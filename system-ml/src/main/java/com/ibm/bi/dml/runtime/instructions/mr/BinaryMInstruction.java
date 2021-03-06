/**
 * (C) Copyright IBM Corp. 2010, 2015
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package com.ibm.bi.dml.runtime.instructions.mr;

import java.util.ArrayList;

import com.ibm.bi.dml.lops.AppendM.CacheType;
import com.ibm.bi.dml.lops.BinaryM.VectorType;
import com.ibm.bi.dml.runtime.DMLRuntimeException;
import com.ibm.bi.dml.runtime.DMLUnsupportedOperationException;
import com.ibm.bi.dml.runtime.instructions.Instruction;
import com.ibm.bi.dml.runtime.instructions.InstructionUtils;
import com.ibm.bi.dml.runtime.matrix.data.MatrixValue;
import com.ibm.bi.dml.runtime.matrix.data.OperationsOnMatrixValues;
import com.ibm.bi.dml.runtime.matrix.mapred.CachedValueMap;
import com.ibm.bi.dml.runtime.matrix.mapred.DistributedCacheInput;
import com.ibm.bi.dml.runtime.matrix.mapred.IndexedMatrixValue;
import com.ibm.bi.dml.runtime.matrix.mapred.MRBaseForCommonInstructions;
import com.ibm.bi.dml.runtime.matrix.operators.BinaryOperator;
import com.ibm.bi.dml.runtime.matrix.operators.Operator;


public class BinaryMInstruction extends BinaryMRInstructionBase implements IDistributedCacheConsumer
{	
	private VectorType _vectorType = null;
	
	public BinaryMInstruction(Operator op, byte in1, byte in2, CacheType ctype, VectorType vtype, byte out, String istr)
	{
		super(op, in1, in2, out);
		mrtype = MRINSTRUCTION_TYPE.ArithmeticBinary;
		instString = istr;
		
		_vectorType = vtype;
	}
	
	/**
	 * 
	 * @param str
	 * @return
	 * @throws DMLRuntimeException
	 */
	public static Instruction parseInstruction ( String str ) 
		throws DMLRuntimeException 
	{	
		InstructionUtils.checkNumFields ( str, 5 );
		
		String[] parts = InstructionUtils.getInstructionParts ( str );
		
		byte in1, in2, out;
		String opcode = parts[0];
		in1 = Byte.parseByte(parts[1]);
		in2 = Byte.parseByte(parts[2]);
		out = Byte.parseByte(parts[3]);
		CacheType ctype = CacheType.valueOf(parts[4]);
		VectorType vtype = VectorType.valueOf(parts[5]);
		
		BinaryOperator bop = InstructionUtils.parseExtendedBinaryOperator(opcode);
		return new BinaryMInstruction(bop, in1, in2, ctype, vtype, out, str);
	}
	
	@Override
	public void processInstruction(Class<? extends MatrixValue> valueClass,
			CachedValueMap cachedValues, IndexedMatrixValue tempValue, IndexedMatrixValue zeroInput,
			int blockRowFactor, int blockColFactor)
		throws DMLUnsupportedOperationException, DMLRuntimeException 
	{	
		ArrayList<IndexedMatrixValue> blkList = cachedValues.get(input1);
		if( blkList == null ) 
			return;
		
		for(IndexedMatrixValue in1 : blkList)
		{
			//allocate space for the output value
			//try to avoid coping as much as possible
			IndexedMatrixValue out;
			if( (output!=input1 && output!=input2) )
				out=cachedValues.holdPlace(output, valueClass);
			else
				out=tempValue;
			
			//get second 
			DistributedCacheInput dcInput = MRBaseForCommonInstructions.dcValues.get(input2);
			IndexedMatrixValue in2 = null;
			if( _vectorType == VectorType.COL_VECTOR )
				in2 = dcInput.getDataBlock((int)in1.getIndexes().getRowIndex(), 1);
			else //_vectorType == VectorType.ROW_VECTOR
				in2 = dcInput.getDataBlock(1, (int)in1.getIndexes().getColumnIndex());
			
			//process instruction
			out.getIndexes().setIndexes(in1.getIndexes());
			OperationsOnMatrixValues.performBinaryIgnoreIndexes(in1.getValue(), 
					in2.getValue(), out.getValue(), ((BinaryOperator)optr));
			
			//put the output value in the cache
			if(out==tempValue)
				cachedValues.add(output, out);
		}
	}

	@Override //IDistributedCacheConsumer
	public boolean isDistCacheOnlyIndex( String inst, byte index )
	{
		return (index==input2 && index!=input1);
	}
	
	@Override //IDistributedCacheConsumer
	public void addDistCacheIndex( String inst, ArrayList<Byte> indexes )
	{
		indexes.add(input2);
	}
}
