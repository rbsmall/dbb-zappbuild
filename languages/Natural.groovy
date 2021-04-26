@groovy.transform.BaseScript com.ibm.dbb.groovy.ScriptLoader baseScript
import com.ibm.dbb.repository.*
import com.ibm.dbb.dependency.*
import com.ibm.dbb.build.*
import groovy.transform.*


// define script properties
@Field BuildProperties props = BuildProperties.getInstance()
@Field def buildUtils= loadScript(new File("${props.zAppBuildDir}/utilities/BuildUtilities.groovy"))
@Field def impactUtils= loadScript(new File("${props.zAppBuildDir}/utilities/ImpactUtilities.groovy"))
@Field RepositoryClient repositoryClient

println("** Building files mapped to ${this.class.getName()}.groovy script")

// verify required build properties
buildUtils.assertBuildProperties(props.jcl_requiredBuildProperties)

def langQualifier = "natural"
buildUtils.createLanguageDatasets(langQualifier)

// sort the build list based on build file rank if provided
List<String> sortedList = buildUtils.sortBuildList(argMap.buildList, 'natural_fileBuildRank')

// iterate through build list
sortedList.each { buildFile ->
	println "*** Building file $buildFile"
	
	// copy build file and dependency files to data sets
	String rules = props.getFileProperty('natural_resolutionRules', buildFile)
	String jcl_srcPDS = props.getFileProperty('natural_srcPDS', buildFile)
	DependencyResolver dependencyResolver = buildUtils.createDependencyResolver(buildFile, rules)
	buildUtils.copySourceFiles(buildFile, natural_srcPDS, props.natural_incPDS, dependencyResolver)	
	
	// create mvs commands
	LogicalFile logicalFile = dependencyResolver.getLogicalFile()
	String member = CopyToPDS.createMemberName(buildFile)
	
	new CopyToPDS().file(new File("${props.workspace}/${buildFile}")).dataset(jcl_srcPDS).member(member).output(true).deployType("JCL").execute()
	
	File logFile = new File( props.userBuild ? "${props.application}/${member}.log" : "${props.buildOutDir}/${member}.jcl.log")
	if (logFile.exists())
		logFile.delete()
		
	logFile.append("JCL member ${buildFile} was successfully copied to ${jcl_srcPDS}(${member})")
	
}

// end script

//********************************************************************
 //* Method definitions
 //********************************************************************

def getRepositoryClient() {
	if (!repositoryClient && props."dbb.RepositoryClient.url")
		repositoryClient = new RepositoryClient().forceSSLTrusted(true)
	
	return repositoryClient
}

