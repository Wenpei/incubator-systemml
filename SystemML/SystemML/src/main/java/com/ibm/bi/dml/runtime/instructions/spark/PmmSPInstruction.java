/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2015
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.runtime.instructions.spark;


import java.util.ArrayList;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import org.apache.spark.broadcast.Broadcast;

import scala.Tuple2;

import com.ibm.bi.dml.api.DMLException; 
import com.ibm.bi.dml.hops.OptimizerUtils;
import com.ibm.bi.dml.lops.MapMult.CacheType;
import com.ibm.bi.dml.lops.PMMJ;
import com.ibm.bi.dml.parser.Expression.DataType;
import com.ibm.bi.dml.parser.Expression.ValueType;
import com.ibm.bi.dml.runtime.DMLRuntimeException;
import com.ibm.bi.dml.runtime.DMLUnsupportedOperationException;
import com.ibm.bi.dml.runtime.controlprogram.context.ExecutionContext;
import com.ibm.bi.dml.runtime.controlprogram.context.SparkExecutionContext;
import com.ibm.bi.dml.runtime.functionobjects.Multiply;
import com.ibm.bi.dml.runtime.functionobjects.Plus;
import com.ibm.bi.dml.runtime.instructions.InstructionUtils;
import com.ibm.bi.dml.runtime.instructions.cp.CPOperand;
import com.ibm.bi.dml.runtime.instructions.spark.functions.AggregateSumMultiBlockFunction;
import com.ibm.bi.dml.runtime.matrix.MatrixCharacteristics;
import com.ibm.bi.dml.runtime.matrix.data.MatrixBlock;
import com.ibm.bi.dml.runtime.matrix.data.MatrixIndexes;
import com.ibm.bi.dml.runtime.matrix.operators.AggregateBinaryOperator;
import com.ibm.bi.dml.runtime.matrix.operators.AggregateOperator;
import com.ibm.bi.dml.runtime.matrix.operators.Operator;
import com.ibm.bi.dml.runtime.util.UtilFunctions;

/**
 * 
 */
public class PmmSPInstruction extends BinarySPInstruction 
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2015\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	private CacheType _type = null;
	private CPOperand _nrow = null;
	
	public PmmSPInstruction(Operator op, CPOperand in1, CPOperand in2, CPOperand out, CPOperand nrow,
			                CacheType type, String opcode, String istr )
	{
		super(op, in1, in2, out, opcode, istr);
		_sptype = SPINSTRUCTION_TYPE.PMM;		
		_type = type;
		_nrow = nrow;
	}

	/**
	 * 
	 * @param str
	 * @return
	 * @throws DMLRuntimeException
	 */
	public static PmmSPInstruction parseInstruction( String str ) 
		throws DMLRuntimeException 
	{
		CPOperand in1 = new CPOperand("", ValueType.UNKNOWN, DataType.UNKNOWN);
		CPOperand in2 = new CPOperand("", ValueType.UNKNOWN, DataType.UNKNOWN);
		CPOperand nrow = new CPOperand("", ValueType.UNKNOWN, DataType.UNKNOWN);
		CPOperand out = new CPOperand("", ValueType.UNKNOWN, DataType.UNKNOWN);

		String opcode = InstructionUtils.getOpCode(str);

		if ( opcode.equalsIgnoreCase(PMMJ.OPCODE)) {
			String parts[] = InstructionUtils.getInstructionPartsWithValueType(str);
			in1.split(parts[1]);
			in2.split(parts[2]);
			nrow.split(parts[3]);
			out.split(parts[4]);
			CacheType type = CacheType.valueOf(parts[5]);
			
			AggregateOperator agg = new AggregateOperator(0, Plus.getPlusFnObject());
			AggregateBinaryOperator aggbin = new AggregateBinaryOperator(Multiply.getMultiplyFnObject(), agg);
			return new PmmSPInstruction(aggbin, in1, in2, out, nrow, type, opcode, str);
		} 
		else {
			throw new DMLRuntimeException("PmmSPInstruction.parseInstruction():: Unknown opcode " + opcode);
		}	
	}
	
	@Override
	public void processInstruction(ExecutionContext ec) 
		throws DMLRuntimeException, DMLUnsupportedOperationException
	{	
		SparkExecutionContext sec = (SparkExecutionContext)ec;
		
		String rddVar = (_type==CacheType.LEFT) ? input2.getName() : input1.getName();
		String bcastVar = (_type==CacheType.LEFT) ? input1.getName() : input2.getName();
		MatrixCharacteristics mc = sec.getMatrixCharacteristics(output.getName());
		long rlen = sec.getScalarInput(_nrow.getName(), _nrow.getValueType(), _nrow.isLiteral()).getLongValue();
		
		//get inputs
		JavaPairRDD<MatrixIndexes,MatrixBlock> in1 = sec.getBinaryBlockRDDHandleForVariable( rddVar );
		Broadcast<MatrixBlock> in2 = sec.getBroadcastForVariable( bcastVar ); 
		
		//execute pmm instruction
		JavaPairRDD<MatrixIndexes,MatrixBlock> out = in1
				.flatMapToPair( new RDDPMMFunction(_type, in2, rlen, mc.getRowsPerBlock()) )
				.reduceByKey( new AggregateSumMultiBlockFunction() );
		
		//put output RDD handle into symbol table
		sec.setRDDHandleForVariable(output.getName(), out);
		sec.addLineageRDD(output.getName(), rddVar);
		sec.addLineageBroadcast(output.getName(), bcastVar);
		
		//update output statistics if not inferred
		updateBinaryMMOutputMatrixCharacteristics(sec, false);
	}
	
	/**
	 * 
	 * 
	 */
	private static class RDDPMMFunction implements PairFlatMapFunction<Tuple2<MatrixIndexes, MatrixBlock>, MatrixIndexes, MatrixBlock> 
	{
		private static final long serialVersionUID = -1696560050436469140L;
		
		private long _rlen = -1;
		private int _brlen = -1;
		
		private MatrixBlock[] _partBlocks = null;
		
		public RDDPMMFunction( CacheType type, Broadcast<MatrixBlock> binput, long rlen, int brlen )
		{
			_brlen = brlen;
			_rlen = rlen;
			
			//get the broadcast vector
			MatrixBlock mb = binput.value();
			
			//partition vector for fast in memory lookup
			try
			{
				// right now always CacheType.LEFT 
				//in-memory colblock partitioning (according to brlen of rdd)
				int lrlen = mb.getNumRows();
				int numBlocks = (int)Math.ceil((double)lrlen/_brlen);	
				
				_partBlocks = new MatrixBlock[numBlocks];
				for( int i=0; i<numBlocks; i++ )
				{
					MatrixBlock tmp = new MatrixBlock();
					mb.sliceOperations( i*_brlen+1, Math.min((i+1)*_brlen, mb.getNumRows()), 1, 1, tmp);
					_partBlocks[i] = tmp;
				}
			}
			catch(DMLException ex)
			{
				LOG.error("Failed partitioning of broadcast variable input.", ex);
			}
		}
		
		@Override
		public Iterable<Tuple2<MatrixIndexes, MatrixBlock>> call( Tuple2<MatrixIndexes, MatrixBlock> arg0 ) 
			throws Exception 
		{
			ArrayList<Tuple2<MatrixIndexes,MatrixBlock>> ret = new ArrayList<Tuple2<MatrixIndexes,MatrixBlock>>();
			MatrixIndexes ixIn = arg0._1();
			MatrixBlock mb2 = arg0._2();
			
			//get the right hand side matrix
			MatrixBlock mb1 = _partBlocks[(int)ixIn.getRowIndex()-1];
			
			//compute target block indexes
			long minPos = UtilFunctions.toLong( mb1.minNonZero() );
			long maxPos = UtilFunctions.toLong( mb1.max() );
			long rowIX1 = (minPos-1)/_brlen+1;
			long rowIX2 = (maxPos-1)/_brlen+1;
			boolean multipleOuts = (rowIX1 != rowIX2);
			
			if( minPos >= 1 ) //at least one row selected
			{
				//output sparsity estimate
				double spmb1 = OptimizerUtils.getSparsity(mb1.getNumRows(), 1, mb1.getNonZeros());
				long estnnz = (long) (spmb1 * mb2.getNonZeros());
				boolean sparse = MatrixBlock.evalSparseFormatInMemory(_brlen, mb2.getNumColumns(), estnnz);
				
				//compute and allocate output blocks
				MatrixBlock out1 = new MatrixBlock();
				MatrixBlock out2 = multipleOuts ? new MatrixBlock() : null;
				out1.reset(_brlen, mb2.getNumColumns(), sparse);
				if( out2 != null )
					out2.reset(UtilFunctions.computeBlockSize(_rlen, rowIX2, _brlen), mb2.getNumColumns(), sparse);
				
				//compute core matrix permutation (assumes that out1 has default blocksize, 
				//hence we do a meta data correction afterwards)
				mb1.permutationMatrixMultOperations(mb2, out1, out2);
				out1.setNumRows(UtilFunctions.computeBlockSize(_rlen, rowIX1, _brlen));
				ret.add(new Tuple2<MatrixIndexes, MatrixBlock>(new MatrixIndexes(rowIX1, ixIn.getColumnIndex()), out1));
				if( out2 != null )
					ret.add(new Tuple2<MatrixIndexes, MatrixBlock>(new MatrixIndexes(rowIX2, ixIn.getColumnIndex()), out2));
			}
			
			return ret;
		}
	}
	
}