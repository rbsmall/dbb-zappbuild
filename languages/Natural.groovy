@groovy.transform.BaseScript com.ibm.dbb.groovy.ScriptLoader baseScript
import com.ibm.dbb.repository.*
import com.ibm.dbb.dependency.*
import com.ibm.dbb.build.*
import groovy.transform.*
import groovy.io.FileType


// define script properties
@Field BuildProperties props = BuildProperties.getInstance()
@Field def buildUtils        = loadScript(new File("${props.zAppBuildDir}/utilities/BuildUtilities.groovy"))
@Field def impactUtils       = loadScript(new File("${props.zAppBuildDir}/utilities/ImpactUtilities.groovy"))
@Field def bindUtils         = loadScript(new File("${props.zAppBuildDir}/utilities/BindUtilities.groovy"))
@Field RepositoryClient repositoryClient

println("** Building files mapped to ${this.class.getName()}.groovy script")
if (props.verbose) println("** Props ${props}")

// verify required build properties
buildUtils.assertBuildProperties(props.natural_requiredBuildProperties)

def langQualifier = "natural"
buildUtils.createLanguageDatasets(langQualifier)

// copy copycode files
copyCopycodeFiles()

// iterate through build list
(argMap.buildList).each { buildFile ->
	println "*** Building file $buildFile"

	// copy build file and dependency files to data sets
//	String rules  = props.getFileProperty('natural_resolutionRules', buildFile)
	buildUtils.copySourceFiles(buildFile, props.natural_srcPDS, null, null)
	String member = CopyToPDS.createMemberName(buildFile)
	File logFile  = new File( props.userBuild ? "${props.buildOutDir}/${member}.log" : "${props.buildOutDir}/${member}.natural.log")
	if (logFile.exists())
		logFile.delete()

	// Create JCLExec String
	String jobcard = props.natural_jobCard.replace("\\n", "\n")
	String jcl = jobcard
	jcl += """\
\n//*
//LOAD     EXEC PGM=NAT23BA,REGION=4M, 
//  PARM=('PARM=${props.natural_jobParms}')        
//CMPRINT  DD  SYSOUT=*
//CMWKF01  DD DISP=SHR,DSN=${props.natural_srcPDS} 
//CMSYNIN  DD *
SYSPROF                         
SYSOBJH                         
${props.natural_loadParms}    
STOP
/*
//*
//BUILD    EXEC PGM=NAT23BA,REGION=4M,COND=(${props.natural_maxRC},LE,LOAD),
//  PARM=('PARM=${props.natural_jobParms}')
//CMPRINT  DD  SYSOUT=*
//CMWKF01  DD DISP=SHR,DSN=${props.natural_srcPDS}
//CMSYNIN  DD *
NEXT LOGON DBAUTILS
${props.natural_buildParms}
XREF ON
CATALL ${member} ALL STOW CC
/*
//*
//FLUSHQ  EXEC PGM=NAT23BA,COND=(${props.natural_maxRC},LE,BUILD),
//  PARM=('PARM=${props.natural_flushParms}')
//CMPRINT  DD  SYSOUT=*
//CMSYNIN  DD *
LOGON DBAUTILS
BPDELET1
XREF ON
/*
//*
//UNLOAD   EXEC PGM=NAT23BA,REGION=4M,COND=(${props.natural_maxRC},LE,FLUSHQ),    
//  PARM=('PARM=${props.natural_unloadParms}')  
//CMPRINT  DD  SYSOUT=*
//CMWKF01  DD DSN=${props.natural_unloadPDS}
//SYSUDUMP DD SYSOUT=*
//CMSYNIN  DD *
LOGON DBAUTILS                               
SYSPROF                                      
SYSOBJH                                      
UNLOAD ${member}  LIB ${props.natural_library} OBJTYPE ${props.natural_objType}     
STOP
/*
"""
			
	if (props.verbose) println(jcl)

	def dbbConf = System.getenv("DBB_CONF")
		
	// Create jclExec
	def naturalBuildJCL = new JCLExec().text(jcl)
	naturalBuildJCL.confDir(dbbConf)
				// Execute jclExec
	naturalBuildJCL.execute()
		/**
		* Store results
		*/
	
	// Save Job Spool to logFile
	naturalBuildJCL.saveOutput(logFile, props.logEncoding)
	
	// Splitting the String into a StringArray using CC as the separator
	jobRcStringArray = naturalBuildJCL.maxRC.split("CC")
	if (props.verbose) println "*** jobRcStringArray - ${jobRcStringArray}"
	
	// This evals the number of items in the ARRAY! Dont get confused with the returnCode itself
	if ( jobRcStringArray.length > 1 ){
		// Ok, the string can be split because it contains the keyword CC : Splitting by CC the second record contains the actual RC
		rc = naturalBuildJCL.maxRC.split("CC")[1].toInteger()
	
		// manage processing the RC, up to your logic. You might want to flag the build as failed.
		if (rc <= props.natural_maxRC.toInteger()){
			println   "***  Natural Build Job ${naturalBuildJCL.submittedJobId} completed with $rc "
			// Store Report in Workspace
		} else {
			props.error = "true"
			String errorMsg = "*! The Natural Build Job failed with RC=($rc) for $buildFile "
			println(errorMsg)
			buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}_natural.log":logFile],client:getRepositoryClient())
		}
	}
	else {
		// We don't see the CC, assume an exception
//		props.error = "true"
		String errorMsg = "*!  Natural Load Job ${naturalBuildJCL.submittedJobId} failed with ${naturalBuildJCL.maxRC}"
		println(errorMsg)
		buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}_natural.log":logFile],client:getRepositoryClient())
	}
}


/**
 * Methods
 */

def getRepositoryClient() {
	if (!repositoryClient && props."dbb.RepositoryClient.url")
		repositoryClient = new RepositoryClient().forceSSLTrusted(true)

	return repositoryClient
}


def splitCCParms(String parms) {
	def outParms = []
	for (int chunk = 0; chunk <= (parms.length().intdiv(72)); chunk++) {
		maxLength = (parms.length() - (chunk * 72))
		if (maxLength > 72)
			maxLength = 72
		outParms.add(parms.substring((chunk * 72), (chunk * 72) + maxLength));
	}
	return outParms
}


def copyCopycodeFiles() {
	def copycodeFolders = props.natural_copycodeFolders
	
	if (props.verbose)
		println "copycodeFolders = ${copycodeFolders}"
		
	ccFolderList = copycodeFolders.split(',').collect{it as String}.each {
		if (props.verbose)
			println "Processing files for ${it}"
			
		def fileList = []
		def dir
		
		if (props.userBuild) 
			dir = new File("${props.application}/${it}")
		else
			dir = new File("${props.workspace}${props.application}/${it}")
			
		dir.eachFileRecurse (FileType.FILES) { file ->       // this is retrieving the full path of all files in the scanned directory
		  fileList << file
	
		  String myPath = file
		  
		  if (props.verbose) 
			  println "myPath is ${myPath}"
			  
		  String[] pathNodes
		  pathNodes     = myPath.split("/")                  // separating the path into the individual folders/files
		  memberName    = pathNodes.last().substring(0,pathNodes.last().indexOf("."))  // getting member name from nodes
		  
		  if (props.verbose)
			  println "*** copying ${memberName} to ${props.natural_incPDS}"
			  
		  new CopyToPDS().file(new File(myPath)).dataset(props.natural_incPDS).member(memberName).copy();		 
		}
	  }
}