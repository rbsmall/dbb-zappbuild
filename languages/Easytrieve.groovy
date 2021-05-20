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

//verify required build properties
buildUtils.assertBuildProperties(props.easytrieve_requiredBuildProperties)

// create language datasets
def langQualifier = "easytrieve"
buildUtils.createLanguageDatasets(langQualifier)


//sort the build list based on build file rank if provided
List<String> sortedList = buildUtils.sortBuildList(argMap.buildList, 'easytrieve_fileBuildRank')

//iterate through build list
sortedList.each { buildFile ->
	println "*** Building file $buildFile"
	
	// copy build file and dependency files to data sets
	String rules = props.getFileProperty('easytrieve_resolutionRules', buildFile)
	DependencyResolver dependencyResolver = buildUtils.createDependencyResolver(buildFile, rules)
		buildUtils.copySourceFiles(buildFile, props.easytrieve_srcPDS, props.easytrieve_cpyPDS, dependencyResolver)

	
	// create mvs commands
	LogicalFile logicalFile = dependencyResolver.getLogicalFile()
	String member = CopyToPDS.createMemberName(buildFile)
	File logFile = new File("${props.buildOutDir}/${member}.log")
	MVSExec compile = createCompileCommand(buildFile, logicalFile, member, logFile)
	MVSExec linkEdit = createLinkEditCommand(buildFile, logicalFile, member, logFile)
	
	// execute mvs commands in a mvs job
	MVSJob job = new MVSJob()
	job.start()
	
	// compile the easytrieve program
	int rc = compile.execute()
	//int rc = 0
	int maxRC = props.getFileProperty('easytrieve_compileMaxRC', buildFile).toInteger()
	
	if (rc > maxRC) {
		String errorMsg = "*! The compile return code ($rc) for $buildFile exceeded the maximum return code allowed ($maxRC)"
		println(errorMsg)
		props.error = "true"
		buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}.log":logFile],client:getRepositoryClient())
	}
	else {
	// if this program needs to be link edited . . .
		String needsLinking = props.getFileProperty('easytrieve_linkEdit', buildFile)
		if (needsLinking.toBoolean()) {
			rc = linkEdit.execute()
			//rc = 0
			maxRC = props.getFileProperty('easytrieve_linkEditMaxRC', buildFile).toInteger()
		
			if (rc > maxRC) {
				String errorMsg = "*! The link edit return code ($rc) for $buildFile exceeded the maximum return code allowed ($maxRC)"
				println(errorMsg)
				props.error = "true"
				buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}.log":logFile],client:getRepositoryClient())
			}
			else {
				// only scan the load module if load module scanning turned on for file
				String scanLoadModule = props.getFileProperty('easytrieve_scanLoadModule', buildFile)
				if (scanLoadModule && scanLoadModule.toBoolean() && getRepositoryClient())
					impactUtils.saveStaticLinkDependencies(buildFile, props.linkedit_loadPDS, logicalFile, repositoryClient)
			}
		}
			
	}
	
	
	// clean up passed DD statements
	job.stop()
}

// end script


//********************************************************************
//* Method definitions
//********************************************************************

/*
 * createEasytrieveParms - Builds up the COBOL compiler parameter list from build and file properties
 */
def createEasytrieveParms(String buildFile, LogicalFile logicalFile) {
	def parms = props.getFileProperty('easytrieve_compileParms', buildFile) ?: ""
	def cics = props.getFileProperty('easytrieve_compileCICSParms', buildFile) ?: ""
	def sql = props.getFileProperty('easytrieve_compileSQLParms', buildFile) ?: ""
	def errPrefix = props.getFileProperty('easytrieve_compileErrorPrefixParms', buildFile) ?: ""
	
	
	if (buildUtils.isCICS(logicalFile))
		parms = "$parms,$cics"
		
	if (buildUtils.isSQL(logicalFile))
		parms = "$parms,$sql"
	
	String isMQ = props.getFileProperty('easytrieve_isMQ', buildFile)
	if (isMQ && isMQ.toBoolean())
		compile.dd(new DDStatement().dsn(props.SCSQCOBC).options("shr"))

	if (errPrefix)
		parameters = "$parms,errPrefix"
		
	if (parms.startsWith(','))
		parms = parms.drop(1)
		
	if (props.verbose) println "Easytrieve compiler parms for $buildFile = $parms"
	return parms
}

/*
 * createCompileCommand - creates a MVSExec command for compiling the COBOL program (buildFile)
 */
def createCompileCommand(String buildFile, LogicalFile logicalFile, String member, File logFile) {
	String parms = createEasytrieveParms(buildFile, logicalFile)
	String compiler = props.getFileProperty('easytrieve_compiler', buildFile)
	
	// define the MVSExec command to compile the program
	MVSExec compile = new MVSExec().file(buildFile).pgm(compiler).parm(parms)
	
	// add DD statements to the compile command
	compile.dd(new DDStatement().name("SYSIN").dsn("${props.easytrieve_srcPDS}($member)").options('shr').report(true))
	compile.dd(new DDStatement().name("SYSPRINT").options(props.easytrieve_printTempOptions))
	//compile.dd(new DDStatement().name("SYSMDECK").options(props.easytrieve_tempOptions))
	(1..2).toList().each { num ->
		compile.dd(new DDStatement().name("SYSUT$num").options(props.easytrieve_tempOptions))
	}
	
	// Write SYSLIN to temporary dataset if performing link edit
	//String doLinkEdit = props.getFileProperty('easytrieve_linkEdit', buildFile)
	//if (doLinkEdit && doLinkEdit.toBoolean())
		compile.dd(new DDStatement().name("SYSLIN").dsn("&&TEMPOBJ").options(props.easytrieve_tempOptions).pass(true))
	//else
	//	compile.dd(new DDStatement().name("SYSLIN").dsn("${props.easytrieve_objPDS}($member)").options('shr').output(true))
		
	// add a syslib to the compile command with optional bms output copybook and CICS concatenation
	compile.dd(new DDStatement().name("SYSLIB").dsn(props.easytrieve_cpyPDS).options("shr"))
	//shekar start change added newly
	// add custom concatenation
	def easytrieveSyslibConcatenation = props.getFileProperty('easytrieve_easytrieveSyslibConcatenation', buildFile) ?: ""
	if (easytrieveSyslibConcatenation) {
		def String[] syslibDatasets = easytrieveSyslibConcatenation.split(',');
		for (String syslibDataset : syslibDatasets )
		compile.dd(new DDStatement().dsn(syslibDataset).options("shr"))
	}

	//shekar end change
	//if (props.bms_cpyPDS)
	//	compile.dd(new DDStatement().dsn(props.bms_cpyPDS).options("shr"))
	//if(props.team)
	//	compile.dd(new DDStatement().dsn(props.easytrieve_BMS_PDS).options("shr"))
	//if (buildUtils.isCICS(logicalFile))
	//	compile.dd(new DDStatement().dsn(props.SDFHCOB).options("shr"))

	// add a tasklib to the compile command with optional CICS, DB2, and IDz concatenations
	String compilerVer = props.getFileProperty('easytrieve_compilerVersion', buildFile)
	compile.dd(new DDStatement().name("TASKLIB").dsn(props.EZTCOMP_VX).options("shr"))
	//if (buildUtils.isCICS(logicalFile))
	//	compile.dd(new DDStatement().dsn(props.SDFHLOAD).options("shr"))
	//if (buildUtils.isSQL(logicalFile))
	//	compile.dd(new DDStatement().dsn(props.SDSNLOAD).options("shr"))
	//if (props.SFELLOAD)
	//	compile.dd(new DDStatement().dsn(props.SFELLOAD).options("shr"))
		
	// add optional DBRMLIB if build file contains DB2 code
	//if (buildUtils.isSQL(logicalFile))
	//	compile.dd(new DDStatement().name("DBRMLIB").dsn("$props.easytrieve_dbrmPDS($member)").options('shr').output(true).deployType('DBRM'))

	// add IDz User Build Error Feedback DDs
	//if (props.errPrefix) {
	//	compile.dd(new DDStatement().name("SYSADATA").options("DUMMY"))
	//	compile.dd(new DDStatement().name("SYSXMLSD").dsn("${props.hlq}.${props.errPrefix}.SYSXMLSD.XML").options('mod keep'))
	//}
		
	// add a copy command to the compile command to copy the SYSPRINT from the temporary dataset to an HFS log file
	compile.copy(new CopyToHFS().ddName("SYSPRINT").file(logFile).hfsEncoding(props.logEncoding))
	
	return compile
}


/*
 * createLinkEditCommand - creates a MVSExec xommand for link editing the COBOL object module produced by the compile
 */
def createLinkEditCommand(String buildFile, LogicalFile logicalFile, String member, File logFile) {
	String parms = props.getFileProperty('easytrieve_linkEditParms', buildFile)
	String linker = props.getFileProperty('easytrieve_linkEditor', buildFile)
	
	// define the MVSExec command to link edit the program
	MVSExec linkedit = new MVSExec().file(buildFile).pgm(linker).parm(parms)
	
	// add DD statements to the linkedit command
	linkedit.dd(new DDStatement().name("SYSLMOD").dsn("${props.easytrieve_loadPDS}($member)").options('shr').output(true).deployType('LOAD'))
	linkedit.dd(new DDStatement().name("SYSPRINT").options(props.easytrieve_printTempOptions))
	linkedit.dd(new DDStatement().name("SYSUT1").options(props.easytrieve_tempOptions))
	
	// add a syslib to the compile command with optional CICS concatenation
	linkedit.dd(new DDStatement().name("SYSLIB").dsn(props.easytrieve_objPDS).options("shr"))
	// shekar start change
	// add custom concatenation
	def linkEditSyslibConcatenation = props.getFileProperty('easytrieve_linkEditSyslibConcatenation', buildFile) ?: ""
	if (linkEditSyslibConcatenation) {
		def String[] syslibDatasets = linkEditSyslibConcatenation.split(',');
		for (String syslibDataset : syslibDatasets )
		linkedit.dd(new DDStatement().dsn(syslibDataset).options("shr"))
	}
	// shekar end change

	linkedit.dd(new DDStatement().dsn(props.SCEELKED).options("shr"))
	//if (buildUtils.isCICS(logicalFile))
	//	linkedit.dd(new DDStatement().dsn(props.SDFHLOAD).options("shr"))
		
	//String isMQ = props.getFileProperty('easytrieve_isMQ', buildFile)
	//if (isMQ && isMQ.toBoolean())
	//	linkedit.dd(new DDStatement().dsn(props.SCSQLOAD).options("shr"))

	// add a copy command to the linkedit command to append the SYSPRINT from the temporary dataset to the HFS log file
	linkedit.copy(new CopyToHFS().ddName("SYSPRINT").file(logFile).hfsEncoding(props.logEncoding).append(true))
	
	return linkedit
}


def getRepositoryClient() {
	if (!repositoryClient && props."dbb.RepositoryClient.url")
		repositoryClient = new RepositoryClient().forceSSLTrusted(true)
	
	return repositoryClient
}

