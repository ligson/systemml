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

package com.ibm.bi.dml.test.integration.functions.binary.scalar;

import org.junit.Test;

import com.ibm.bi.dml.test.integration.AutomatedTestBase;
import com.ibm.bi.dml.test.integration.TestConfiguration;
import com.ibm.bi.dml.test.utils.TestUtils;



public class LessThanTest extends AutomatedTestBase 
{
	
	private	final static String TEST_DIR = "functions/binary/scalar/";
	
	@Override
	public void setUp() 
	{
		TestUtils.clearAssertionInformation();
		addTestConfiguration("LessThanTest", new TestConfiguration(TEST_DIR, "LessThanTest",
				new String[] { "left_1", "left_2", "left_3", "right_1", "right_2", "right_3" }));
	}
	
	@Test
	public void testLessThan() {
		TestConfiguration config = getTestConfiguration("LessThanTest");
		loadTestConfiguration(config);
		
		createHelperMatrix();
		writeExpectedHelperMatrix("left_1", 2);
		writeExpectedHelperMatrix("left_2", 1);
		writeExpectedHelperMatrix("left_3", 1);
		writeExpectedHelperMatrix("right_1", 1);
		writeExpectedHelperMatrix("right_2", 1);
		writeExpectedHelperMatrix("right_3", 2);
		
		runTest();
		
		compareResults();
	}

}
