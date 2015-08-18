#-------------------------------------------------------------
# IBM Confidential
# OCO Source Materials
# (C) Copyright IBM Corp. 2010, 2014
# The source code for this program is not published or
# otherwise divested of its trade secrets, irrespective of
# what has been deposited with the U.S. Copyright Office.
#-------------------------------------------------------------

args <- commandArgs(TRUE)
options(digits=22)

library("Matrix")

A = as.matrix(readMM(paste(args[1], "A.mtx", sep="")))

R = A;
s1 = A[7,3];
s2 = A[8,4];
R[1,1] = s1+s2;

writeMM(as(R, "CsparseMatrix"), paste(args[2], "R", sep="")); 

