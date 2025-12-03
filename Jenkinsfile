#!groovy
def BN = (BRANCH_NAME == 'master' || BRANCH_NAME.startsWith('releases/')) ? BRANCH_NAME : 'releases/2026-06'

library "knime-pipeline@$BN"

properties([
    parameters(workflowTests.getConfigurationsAsParameters()),
    buildDiscarder(logRotator(numToKeepStr: '5')),
    disableConcurrentBuilds()
])

try {
    knimetools.defaultTychoBuild('org.knime.update.conda')
    
    // Run Conda Environment Installation Tests
    testInstallCondaEnvAction(BRANCH_NAME.startsWith('releases/') ? BRANCH_NAME : 'master')

    stage('Sonarqube analysis') {
        env.lastStage = env.STAGE_NAME
        // Passing an empty test configuration as we do not have any workflow tests
        // TODO remove empty test configuration when adding workflow tests
        workflowTests.runSonar([])
    }
} catch (ex) {
    currentBuild.result = 'FAILURE'
    throw ex
} finally {
    notifications.notifyBuild(currentBuild.result);
}

def testInstallCondaEnvAction(String baseBranch) {
    def branch = env.CHANGE_BRANCH ?: env.BRANCH_NAME

    def testBody = { nodeLabel ->
        node("workflow-tests && ${nodeLabel}") {
            stage("Install Conda Environment Tests on ${nodeLabel}") {
                knimetools.materializeResources(['common.inc'])

                def String compositeRepo = "https://jenkins.devops.knime.com/p2/knime/composites/${baseBranch}"
                def String condaRepo = "https://jenkins.devops.knime.com/p2/knime/knime-conda/${branch}"

                try {
                    sh label: 'Create minimal installation', script: """
                        source common.inc
                        installIU org.knime.minimal.product \"${compositeRepo}\" knime_minimal.app \"\" \"\" \"\" 2
                    """

                    // Test basic installation
                    runCondaEnvInstallationTest(
                        (nodeLabel == "macosx" ? "knime test.app/Contents/Eclipse/bundling/" : "knime test.app/bundling"),
                        "",
                        condaRepo,
                        compositeRepo,
                        nodeLabel
                    )

                    // Create a temporary directory for KNIME_PYTHON_BUNDLING_PATH
                    def bundlingPath = "${env.WORKSPACE}/bundling_${nodeLabel}_${UUID.randomUUID().toString()}"
                    sh "mkdir -p \"${bundlingPath}\""

                    runCondaEnvInstallationTest(
                        bundlingPath,
                        "withEnv",
                        condaRepo,
                        compositeRepo,
                        nodeLabel
                    )
                } finally {
                    // Clean up
                    sh label: 'Delete knime_minimal.app', script: """
                        rm -rf "knime_minimal.app"
                    """
                }
            }
        }
    }

    parallel(
        'ubuntu22.04': { testBody('ubuntu22.04') },
        'windows': { testBody('windows') },
        'macosx': { testBody('macosx') },
    )
}

def runCondaEnvInstallationTest(bundlingPath, envType, condaRepo, compositeRepo, nodeLabel) {
    sh label: 'Copy minimal installation for startup test', script: """
        cp -a knime_minimal.app "knime startup.app"
    """
    
    // For startup tests, if we're using custom bundling path, use that. Otherwise use the startup app's bundling directory
    def actualBundlingPath = (envType == "withEnv") ? bundlingPath : (nodeLabel == "macosx" ? "knime startup.app/Contents/Eclipse/bundling" : "knime startup.app/bundling")
    def envDir = "${actualBundlingPath}/org_knime_conda_envinstall_testext/.pixi/envs/default"
    def pixiCacheDir = actualBundlingPath + "/.pixi-cache"

    // Configure knime.ini to install environments on startup
    def knimeIniPath = (nodeLabel == "macosx") ? 
        "knime startup.app/Contents/Eclipse/knime.ini" : 
        "knime startup.app/knime.ini"

    def installTest = {
        sh label: 'Install test extension', script: """
            source common.inc
            installIU org.knime.desktop.product \"${compositeRepo}\" \"knime startup.app\" \"\" \"\" \"\" 2
            installIU org.knime.features.conda.envinstall.testext.feature.group \"${condaRepo},${compositeRepo}\" \"knime startup.app\" \"\" \"\" \"\" 2
        """
        
        // Start KNIME to trigger environment installation on startup
        def knimeExecutable = (nodeLabel == "macosx") ? 
            "knime startup.app/Contents/MacOS/knime" : 
            "knime startup.app/knime"
        
        // Set KNIME_PYTHON_BUNDLING_PATH for the KNIME process if using custom path
        def envVarPrefix = (envType == "withEnv") ? "KNIME_PYTHON_BUNDLING_PATH=\"${bundlingPath}\" " : ""
        
        sh label: 'Run KNIME warmstart to install environment', script: """
            source common.inc
            ${envVarPrefix} runWithWatchdog 300 "${knimeExecutable}" -nosplash -consoleLog -data temp_workspace -application org.knime.product.KNIME_WARMSTART_APPLICATION 2>&1 | tee warmstart.log
        """
        
        // Archive artifacts for debugging
        archiveArtifacts artifacts: 'warmstart.log', allowEmptyArchive: true

        // 1. Environment directory must exist
        if (!fileExists(envDir)) {
            error("Environment directory does not exist after startup: ${envDir}")
        }

        // 2. Environment must contain tzdata package
        sh label: 'Verify environment contains tzdata', script: """
            micromamba list -p \"${envDir}\" | grep 'tzdata' || {
                echo "✖ tzdata package not found in environment ${envDir}"
                exit 1
            }
        """
    }

    def uninstallTest = { boolean installSuccess ->
        if (!installSuccess) {
            error("Install step failed, uninstall not attempted")
        }
        sh label: 'Uninstall test extension (startup-created env)', script: """
            source common.inc
            uninstallIU org.knime.features.conda.envinstall.testext.feature.group \"knime startup.app\"
        """
        if (fileExists(envDir)) {
            error("Environment directory still exist after feature uninstallation: ${envDir}")
        }
    }

    try {
        if (envType == "withEnv") {
            withEnv(["KNIME_PYTHON_BUNDLING_PATH=${bundlingPath}"]) {
                boolean installSuccess = runTest("should install environment on startup into custom path", installTest)
                runTest("should uninstall startup-created environment from custom path") { uninstallTest(installSuccess) }
            }
        } else {
            boolean installSuccess = runTest("should install environment on startup", installTest)
            runTest("should uninstall startup-created environment") { uninstallTest(installSuccess) }
        }
    } finally {
        // Clean up
        sh label: 'Delete knime startup.app', script: """
            rm -rf "knime startup.app"
        """
        if (envType == "withEnv") {
            sh label: 'Delete external bundling directory (startup test)', script: """
                rm -rf "${bundlingPath}"
            """
        }
    }
}

def runTest(String testName, Closure testLambda) {
    boolean success = true
    String failureMessage = ""
    try {
        testLambda()
    } catch (ex) {
        success = false
        failureMessage = ex.getMessage()
    }
    reportTestResult("CondaEnvInstallation", testName, success, failureMessage)
    return success
}

def reportTestResult(String suite, String name, boolean success, String failureMessage) {
    def xml = """
        <testsuite name=\"${suite}\" tests=\"1\" failures=\"${success ? 0 : 1}\">
          <testcase classname=\"${suite}\" name=\"${name}\">${success ? '' : "<failure message=\"${failureMessage.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')}\"/>"}</testcase>
        </testsuite>
    """.stripIndent()
    writeFile file: "${suite}-${name}-result.xml", text: xml
    junit "${suite}-${name}-result.xml"
}

/* vim: set shiftwidth=4 expandtab smarttab: */
