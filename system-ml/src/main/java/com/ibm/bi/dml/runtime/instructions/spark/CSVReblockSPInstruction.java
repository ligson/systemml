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

package com.ibm.bi.dml.runtime.instructions.spark;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.spark.api.java.JavaPairRDD;

import com.ibm.bi.dml.hops.recompile.Recompiler;
import com.ibm.bi.dml.runtime.DMLRuntimeException;
import com.ibm.bi.dml.runtime.DMLUnsupportedOperationException;
import com.ibm.bi.dml.runtime.controlprogram.caching.MatrixObject;
import com.ibm.bi.dml.runtime.controlprogram.context.ExecutionContext;
import com.ibm.bi.dml.runtime.controlprogram.context.SparkExecutionContext;
import com.ibm.bi.dml.runtime.instructions.Instruction;
import com.ibm.bi.dml.runtime.instructions.InstructionUtils;
import com.ibm.bi.dml.runtime.instructions.cp.CPOperand;
import com.ibm.bi.dml.runtime.instructions.spark.utils.RDDConverterUtils;
import com.ibm.bi.dml.runtime.matrix.MatrixCharacteristics;
import com.ibm.bi.dml.runtime.matrix.MatrixFormatMetaData;
import com.ibm.bi.dml.runtime.matrix.data.InputInfo;
import com.ibm.bi.dml.runtime.matrix.data.MatrixBlock;
import com.ibm.bi.dml.runtime.matrix.data.MatrixIndexes;
import com.ibm.bi.dml.runtime.matrix.operators.Operator;

public class CSVReblockSPInstruction extends UnarySPInstruction 
{
	private int _brlen;
	private int _bclen;
	private boolean _hasHeader;
	private String _delim;
	private boolean _fill;
	private double _missingValue;

	public CSVReblockSPInstruction(Operator op, CPOperand in, CPOperand out,
			int br, int bc, boolean hasHeader, String delim, boolean fill,
			double missingValue, String opcode, String instr) 
	{
		super(op, in, out, opcode, instr);
		_brlen = br;
		_bclen = bc;
		_hasHeader = hasHeader;
		_delim = delim;
		_fill = fill;
		_missingValue = missingValue;
	}

	public static Instruction parseInstruction(String str)
			throws DMLRuntimeException 
	{
		String opcode = InstructionUtils.getOpCode(str);
		if( !opcode.equals("csvrblk") ) {
			throw new DMLRuntimeException(
					"Incorrect opcode for CSVReblockSPInstruction:" + opcode);
		}

		// Example parts of CSVReblockSPInstruction:
		// [csvrblk, pREADmissing_val_maps·MATRIX·DOUBLE, _mVar37·MATRIX·DOUBLE,
		// 1000, 1000, false, ,, true, 0.0]
		String parts[] = InstructionUtils.getInstructionPartsWithValueType(str);

		CPOperand in = new CPOperand(parts[1]);
		CPOperand out = new CPOperand(parts[2]);
		int brlen = Integer.parseInt(parts[3]);
		int bclen = Integer.parseInt(parts[4]);
		boolean hasHeader = Boolean.parseBoolean(parts[5]);
		String delim = parts[6];
		boolean fill = Boolean.parseBoolean(parts[7]);
		double missingValue = Double.parseDouble(parts[8]);

		return new CSVReblockSPInstruction(null, in, out, brlen, bclen,
				hasHeader, delim, fill, missingValue, opcode, str);
	}

	@Override
	@SuppressWarnings("unchecked")
	public void processInstruction(ExecutionContext ec)
		throws DMLRuntimeException, DMLUnsupportedOperationException 
	{
		SparkExecutionContext sec = (SparkExecutionContext) ec;

		//sanity check input info
		MatrixObject mo = sec.getMatrixObject(input1.getName());
		MatrixFormatMetaData iimd = (MatrixFormatMetaData) mo.getMetaData();
		if (iimd.getInputInfo() != InputInfo.CSVInputInfo) {
			throw new DMLRuntimeException("The given InputInfo is not implemented for "
					+ "CSVReblockSPInstruction:" + iimd.getInputInfo());
		}
		
		//set output characteristics
		MatrixCharacteristics mcIn = sec.getMatrixCharacteristics(input1.getName());
		MatrixCharacteristics mcOut = sec.getMatrixCharacteristics(output.getName());
		mcOut.set(mcIn.getRows(), mcIn.getCols(), _brlen, _bclen);

		//check for in-memory reblock (w/ lazy spark context, potential for latency reduction)
		if( Recompiler.checkCPReblock(sec, input1.getName()) ) {
			Recompiler.executeInMemoryReblock(sec, input1.getName(), output.getName());
			return;
		}
		
		//check jdk version (prevent double.parseDouble contention on <jdk8)
		sec.checkAndRaiseValidationWarningJDKVersion();
		
		//get input rdd (needs to be longwritable/text for consistency with meta data, in case of
		//serialization issues create longwritableser/textser as serializable wrappers
		JavaPairRDD<LongWritable, Text> in = (JavaPairRDD<LongWritable, Text>) 
				sec.getRDDHandleForVariable(input1.getName(), iimd.getInputInfo());
			
		//reblock csv to binary block
		JavaPairRDD<MatrixIndexes, MatrixBlock> out = RDDConverterUtils.csvToBinaryBlock(
				sec.getSparkContext(), in, mcOut, _hasHeader, _delim, _fill, _missingValue);
		
		// put output RDD handle into symbol table
		sec.setRDDHandleForVariable(output.getName(), out);
		sec.addLineageRDD(output.getName(), input1.getName());
	}
}
