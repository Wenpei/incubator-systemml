/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2014
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.runtime.instructions.MRInstructions;

import java.util.ArrayList;

import com.ibm.bi.dml.runtime.DMLRuntimeException;
import com.ibm.bi.dml.runtime.DMLUnsupportedOperationException;
import com.ibm.bi.dml.runtime.instructions.Instruction;
import com.ibm.bi.dml.runtime.instructions.InstructionUtils;
import com.ibm.bi.dml.runtime.matrix.io.MatrixIndexes;
import com.ibm.bi.dml.runtime.matrix.io.MatrixValue;
import com.ibm.bi.dml.runtime.matrix.mapred.CachedValueMap;
import com.ibm.bi.dml.runtime.matrix.mapred.IndexedMatrixValue;

/**
 * 
 * 
 */
public class ReplicateInstruction extends UnaryMRInstructionBase 
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2014\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	private long _clenM = -1;
	
	public ReplicateInstruction(byte in, byte out, long clenM, String istr)
	{
		super(null, in, out);
		mrtype = MRINSTRUCTION_TYPE.Reorg;
		instString = istr;
		
		_clenM = clenM;
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
		//check instruction format
		InstructionUtils.checkNumFields ( str, 3 );
		
		//parse instruction
		String[] parts = InstructionUtils.getInstructionParts ( str );
		byte in = Byte.parseByte(parts[1]);
		long clen = Long.parseLong(parts[2]);
		byte out = Byte.parseByte(parts[3]);
		
		//construct instruction
		return new ReplicateInstruction(in, out, clen, str);
	}

	/**
	 * 
	 */
	@Override
	public void processInstruction(Class<? extends MatrixValue> valueClass, CachedValueMap cachedValues, 
			IndexedMatrixValue tempValue, IndexedMatrixValue zeroInput, int blockRowFactor, int blockColFactor)
		throws DMLUnsupportedOperationException, DMLRuntimeException 
	{
		ArrayList<IndexedMatrixValue> blkList = cachedValues.get(input);
		
		if( blkList != null ) {
			for(IndexedMatrixValue in : blkList)
			{
				if( in==null ) continue;
				
				//allocate space for the output value
				IndexedMatrixValue out;
				if(input==output)
					out=tempValue;
				else
					out=cachedValues.holdPlace(output, valueClass);
				
				//process instruction
				MatrixIndexes inIx = in.getIndexes();
				MatrixValue inVal = in.getValue();
				if( inIx.getColumnIndex()>1 || inVal.getNumColumns()>1) //pass-through
				{
					//pass through of index/value (unnecessary rep); decision based on
					//if not column vector (MV binary cell operations only support col vectors)
					out.set(inIx, inVal);
				}
				else
				{
					//compute num additional replicates based on num column blocks lhs matrix
					//(e.g., M is Nx2700, blocksize=1000 -> numRep 2 because original block passed to index 1)
					if( blockColFactor<=1 ) //blocksize should be 1000 or similar
						LOG.warn("Block size of input matrix is: brlen="+blockRowFactor+", bclen="+blockColFactor+".");
					int numRep = (int)Math.ceil((double)_clenM / blockColFactor) - 1; 
					
					//replicate block (number of replicates is potentially unbounded, however,
					//because the vector is not modified we can passed the original data and
					//hence the memory overhead is very small)
					for( int i=0; i<numRep; i++ ){
						IndexedMatrixValue repV = cachedValues.holdPlace(output, valueClass);
						MatrixIndexes repIX= repV.getIndexes();
						repV.set(repIX, inVal);
					}
					
					//output original block
					out.set(inIx, inVal);	
				}
				
				//put the output value in the cache
				if(out==tempValue)
					cachedValues.add(output, out);
			}
		}
	}
}