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

package com.ibm.bi.dml.test.integration.applications.descriptivestats;

import java.util.HashMap;

import org.junit.Test;

import com.ibm.bi.dml.runtime.matrix.data.MatrixValue.CellIndex;
import com.ibm.bi.dml.test.integration.AutomatedTestBase;
import com.ibm.bi.dml.test.integration.TestConfiguration;
import com.ibm.bi.dml.test.utils.TestUtils;


public class BivariateOrdinalOrdinalTest extends AutomatedTestBase 
{

	
	private final static String TEST_DIR = "applications/descriptivestats/";
	private final static String TEST_ORDINAL_ORDINAL = "OrdinalOrdinal";
	private final static String TEST_ORDINAL_ORDINAL_WEIGHTS = "OrdinalOrdinalWithWeightsTest";

	private final static double eps = 1e-9;
	private final static int rows = 10000;
	private final static int ncatA = 100; // # of categories in A
	private final static int ncatB = 75; // # of categories in B
	private int maxW = 100;    // maximum weight
	
	@Override
	public void setUp() {
		addTestConfiguration(TEST_ORDINAL_ORDINAL, 
				new TestConfiguration(TEST_DIR, TEST_ORDINAL_ORDINAL, 
					new String[] { "Spearman"+".scalar" }));
		addTestConfiguration(TEST_ORDINAL_ORDINAL_WEIGHTS, 
				new TestConfiguration(TEST_DIR, TEST_ORDINAL_ORDINAL_WEIGHTS, 
					new String[] { "Spearman"+".scalar" }));
	}
	
	@Test
	public void testOrdinalOrdinal() {
		TestConfiguration config = getTestConfiguration(TEST_ORDINAL_ORDINAL);
		
		config.addVariable("rows", rows);
		
		/* This is for running the junit test the new way, i.e., construct the arguments directly */
		String OO_HOME = SCRIPT_DIR + TEST_DIR;	
		fullDMLScriptName = OO_HOME + TEST_ORDINAL_ORDINAL + ".dml";
		programArgs = new String[]{"-args", OO_HOME + INPUT_DIR + "A", 
	                        Integer.toString(rows),
	                        OO_HOME + INPUT_DIR + "B", 
	                        OO_HOME + OUTPUT_DIR + "Spearman"};
		fullRScriptName = OO_HOME + TEST_ORDINAL_ORDINAL + ".R";
		rCmd = "Rscript" + " " + OO_HOME + TEST_ORDINAL_ORDINAL + ".R" + " " + 
		       OO_HOME + INPUT_DIR + " " + OO_HOME + EXPECTED_DIR;

		loadTestConfiguration(config);

        double[][] A = getRandomMatrix(rows, 1, 1, ncatA, 1, System.currentTimeMillis());
        double[][] B = getRandomMatrix(rows, 1, 1, ncatB, 1, System.currentTimeMillis()+1);
        round(A);
        round(B);
        
		writeInputMatrix("A", A, true);
		writeInputMatrix("B", B, true);

		runTest(true, false, null, -1);
		runRScript(true);
		
		for(String file: config.getOutputFiles())
		{
			/* NOte that some files do not contain matrix, but just a single scalar value inside */
			HashMap<CellIndex, Double> dmlfile;
			HashMap<CellIndex, Double> rfile;
			if (file.endsWith(".scalar")) {
				file = file.replace(".scalar", "");
				dmlfile = readDMLScalarFromHDFS(file);
				rfile = readRScalarFromFS(file);
			}
			else {
				dmlfile = readDMLMatrixFromHDFS(file);
				rfile = readRMatrixFromFS(file);
			}
			TestUtils.compareMatrices(dmlfile, rfile, eps, file+"-DML", file+"-R");
		}
	}
	
	private void round(double[][] weight) {
		for(int i=0; i<weight.length; i++)
			weight[i][0]=Math.floor(weight[i][0]);
	}

	@Test
	public void testOrdinalOrdinalWithWeights() {
		TestConfiguration config = getTestConfiguration(TEST_ORDINAL_ORDINAL_WEIGHTS);
		
		config.addVariable("rows", rows);

		/* This is for running the junit test the new way, i.e., construct the arguments directly */
		String OO_HOME = SCRIPT_DIR + TEST_DIR;	
		fullDMLScriptName = OO_HOME + TEST_ORDINAL_ORDINAL_WEIGHTS + ".dml";
		programArgs = new String[]{"-args", OO_HOME + INPUT_DIR + "A", 
	                        Integer.toString(rows),
	                        OO_HOME + INPUT_DIR + "B", 
	                        OO_HOME + INPUT_DIR + "WM", 
	                        OO_HOME + OUTPUT_DIR + "Spearman"};

		fullRScriptName = OO_HOME + TEST_ORDINAL_ORDINAL_WEIGHTS + ".R";
		rCmd = "Rscript" + " " + OO_HOME + TEST_ORDINAL_ORDINAL_WEIGHTS + ".R" + " " + 
		       OO_HOME + INPUT_DIR + " " + OO_HOME + EXPECTED_DIR;

		loadTestConfiguration(config);

        double[][] A = getRandomMatrix(rows, 1, 1, ncatA, 1, System.currentTimeMillis());
        double[][] B = getRandomMatrix(rows, 1, 1, ncatB, 1, System.currentTimeMillis());
        double[][] WM = getRandomMatrix(rows, 1, 1, maxW, 1, System.currentTimeMillis());
        round(A);
        round(B);
        round(WM);
        
		writeInputMatrix("A", A, true);
		writeInputMatrix("B", B, true);
		writeInputMatrix("WM", WM, true);
		
		runTest(true, false, null, -1);
		runRScript(true);
		
		for(String file: config.getOutputFiles())
		{
			/* NOte that some files do not contain matrix, but just a single scalar value inside */
			HashMap<CellIndex, Double> dmlfile;
			HashMap<CellIndex, Double> rfile;
			if (file.endsWith(".scalar")) {
				file = file.replace(".scalar", "");
				dmlfile = readDMLScalarFromHDFS(file);
				rfile = readRScalarFromFS(file);
			}
			else {
				dmlfile = readDMLMatrixFromHDFS(file);
				rfile = readRMatrixFromFS(file);
			}
			TestUtils.compareMatrices(dmlfile, rfile, eps, file+"-DML", file+"-R");
		}
	}
	

}
