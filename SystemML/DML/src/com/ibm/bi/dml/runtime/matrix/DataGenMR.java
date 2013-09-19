package com.ibm.bi.dml.runtime.matrix;

import java.io.PrintWriter;
import java.util.HashSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.math.random.Well1024a;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.mapred.Counters.Group;

import com.ibm.bi.dml.lops.Lops;
import com.ibm.bi.dml.lops.compile.JobType;
import com.ibm.bi.dml.lops.runtime.RunMRJobs;
import com.ibm.bi.dml.lops.runtime.RunMRJobs.ExecMode;
import com.ibm.bi.dml.runtime.instructions.MRInstructionParser;
import com.ibm.bi.dml.runtime.instructions.MRJobInstruction;
import com.ibm.bi.dml.runtime.instructions.CPInstructions.RandCPInstruction;
import com.ibm.bi.dml.runtime.instructions.MRInstructions.DataGenMRInstruction;
import com.ibm.bi.dml.runtime.instructions.MRInstructions.MRInstruction;
import com.ibm.bi.dml.runtime.instructions.MRInstructions.RandInstruction;
import com.ibm.bi.dml.runtime.instructions.MRInstructions.SeqInstruction;
import com.ibm.bi.dml.runtime.instructions.MRInstructions.MRInstruction.MRINSTRUCTION_TYPE;
import com.ibm.bi.dml.runtime.matrix.io.InputInfo;
import com.ibm.bi.dml.runtime.matrix.io.MatrixIndexes;
import com.ibm.bi.dml.runtime.matrix.io.OutputInfo;
import com.ibm.bi.dml.runtime.matrix.io.TaggedMatrixBlock;
import com.ibm.bi.dml.runtime.matrix.mapred.GMRCombiner;
import com.ibm.bi.dml.runtime.matrix.mapred.GMRReducer;
import com.ibm.bi.dml.runtime.matrix.mapred.MRConfigurationNames;
import com.ibm.bi.dml.runtime.matrix.mapred.MRJobConfiguration;
import com.ibm.bi.dml.runtime.matrix.mapred.DataGenMapper;
import com.ibm.bi.dml.runtime.matrix.mapred.MRJobConfiguration.MatrixChar_N_ReducerGroups;
import com.ibm.bi.dml.runtime.util.MapReduceTool;
import com.ibm.bi.dml.utils.DMLRuntimeException;


/**
 * <p>Rand MapReduce job which creates random objects.</p>
 * 
 */
public class DataGenMR
{
	/**
	 * <p>Starts a Rand MapReduce job which will produce one or more random objects.</p>
	 * 
	 * @param numRows number of rows for each random object
	 * @param numCols number of columns for each random object
	 * @param blockRowSize number of rows in a block for each random object
	 * @param blockColSize number of columns in a block for each random object
	 * @param minValue minimum of the random values for each random object
	 * @param maxValue maximum of the random values for each random object
	 * @param sparsity sparsity for each random object
	 * @param pdf probability density function for each random object
	 * @param replication file replication
	 * @param inputs input file for each random object
	 * @param outputs output file for each random object
	 * @param outputInfos output information for each random object
	 * @param instructionsInMapper instruction for each random object
	 * @param resultIndexes result indexes for each random object
	 * @return matrix characteristics for each random object
	 * @throws Exception if an error occurres in the MapReduce phase
	 */
	private static final Log LOG = LogFactory.getLog(DataGenMR.class.getName());
	
	public static JobReturn runJob(MRJobInstruction inst, String[] dataGenInstructions, 
			String instructionsInMapper, String aggInstructionsInReducer, String otherInstructionsInReducer, 
			int numReducers, int replication, byte[] resultIndexes, String dimsUnknownFilePrefix, 
			String[] outputs, OutputInfo[] outputInfos) 
	throws Exception
	{
		JobConf job;
		job = new JobConf(DataGenMR.class);
		job.setJobName("DataGen-MR");
		
		//whether use block representation or cell representation
		MRJobConfiguration.setMatrixValueClass(job, true);
		
		
		byte[] realIndexes=new byte[dataGenInstructions.length];
		for(byte b=0; b<realIndexes.length; b++)
			realIndexes[b]=b;
		
		String[] inputs=new String[dataGenInstructions.length];
		InputInfo[] inputInfos = new InputInfo[dataGenInstructions.length];
		long[] rlens=new long[dataGenInstructions.length];
		long[] clens=new long[dataGenInstructions.length];
		int[] brlens=new int[dataGenInstructions.length];
		int[] bclens=new int[dataGenInstructions.length];
		
		FileSystem fs = FileSystem.get(job);
		String dataGenInsStr="";
		int numblocks=0;
		int maxbrlen=-1, maxbclen=-1;
		double maxsparsity = -1;
		
		for(int i = 0; i < dataGenInstructions.length; i++)
		{
			dataGenInsStr=dataGenInsStr+Lops.INSTRUCTION_DELIMITOR+dataGenInstructions[i];
			
			MRInstruction mrins = MRInstructionParser.parseSingleInstruction(dataGenInstructions[i]);
			MRINSTRUCTION_TYPE mrtype = mrins.getMRInstructionType();
			DataGenMRInstruction genInst = (DataGenMRInstruction) mrins;
			
			rlens[i]  = genInst.rows;
			clens[i]  = genInst.cols;
			brlens[i] = genInst.rowsInBlock;
			bclens[i] = genInst.colsInBlock;
			
			maxbrlen = Math.max(maxbrlen, brlens[i]);
			maxbclen = Math.max(maxbclen, bclens[i]);

			if ( mrtype == MRINSTRUCTION_TYPE.Rand ) 
			{
				RandInstruction randInst = (RandInstruction) mrins;
				inputs[i]=genInst.baseDir + System.currentTimeMillis()+".randinput";//+random.nextInt();
				maxsparsity = Math.max(maxsparsity, randInst.sparsity);
				
				FSDataOutputStream fsOut = fs.create(new Path(inputs[i]));
				PrintWriter pw = new PrintWriter(fsOut);
				//for obj reuse and preventing repeated buffer re-allocations
				StringBuilder sb = new StringBuilder();
				
				//seed generation
				Well1024a bigrand = RandCPInstruction.setupSeedsForRand(randInst.seed);
				for(long r = 0; r < rlens[i]; r += brlens[i]) {
					long curBlockRowSize = Math.min(brlens[i], (rlens[i] - r));
					for(long c = 0; c < clens[i]; c += bclens[i])
					{
						long curBlockColSize = Math.min(bclens[i], (clens[i] - c));
						
						sb.append((r / brlens[i]) + 1);
						sb.append(',');
						sb.append((c / bclens[i]) + 1);
						sb.append(',');
						sb.append(curBlockRowSize);
						sb.append(',');
						sb.append(curBlockColSize);
						sb.append(',');
						sb.append(bigrand.nextLong());
						pw.println(sb.toString());
						sb.setLength(0);
						numblocks++;
					}
				}
				pw.close();
				fsOut.close();
				inputInfos[i] = InputInfo.TextCellInputInfo;
			}
			else if ( mrtype == MRINSTRUCTION_TYPE.Seq ) 
			{
				SeqInstruction seqInst = (SeqInstruction) mrins;
				inputs[i]=genInst.baseDir + System.currentTimeMillis()+".seqinput";
				maxsparsity = 1.0; //always dense
				
				FSDataOutputStream fsOut = fs.create(new Path(inputs[i]));
				PrintWriter pw = new PrintWriter(fsOut);
				
				//for obj reuse and preventing repeated buffer re-allocations
				StringBuilder sb = new StringBuilder();
				
				double from = seqInst.fromValue;
				double to = seqInst.toValue;
				double incr = seqInst.incrValue;
				
				// Correctness checks on (from, to, incr)
				boolean neg = (from > to);
				if ( incr == 0 )
					throw new DMLRuntimeException("Invalid value for \"increment\" in seq().");
				
				if (neg != (incr < 0) )
					throw new DMLRuntimeException("Wrong sign for the increment in a call to seq()");
				
				// Compute the number of rows in the sequence
				long numrows = 1 + (long)Math.floor((to-from)/incr);
				if ( rlens[i] > 0 ) {
					if ( numrows != rlens[i] )
						throw new DMLRuntimeException("Unexpected error while processing sequence instruction. Expected number of rows does not match given number: " + rlens[i] + " != " + numrows);
				}
				else {
					rlens[i] = numrows;
				}
				
				if ( clens[i] >0 && clens[i] != 1)
					throw new DMLRuntimeException("Unexpected error while processing sequence instruction. Number of columns (" + clens[i] + ") must be equal to 1.");
				else 
					clens[i] = 1;
				
				double temp = from;
				int bid_i, bid_j;
				double block_from, block_to;
				for(long r = 0; r < rlens[i]; r += brlens[i]) {
					long curBlockRowSize = Math.min(brlens[i], (rlens[i] - r));
					
					// block (bid_i,bid_j) generates a sequence from the interval [block_from, block_to] (inclusive of both end points of the interval) 
					bid_i = (int) ((r / brlens[i]) + 1);
					bid_j = 1;
					block_from = temp;
					block_to   = temp+(curBlockRowSize-1)*incr;
					temp = block_to + incr; // next block starts from here
					
					sb.append(bid_i);
					sb.append(',');
					sb.append(bid_j);
					sb.append(',');
					/*
					// Need not include block size while generating seq()
					sb.append(curBlockRowSize);
					sb.append(',');
					sb.append(1);
					sb.append(',');*/
					sb.append(block_from);
					sb.append(',');
					sb.append(block_to);
					sb.append(',');
					sb.append(incr);
					
					pw.println(sb.toString());
					//System.out.println("MapTask " + r + ": " + sb.toString());
					sb.setLength(0);
					numblocks++;
				}
				
				pw.close();
				fsOut.close();
				inputInfos[i] = InputInfo.TextCellInputInfo;
			} else {
				throw new DMLRuntimeException("Unexpected Data Generation Instruction Type: " + mrtype );
			}
		}
		dataGenInsStr=dataGenInsStr.substring(1);//remove the first ","
		RunningJob runjob;
		MatrixCharacteristics[] stats;
		try{
			//set up the block size
			MRJobConfiguration.setBlocksSizes(job, realIndexes, brlens, bclens);
			
			//set up the input files and their format information
			MRJobConfiguration.setUpMultipleInputs(job, realIndexes, inputs, inputInfos, true, brlens, bclens, false);
			
			//set up the dimensions of input matrices
			MRJobConfiguration.setMatricesDimensions(job, realIndexes, rlens, clens);
			MRJobConfiguration.setDimsUnknownFilePrefix(job, dimsUnknownFilePrefix);
			
			//set up the block size
			MRJobConfiguration.setBlocksSizes(job, realIndexes, brlens, bclens);
			
			//set up the rand Instructions
			MRJobConfiguration.setRandInstructions(job, dataGenInsStr);
			
			//set up unary instructions that will perform in the mapper
			MRJobConfiguration.setInstructionsInMapper(job, instructionsInMapper);
			
			//set up the aggregate instructions that will happen in the combiner and reducer
			MRJobConfiguration.setAggregateInstructions(job, aggInstructionsInReducer);
			
			//set up the instructions that will happen in the reducer, after the aggregation instrucions
			MRJobConfiguration.setInstructionsInReducer(job, otherInstructionsInReducer);
			
			
			//set up the replication factor for the results
			job.setInt("dfs.replication", replication);
			
			JobClient client=new JobClient(job);
			int capacity=client.getClusterStatus().getMaxMapTasks();
			int dfsblocksize=job.getInt(MRConfigurationNames.DFS_BLOCK_SIZE, 134217728);
			          
			//nmappers: 1<=n<=capacity, TODO use maxsparsity whenever we have a way of generating sparse rand data
			int nmapers=Math.max(Math.min((int)(8*maxbrlen*maxbclen*(long)numblocks/(long)dfsblocksize), capacity),1);
			job.setNumMapTasks(nmapers);
			
			//set up what matrices are needed to pass from the mapper to reducer
			HashSet<Byte> mapoutputIndexes=MRJobConfiguration.setUpOutputIndexesForMapper(job, realIndexes,  dataGenInsStr, instructionsInMapper, null, aggInstructionsInReducer, otherInstructionsInReducer, resultIndexes);
			
			MatrixChar_N_ReducerGroups ret=MRJobConfiguration.computeMatrixCharacteristics(job, realIndexes, dataGenInsStr,
					instructionsInMapper, null, aggInstructionsInReducer, null, otherInstructionsInReducer, 
					resultIndexes, mapoutputIndexes, false);
			stats=ret.stats;
			
			//set up the number of reducers
			MRJobConfiguration.setNumReducers(job, ret.numReducerGroups, numReducers);
			
			// print the complete MRJob instruction
			if (LOG.isTraceEnabled())
				inst.printCompelteMRJobInstruction(stats);
			
			// Update resultDimsUnknown based on computed "stats"
			byte[] resultDimsUnknown = new byte[resultIndexes.length]; 
			for ( int i=0; i < resultIndexes.length; i++ ) { 
				if ( stats[i].numRows == -1 || stats[i].numColumns == -1 ) {
					resultDimsUnknown[i] = (byte) 1;
				}
				else {
					resultDimsUnknown[i] = (byte) 0;
				}
			}
			
			boolean mayContainCtable = instructionsInMapper.contains("ctabletransform") ||instructionsInMapper.contains("groupedagg") ; 
			
			//set up the multiple output files, and their format information
			MRJobConfiguration.setUpMultipleOutputs(job, resultIndexes, resultDimsUnknown, outputs, outputInfos, true, mayContainCtable);
			
			// configure mapper and the mapper output key value pairs
			job.setMapperClass(DataGenMapper.class);
			if(numReducers==0)
			{
				job.setMapOutputKeyClass(Writable.class);
				job.setMapOutputValueClass(Writable.class);
			}else
			{
				job.setMapOutputKeyClass(MatrixIndexes.class);
				job.setMapOutputValueClass(TaggedMatrixBlock.class);
			}
			
			//set up combiner
			if(numReducers!=0 && aggInstructionsInReducer!=null 
					&& !aggInstructionsInReducer.isEmpty())
				job.setCombinerClass(GMRCombiner.class);
		
			//configure reducer
			job.setReducerClass(GMRReducer.class);
			//job.setReducerClass(PassThroughReducer.class);

			// By default, the job executes in "cluster" mode.
			// Determine if we can optimize and run it in "local" mode.
			MatrixCharacteristics[] inputStats = new MatrixCharacteristics[inputs.length];
			for ( int i=0; i < inputs.length; i++ ) {
				inputStats[i] = new MatrixCharacteristics(rlens[i], clens[i], brlens[i], bclens[i]);
			}
			ExecMode mode = RunMRJobs.getExecMode(JobType.RAND, inputStats); 
			if ( mode == ExecMode.LOCAL ) {
				job.set("mapred.job.tracker", "local");
				MRJobConfiguration.setStagingDir( job );
			}

			//set unique working dir
			MRJobConfiguration.setUniqueWorkingDir(job, mode);
			
			
			runjob=JobClient.runJob(job);
			
			/* Process different counters */
			
			Group group=runjob.getCounters().getGroup(MRJobConfiguration.NUM_NONZERO_CELLS);
			for(int i=0; i<resultIndexes.length; i++) {
				// number of non-zeros
				stats[i].nonZero=group.getCounter(Integer.toString(i));
				//	System.out.println("result #"+resultIndexes[i]+" ===>\n"+stats[i]);
			}
			
			String dir = dimsUnknownFilePrefix + "/" + runjob.getID().toString() + "_dimsFile";
			stats = MapReduceTool.processDimsFiles(dir, stats);
			MapReduceTool.deleteFileIfExistOnHDFS(dir);
			
/*			Group rowgroup, colgroup;
			for(int i=0; i<resultIndexes.length; i++)
			{
				// number of non-zeros
				stats[i].nonZero=group.getCounter(Integer.toString(i));
			//	System.out.println("result #"+resultIndexes[i]+" ===>\n"+stats[i]);
				
				// compute dimensions for output matrices whose dimensions are unknown at compilation time 
				if ( stats[i].numRows == -1 || stats[i].numColumns == -1 ) {
					if ( resultDimsUnknown[i] != (byte) 1 )
						throw new DMLRuntimeException("Unexpected error after executing Rand Job");
				
					rowgroup = runjob.getCounters().getGroup("max_rowdim_"+i);
					colgroup = runjob.getCounters().getGroup("max_coldim_"+i);
					int maxrow, maxcol;
					maxrow = maxcol = 0;
					for ( int rid=0; rid < numReducers; rid++ ) {
						if ( maxrow < (int) rowgroup.getCounter(Integer.toString(rid)) )
							maxrow = (int) rowgroup.getCounter(Integer.toString(rid));
						if ( maxcol < (int) colgroup.getCounter(Integer.toString(rid)) )
							maxcol = (int) colgroup.getCounter(Integer.toString(rid)) ;
					}
					//System.out.println("Resulting Rows = " + maxrow + ", Cols = " + maxcol );
					stats[i].numRows = maxrow;
					stats[i].numColumns = maxcol;
				}
			}
*/			
		}finally
		{
			for(String input: inputs)
				MapReduceTool.deleteFileIfExistOnHDFS(new Path(input), job);
		}
		
		return new JobReturn(stats, outputInfos, runjob.isSuccessful());
	}
}