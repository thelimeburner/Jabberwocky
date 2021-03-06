import os
import subprocess
import db_functions as dbq
import sys
filename = sys.argv[1]

f = open("test.java","r")

oparen = []
obracket = []
cparen = []
cbracket = []
error_weight = 2 



#The logic in this function is wrong.
#It will not included both mismatched values in both stacks
# It will just return the longest value.
def getTotalPairs(list1,list2):
	if len(list1) == len(list2):
		return len(list1)
	elif len(list1) > len(list2):
		return len(list1)
	elif len(list2) > len(list1):
		return len(list2)



def checkFile(file,oparen,obracket,cparen,cbracket,data):
	global error_weight
	print("Checking Files\n")
	#Adds characters to their stacks
	for line in f:
		print line
		for i in range(len(line)):
			if(line[i] == '{'):
				obracket.append('{')
			elif(line[i] == '('):
				oparen.append('(')
			elif(line[i] == '}'):
				cbracket.append('}')
			elif(line[i] == ')'):
				cparen.append(')')
	#totalBPairs = getTotalPairs(obracket,cbracket)
	#totalPPairs = getTotalPairs(oparen,cparen)
	bSP = 0	 # Bracket Successful 
	bFP = 0 # Bracket Failed Parings, aka the num of brackets left at end
	bracket_score = 0	#End score for brackets, will be removed
	for x in range(len(obracket)):
		if (len(obracket) == 0 or len(cbracket) == 0):
			#FIX THIS VALUE
			bracket_score = bracket_score + error_weight
			break
		obracket.pop()
		cbracket.pop()
		bSP = bSP + 1
	print "The number of left over open brackets is ",len(obracket)
	print "The number of left over closed brackets is ",len(cbracket)
	bFP = len(obracket) + len(cbracket)
	#print "Final Error Score is "+str(bracket_score)
	#print "Final Success Score is "+str(syntaxB)
	pSP = 0 # Parens Successful Pairings
	pFP = 0 # Parens failed Pairings, aka the number of parens left at end
	paren_score = 0
	for x in range(len(oparen)):
		if (len(oparen) == 0 or len(cparen) == 0):
			# Fix this value
			paren_score = paren_score + error_weight
			break
		oparen.pop()
		cparen.pop()
		pSP = pSP + 1
	pFP = len(cparen)+len(oparen) #Get failed pairings
	#print "Final Error score is "+str(paren_score)
	#print "Final Success score is "+str(syntaxP)
	totalBPairs = bFP + bSP
	totalPPairs = pFP + pSP
	print "The total number of paren pairs is: ",totalPPairs
	print "The total number of bracket pairs is: ",totalBPairs
	pSR = float(pSP)/totalPPairs # Calculate the success rate by doing number of pairs/total num pairs
	bSR = float(bSP)/totalBPairs
	print "Calculating Brackets:"
	print "bSR = "+str(float(bSP))+"/"+str(totalBPairs)
	print "Calculating Parens:"
	print "pSR = "+str(float(pSP))+"/"+str(totalPPairs)
	print "Paren success rate: ",pSR
	print "Bracket success rate: ",bSR
	#syntaxF = bracket_score+paren_score
	#syntaxP = bSR+pSR
	return (pSR,bSR)


def parseErrorMessage(error):
	#errorStr = error.rsplit(None,1)[-1]
	errorStr = error.split()[len(error.split())-2]
	return int(errorStr)

def checkCompile(filename,data):
	global error_weight
	cmd = "javac "+filename
	compileScore = 0
	process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=True)
	output, error = process.communicate()
	if not output and not error:
		print "Success: Compile was Successful."
	if error:
		print "Failure : Compile was unsuccessful."
		#print "Error was :",error
		errorVal = parseErrorMessage(error)
		compileScore = compileScore + errorVal
	return compileScore

#This function takes in three arguments, returns aEPC
# cEPC => the current error per compile
# data ==> Has all values for student
def updateEPC(cEPC, data):
	data["compileTE"] = data["compileTE"]+cEPC
	data["compileT"]= data["compileT"]+1
	data["EPC"] = float(data["compileTE"])/(data["compileT"]) 
	return data




data = dbq.getCurrentLDValues()

# score = (pSR,bSR) 
score = checkFile(f,oparen,obracket,cparen,cbracket,data)
f.close()

# cEPC = current # of compile errors
cEPC = checkCompile(filename,data)

#Create Weighted average
syntaxScore = (score[0]*.25) +(score[1]*.25)

#print "Syntax score is ",score
#print "Compile score is ",score2

#print "paren weighted score is ",score[0]*.5
#print "bracket weighted score is ",score[1]*.5
#print "Weighted score: ",syntaxScore


print "data before update EPC is ",data
data = updateEPC(cEPC,data)
print "data after update EPC is ",data
dbq.updateLDScore(data)

#print "final score is ",total_score
#paren.pop
#paren.append("{")
#p = line.count('{')
#print "There are "+str(p)+"open brackets"