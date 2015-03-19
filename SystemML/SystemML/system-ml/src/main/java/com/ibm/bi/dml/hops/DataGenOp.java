/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2015
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.hops;


import java.util.HashMap;
import java.util.Map.Entry;

import com.ibm.bi.dml.api.DMLScript;
import com.ibm.bi.dml.conf.ConfigurationManager;
import com.ibm.bi.dml.conf.DMLConfig;
import com.ibm.bi.dml.hops.rewrite.HopRewriteUtils;
import com.ibm.bi.dml.lops.Lop;
import com.ibm.bi.dml.lops.DataGen;
import com.ibm.bi.dml.lops.LopsException;
import com.ibm.bi.dml.lops.LopProperties.ExecType;
import com.ibm.bi.dml.parser.DMLTranslator;
import com.ibm.bi.dml.parser.DataIdentifier;
import com.ibm.bi.dml.parser.DataExpression;
import com.ibm.bi.dml.parser.Expression.DataType;
import com.ibm.bi.dml.parser.Expression.ValueType;
import com.ibm.bi.dml.parser.Statement;
import com.ibm.bi.dml.runtime.controlprogram.parfor.ProgramConverter;
import com.ibm.bi.dml.sql.sqllops.SQLLopProperties;
import com.ibm.bi.dml.sql.sqllops.SQLLops;
import com.ibm.bi.dml.sql.sqllops.SQLLopProperties.AGGREGATIONTYPE;
import com.ibm.bi.dml.sql.sqllops.SQLLopProperties.JOINTYPE;
import com.ibm.bi.dml.sql.sqllops.SQLLops.GENERATES;

/**
 * 
 * 
 */
public class DataGenOp extends Hop
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2015\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	public static final long UNSPECIFIED_SEED = -1;
	
	 // defines the specific data generation method
	private DataGenMethod _op;
	
	/**
	 * List of "named" input parameters. They are maintained as a hashmap:
	 * parameter names (String) are mapped as indices (Integer) into getInput()
	 * arraylist.
	 * 
	 * i.e., getInput().get(_paramIndexMap.get(parameterName)) refers to the Hop
	 * that is associated with parameterName.
	 */
	private HashMap<String, Integer> _paramIndexMap = new HashMap<String, Integer>();
		

	/** target identifier which will hold the random object */
	private DataIdentifier _id;
	
	//Rand-specific attributes
	
	/** sparsity of the random object, this is used for mem estimate */
	private double _sparsity = -1;
	/** base directory for temp file (e.g., input seeds)*/
	private String _baseDir;
	
	//seq-specific attributes (used for recompile/recompile)
	private double _incr = Double.MAX_VALUE; 
	
	
	private DataGenOp() {
		//default constructor for clone
	}
	
	/**
	 * <p>Creates a new Rand HOP.</p>
	 * 
	 * @param id the target identifier
	 * @param inputParameters HashMap of the input parameters for Rand Hop
	 */
	public DataGenOp(DataGenMethod mthd, DataIdentifier id, HashMap<String, Hop> inputParameters)
	{
		super(Kind.DataGenOp, id.getName(), DataType.MATRIX, ValueType.DOUBLE);
		
		_id = id;
		_op = mthd;

		int index = 0;
		for( Entry<String, Hop> e: inputParameters.entrySet() ) {
			String s = e.getKey();
			Hop input = e.getValue();
			getInput().add(input);
			input.getParent().add(this);

			_paramIndexMap.put(s, index);
			index++;
		}
		if ( mthd == DataGenMethod.RAND )
			_sparsity = Double.valueOf(((LiteralOp)inputParameters.get(DataExpression.RAND_SPARSITY)).getName());
		
		//generate base dir
		String scratch = ConfigurationManager.getConfig().getTextValue(DMLConfig.SCRATCH_SPACE);
		_baseDir = scratch + Lop.FILE_SEPARATOR + Lop.PROCESS_PREFIX + DMLScript.getUUID() + Lop.FILE_SEPARATOR + 
	               Lop.FILE_SEPARATOR + ProgramConverter.CP_ROOT_THREAD_ID + Lop.FILE_SEPARATOR;
		
		//compute unknown dims and nnz
		refreshSizeInformation();
	}
	
	@Override
	public String getOpString() {
		return "dg(" + _op.toString().toLowerCase() +")";
	}
	
	public DataGenMethod getOp() {
		return _op;
	}
	
	@Override
	public Lop constructLops() 
		throws HopsException, LopsException
	{
		if(getLops() == null)
		{
			ExecType et = optFindExecType();
			if(et == ExecType.SPARK) {
				// throw new Exception
				et = ExecType.CP;
			}
			
			HashMap<String, Lop> inputLops = new HashMap<String, Lop>();
			for (Entry<String, Integer> cur : _paramIndexMap.entrySet()) {
				if( cur.getKey().equals(DataExpression.RAND_ROWS) && _dim1>0 )
					inputLops.put(cur.getKey(), new LiteralOp(String.valueOf(_dim1), _dim1).constructLops());
				else if( cur.getKey().equals(DataExpression.RAND_COLS) && _dim2>0 )
					inputLops.put(cur.getKey(), new LiteralOp(String.valueOf(_dim2), _dim2).constructLops());
				else
					inputLops.put(cur.getKey(), getInput().get(cur.getValue()).constructLops());
			}
			
			DataGen rnd = new DataGen(_op, _id, inputLops,_baseDir,
					getDataType(), getValueType(), et);
			
			rnd.getOutputParameters().setDimensions(
					getDim1(), getDim2(),
					//robust handling for blocksize (important for -exec singlenode; otherwise incorrect results)
					(getRowsInBlock()>0)?getRowsInBlock():DMLTranslator.DMLBlockSize, 
					(getColsInBlock()>0)?getColsInBlock():DMLTranslator.DMLBlockSize,  
					getNnz());
			
			rnd.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
			setLops(rnd);
		}
		
		return getLops();
	}
	
	@Override
	public void printMe() throws HopsException
	{	
		if (LOG.isDebugEnabled()){
			if(getVisited() != VisitStatus.DONE)
			{
				super.printMe();
			}

			setVisited(VisitStatus.DONE);
		}
	}

	@Override
	public SQLLops constructSQLLOPs() throws HopsException {
		if(this.getSqlLops() == null)
		{
			
			SQLLops sqllop = new SQLLops("Random" + _id.getName(), GENERATES.PROC,
					this.getValueType(),
					this.getDataType());

			//TODO extend for seed
			sqllop.set_sql("CALL gensparsematrix('" + sqllop.get_tableName() + "', " + this.getDim1() + ", "
					+ this.getDim2() + ");");
			
			sqllop.set_properties(getProperties());
			this.setSqlLops(sqllop);
		}
		return this.getSqlLops();
	}
	
	private SQLLopProperties getProperties()
	{
		SQLLopProperties prop = new SQLLopProperties();
		prop.setJoinType(JOINTYPE.NONE);
		prop.setAggType(AGGREGATIONTYPE.NONE);
		prop.setOpString("Rand");
		return prop;
	}

	@Override
	public boolean allowsAllExecTypes()
	{
		return true;
	}	
	
	@Override
	protected double computeOutputMemEstimate( long dim1, long dim2, long nnz )
	{		
		double ret = 0;
		
		if ( _op == DataGenMethod.RAND ) {
			if( hasConstantValue(0.0) ) { //if empty block
				ret = OptimizerUtils.estimateSizeEmptyBlock(dim1, dim2);
			}
			else {
				//sparsity-aware estimation (dependent on sparse generation approach); for pure dense generation
				//we would need to disable sparsity-awareness and estimate via sparsity=1.0
				ret = OptimizerUtils.estimateSizeExactSparsity(dim1, dim2, _sparsity);
			}
		}
		else {
			ret = OptimizerUtils.estimateSizeExactSparsity(dim1, dim2, 1.0);	
		}
		
		return ret;
	}
	
	@Override
	protected double computeIntermediateMemEstimate( long dim1, long dim2, long nnz )
	{
		if ( _op == DataGenMethod.RAND && dimsKnown() ) {
			long numBlocks = (long) (Math.ceil((double)dim1/DMLTranslator.DMLBlockSize) * Math.ceil((double)dim2/DMLTranslator.DMLBlockSize));
			return 32 + numBlocks*8.0; // 32 bytes of overhead for an array of long & numBlocks long values.
		}
		else 
			return 0;
	}
	
	@Override
	protected long[] inferOutputCharacteristics( MemoTable memo )
	{
		//infer rows and 
		if( (_op == DataGenMethod.RAND || _op == DataGenMethod.SINIT ) &&
			OptimizerUtils.ALLOW_WORSTCASE_SIZE_EXPRESSION_EVALUATION )
		{
			long dim1 = computeDimParameterInformation(getInput().get(_paramIndexMap.get(DataExpression.RAND_ROWS)), memo);
			long dim2 = computeDimParameterInformation(getInput().get(_paramIndexMap.get(DataExpression.RAND_COLS)), memo);
			long nnz = (long)(_sparsity * dim1 * dim2);
			if( dim1>0 && dim2>0 )
				return new long[]{ dim1, dim2, nnz };
		}
		else if ( _op == DataGenMethod.SEQ )
		{
			Hop from = getInput().get(_paramIndexMap.get(Statement.SEQ_FROM));
			Hop incr = getInput().get(_paramIndexMap.get(Statement.SEQ_INCR)); 
			//in order to ensure correctness we also need to know from and incr
			//here, we check for the common case of seq(1,x), i.e. from=1, incr=1
			if(    from instanceof LiteralOp && HopRewriteUtils.getIntValueSafe((LiteralOp)from)==1
				&& incr instanceof LiteralOp && HopRewriteUtils.getIntValueSafe((LiteralOp)incr)==1 )
			{
				long to = computeDimParameterInformation(getInput().get(_paramIndexMap.get(Statement.SEQ_TO)), memo);
				if( to > 0 )	
					return new long[]{ to, 1, -1 };
			}
		}
		
		return null;
	}

	@Override
	protected ExecType optFindExecType() throws HopsException {
		
		checkAndSetForcedPlatform();

		if( _etypeForced != null ) 			
			_etype = _etypeForced;
		else 
		{
			if ( OptimizerUtils.isMemoryBasedOptLevel() ) {
				_etype = findExecTypeByMemEstimate();
			}
			else if (this.areDimsBelowThreshold() || this.isVector())
				_etype = ExecType.CP;
			else
				_etype = ExecType.MR;
		
			//check for valid CP dimensions and matrix size
			checkAndSetInvalidCPDimsAndSize();
			
			//mark for recompile (forever)
			if( OptimizerUtils.ALLOW_DYN_RECOMPILATION && !dimsKnown(true) && _etype==ExecType.MR )
				setRequiresRecompile();
		}
		
		//always force string initialization into CP (not supported in MR)
		if( _op == DataGenMethod.SINIT )
			_etype = ExecType.CP;
		
		return _etype;
	}
	
	@Override
	public void refreshSizeInformation()
	{		
		Hop input1 = null;  
		Hop input2 = null; 
		Hop input3 = null;

		if ( _op == DataGenMethod.RAND || _op == DataGenMethod.SINIT ) 
		{
			input1 = getInput().get(_paramIndexMap.get(DataExpression.RAND_ROWS)); //rows
			input2 = getInput().get(_paramIndexMap.get(DataExpression.RAND_COLS)); //cols
			
			//refresh rows information
			refreshRowsParameterInformation(input1);
			
			//refresh cols information
			refreshColsParameterInformation(input2);
		}
		else if (_op == DataGenMethod.SEQ ) 
		{
			input1 = getInput().get(_paramIndexMap.get(Statement.SEQ_FROM));
			input2 = getInput().get(_paramIndexMap.get(Statement.SEQ_TO)); 
			input3 = getInput().get(_paramIndexMap.get(Statement.SEQ_INCR)); 

			double from = computeBoundsInformation(input1);
			boolean fromKnown = (from != Double.MAX_VALUE);
			
			double to = computeBoundsInformation(input2);
			boolean toKnown = (to != Double.MAX_VALUE);
			
			double incr = computeBoundsInformation(input3);
			boolean incrKnown = (incr != Double.MAX_VALUE);
			
			if(  !incrKnown && input3.getKind() == Kind.BinaryOp //special case for incr
			   && ((BinaryOp)input3).getOp() == Hop.OpOp2.SEQINCR && fromKnown && toKnown) 
			{
				incr = ( from >= to ) ? -1 : 1;
				incrKnown = true;
			}
			
			if ( fromKnown && toKnown && incrKnown ) {
				setDim1(1 + (long)Math.floor(((double)(to-from))/incr));
				setDim2(1);
				_incr = incr;
			}
		}
		
		//refresh nnz information (for seq, sparsity is always -1)
		if( _op == DataGenMethod.RAND && hasConstantValue(0.0) )
			_nnz = 0;
		else if ( dimsKnown() && _sparsity>=0 ) //general case
			_nnz = (long) (_sparsity * _dim1 * _dim2);
	}
	

	public HashMap<String, Integer> getParamIndexMap()
	{
		return _paramIndexMap;
	}
	
	public int getParamIndex(String key)
	{
		return _paramIndexMap.get(key);
	}
	
	/**
	 * 
	 * @return
	 */
	public boolean hasConstantValue() 
	{
		//string initialization does not exhibit constant values
		if( _op == DataGenMethod.SINIT )
			return false;
		
		Hop min = getInput().get(_paramIndexMap.get(DataExpression.RAND_MIN)); //min 
		Hop max = getInput().get(_paramIndexMap.get(DataExpression.RAND_MAX)); //max
		
		//literal value comparison
		if( min instanceof LiteralOp && max instanceof LiteralOp){
			try{
				double minVal = HopRewriteUtils.getDoubleValue((LiteralOp)min);
				double maxVal = HopRewriteUtils.getDoubleValue((LiteralOp)max);
				return (minVal == maxVal);
			}
			catch(Exception ex)
			{
				return false;
			}
		}
		
		//reference comparison (based on common subexpression elimination)
		return (min == max);
	}
	
	/**
	 * 
	 * @param val
	 * @return
	 */
	public boolean hasConstantValue(double val) 
	{
		//string initialization does not exhibit constant values
		if( _op == DataGenMethod.SINIT )
			return false;
		
		Hop min = getInput().get(_paramIndexMap.get(DataExpression.RAND_MIN)); //min 
		Hop max = getInput().get(_paramIndexMap.get(DataExpression.RAND_MAX)); //max
		
		//literal value comparison
		if( min instanceof LiteralOp && max instanceof LiteralOp){
			try{
				double minVal = HopRewriteUtils.getDoubleValue((LiteralOp)min);
				double maxVal = HopRewriteUtils.getDoubleValue((LiteralOp)max);
				return (minVal == val && maxVal == val);
			}
			catch(Exception ex)
			{
				return false;
			}
		}
		
		return false;
	}
	
	public void setIncrementValue(double incr)
	{
		_incr = incr;
	}
	
	public double getIncrementValue()
	{
		return _incr;
	}
	
	public static long generateRandomSeed()
	{
		return System.nanoTime();
	}
	

	@SuppressWarnings("unchecked")
	@Override
	public Object clone() throws CloneNotSupportedException 
	{
		DataGenOp ret = new DataGenOp();	
		
		//copy generic attributes
		ret.clone(this, false);
		
		//copy specific attributes
		ret._op = _op;
		ret._id = _id;
		ret._sparsity = _sparsity;
		ret._baseDir = _baseDir;
		ret._paramIndexMap = (HashMap<String, Integer>) _paramIndexMap.clone();
		//note: no deep cp of params since read-only 
		
		return ret;
	}
	
	@Override
	public boolean compare( Hop that )
	{
		if( that._kind!=Kind.DataGenOp )
			return false;
		
		DataGenOp that2 = (DataGenOp)that;	
		boolean ret = (  _op == that2._op
				      && _sparsity == that2._sparsity
				      && _baseDir.equals(that2._baseDir)
					  && _paramIndexMap!=null && that2._paramIndexMap!=null );
		
		if( ret )
		{
			for( Entry<String,Integer> e : _paramIndexMap.entrySet() )
			{
				String key1 = e.getKey();
				int pos1 = e.getValue();
				int pos2 = that2._paramIndexMap.get(key1);
				ret &= (   that2.getInput().get(pos2)!=null
					    && getInput().get(pos1) == that2.getInput().get(pos2) );
			}
			
			//special case for rand seed (no CSE if unspecified seed because runtime generated)
			//note: if min and max is constant, we can safely merge those hops
			if( _op == DataGenMethod.RAND || _op == DataGenMethod.SINIT ){
				Hop seed = getInput().get(_paramIndexMap.get(DataExpression.RAND_SEED));
				Hop min = getInput().get(_paramIndexMap.get(DataExpression.RAND_MIN));
				Hop max = getInput().get(_paramIndexMap.get(DataExpression.RAND_MAX));
				if( seed.getName().equals(String.valueOf(DataGenOp.UNSPECIFIED_SEED)) && min != max )
					ret = false;
			}
		}
		
		return ret;
	}
}