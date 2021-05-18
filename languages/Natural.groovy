@groovy.transform.BaseScript com.ibm.dbb.groovy.ScriptLoader baseScript
import com.ibm.dbb.repository.*
import com.ibm.dbb.dependency.*
import com.ibm.dbb.build.*
import groovy.transform.*


// define script properties
@Field BuildProperties props = BuildProperties.getInstance()
@Field def buildUtils= loadScript(new File("${props.zAppBuildDir}/utilities/BuildUtilities.groovy"))
@Field def impactUtils= loadScript(new File("${props.zAppBuildDir}/utilities/ImpactUtilities.groovy"))
@Field def bindUtils= loadScript(new File("${props.zAppBuildDir}/utilities/BindUtilities.groovy"))
@Field RepositoryClient repositoryClient

println("** Building files mapped to ${this.class.getName()}.groovy script")
println("** Props ${props}")

// verify required build properties
buildUtils.assertBuildProperties(props.natural_requiredBuildProperties)

def langQualifier = "natural"
buildUtils.createLanguageDatasets(langQualifier)


// iterate through build list
(argMap.buildList).each { buildFile ->
	println "*** Building file $buildFile"

//	String member = CopyToPDS.createMemberName(buildFile)

//	File logFile = new File("${props.buildOutDir}/${member}.natural.load.log")

//	DependencyResolver dependencyResolver = buildUtils.createDependencyResolver(buildFile, rules)
	
	// copy build file and dependency files to data sets
	String rules = props.getFileProperty('natural_resolutionRules', buildFile)
	DependencyResolver dependencyResolver = buildUtils.createDependencyResolver(buildFile, rules)
	buildUtils.copySourceFiles(buildFile, props.natural_srcPDS, props.natural_incPDS, dependencyResolver)
	LogicalFile logicalFile = dependencyResolver.getLogicalFile()
	String member = CopyToPDS.createMemberName(buildFile)
	File logFile = new File( props.userBuild ? "${props.buildOutDir}/${member}.log" : "${props.buildOutDir}/${member}.natural.log")
	if (logFile.exists())
		logFile.delete()

	
	
	
	
	
	
	
	

	// Create JCLExec String
	String jobcard = props.natural_jobCard.replace("\\n", "\n")
	if (props.verbose) println ("*** Jobcard is ${jobcard}")
	String jcl = jobcard
	jcl += """\
\n//*
//SYSOBJH  EXEC PGM=NAT23BA,REGION=4M, 
//  PARM=('PARM=${props.natural_jobParms}')        
//CMPRINT  DD  SYSOUT=*
//CMWKF01  DD DISP=SHR,DSN=${props.natural_srcPDS} 
//CMSYNIN  DD *
SYSPROF                         
SYSOBJH                         
${props.natural_loadParms}    
STOP
/*
"""
	
	if (props.verbose) println(jcl)

	def dbbConf = System.getenv("DBB_CONF")

	// Create jclExec
	def naturalLoadJCL = new JCLExec().text(jcl)
	naturalLoadJCL.confDir(dbbConf)

	// Execute jclExec
	naturalLoadJCL.execute()

	/**
	 * Store results
	 */
	def rc = 99
	// Save Job Spool to logFile
	naturalLoadJCL.saveOutput(logFile, props.logEncoding)

	// Splitting the String into a StringArray using CC as the separator
	def jobRcStringArray = naturalLoadJCL.maxRC.split("CC")
	println "*** jobRcStringArray - ${jobRcStringArray}"

	// This evals the number of items in the ARRAY! Dont get confused with the returnCode itself
	if ( jobRcStringArray.length > 1 ){
		// Ok, the string can be split because it contains the keyword CC : Splitting by CC the second record contains the actual RC
		rc = naturalLoadJCL.maxRC.split("CC")[1].toInteger()

		// manage processing the RC, up to your logic. You might want to flag the build as failed.
		if (rc <= props.natural_maxRC.toInteger()){
			println   "***  Natural Load Job ${naturalLoadJCL.submittedJobId} completed with $rc "
			// Store Report in Workspace
		} else { 
			props.error = "true"
			String errorMsg = "*! The Natural Load Job failed with RC=($rc) for $buildFile "
			println(errorMsg)
			buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}_natural.log":logFile],client:getRepositoryClient())
		}
	}
	else {
		// We don't see the CC, assume an exception
		props.error = "true"
		String errorMsg = "*!  Natural Load Job ${naturalLoadJCL.submittedJobId} failed with ${naturalLoadJCL.maxRC}"
		println(errorMsg)
		buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}_natural.log":logFile],client:getRepositoryClient())
	}

	if (rc <= props.natural_maxRC.toInteger()) {
		jcl = jobcard
		jcl += """\
\n//*
//SYSOBJH  EXEC PGM=NAT23BA,REGION=4M,
//  PARM=('PARM=${props.natural_jobParms}')
//CMPRINT  DD  SYSOUT=*
//CMWKF01  DD DISP=SHR,DSN=${props.natural_srcPDS}
//CMSYNIN  DD *
NEXT LOGON DBAUTILS
${props.natural_buildParms}
XREF ON
CATALL ${member} ALL STOW CC
/*
"""
		
		if (props.verbose) println(jcl)
	
//			def dbbConf = System.getenv("DBB_CONF")
	
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
			println "*** jobRcStringArray - ${jobRcStringArray}"
	
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
				props.error = "true"
				String errorMsg = "*!  Natural Load Job ${naturalBuildJCL.submittedJobId} failed with ${naturalBuildJCL.maxRC}"
				println(errorMsg)
				buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}_natural.log":logFile],client:getRepositoryClient())
			}
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
