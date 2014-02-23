/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2013
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.lops;

import com.ibm.bi.dml.lops.LopProperties.ExecLocation;
import com.ibm.bi.dml.lops.LopProperties.ExecType;
import com.ibm.bi.dml.lops.OutputParameters.Format;
import com.ibm.bi.dml.lops.compile.JobType;
import com.ibm.bi.dml.parser.Expression.DataType;
import com.ibm.bi.dml.parser.Expression.ValueType;


/**
 * Lop to perform reblock operation
 */
public class ReBlock extends Lop 
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2013\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	public static final String OPCODE = "rblk"; 
	
	/**
	 * Constructor to perform a reblock operation. 
	 * @param input
	 * @param op
	 */
	
	Long rows_per_block;
	Long cols_per_block;

	public ReBlock(Lop input, Long rows_per_block, Long cols_per_block, DataType dt, ValueType vt) throws LopsException 
	{
		super(Lop.Type.ReBlock, dt, vt);		
		this.addInput(input);
		input.addOutput(this);
		
		this.rows_per_block = rows_per_block;
		this.cols_per_block = cols_per_block;
		
		/*
		 * This lop can be executed only in REBLOCK job.
		 */
		boolean breaksAlignment = false;
		boolean aligner = false;
		boolean definesMRJob = true;
		
		lps.addCompatibility(JobType.REBLOCK);
		this.lps.setProperties( inputs, ExecType.MR, ExecLocation.MapAndReduce, breaksAlignment, aligner, definesMRJob );
	}

	@Override
	public String toString() {
	
		return "Reblock - rows per block = " + rows_per_block + " cols per block  " + cols_per_block ;
	}

	@Override
	public String getInstructions(int input_index, int output_index) throws LopsException
	{
		StringBuilder sb = new StringBuilder();
		sb.append( getExecType() );
		sb.append( Lop.OPERAND_DELIMITOR );
		sb.append( OPCODE );
		sb.append( OPERAND_DELIMITOR );
		
		sb.append( getInputs().get(0).prepInputOperand(input_index));
		sb.append( OPERAND_DELIMITOR );
		
		sb.append ( this.prepOutputOperand(output_index));
		sb.append( OPERAND_DELIMITOR );
		
		sb.append( rows_per_block );
		sb.append( OPERAND_DELIMITOR );
		sb.append( cols_per_block );
		
		return sb.toString();
	}
	
	// This function is replicated in Dag.java
	private Format getChildFormat(Lop node) throws LopsException {
		
		if(node.getOutputParameters().getFile_name() != null
				|| node.getOutputParameters().getLabel() != null)
		{
			return node.getOutputParameters().getFormat();
		}
		else
		{
			// Reblock lop should always have a single child
			if(node.getInputs().size() > 1)
				throw new LopsException(this.printErrorLocation() + "Should only have one child! \n");
			
			/*
			 * Return the format of the child node (i.e., input lop)
			 * No need of recursion here.. because
			 * 1) Reblock lop's input can either be DataLop or some intermediate computation
			 *    If it is Data then we just take its format (TEXT or BINARY)
			 *    If it is intermediate lop then it is always BINARY 
			 *      since we assume that all intermediate computations will be in Binary format
			 * 2) Note that Reblock job will never have any instructions in the mapper 
			 *    => the input lop (if it is other than Data) is always executed in a different job
			 */
			// return getChildFormat(node.getInputs().get(0));
			return node.getInputs().get(0).getOutputParameters().getFormat();		}
		
	}

 
 
}