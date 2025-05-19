#!groovy
def BN = (BRANCH_NAME == 'master' || BRANCH_NAME.startsWith('releases/')) ? BRANCH_NAME : 'releases/2025-07'

library "knime-pipeline@$BN"

properties([
    parameters(workflowTests.getConfigurationsAsParameters()),
    buildDiscarder(logRotator(numToKeepStr: '5')),
    disableConcurrentBuilds()
])

try {
    knimetools.defaultTychoBuild('org.knime.update.conda')
    testInstallCondaEnvAction(BN)

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
            stage("Install Conda Environment Action Tests on ${nodeLabel}") {
                knimetools.materializeResources(['common.inc'])

                def String compositeRepo = "https://jenkins.devops.knime.com/p2/knime/composites/${baseBranch}"
                def String condaRepo = "https://jenkins.devops.knime.com/p2/knime/knime-conda/${branch}"

                try {
                    sh label: 'Create minimal installation', script: """
                        source common.inc
                        installIU org.knime.minimal.product \"${compositeRepo}\" knime_minimal.app \"\" \"\" \"\" 2
                    """

                    // Test basic installation
                    runCondaEnvBundlingTest(
                        (nodeLabel == "macosx" ? "knime test.app/Contents/Eclipse/bundling/" : "knime test.app/bundling"),
                        "",
                        condaRepo,
                        compositeRepo
                    )

                    // Create a temporary directory for KNIME_PYTHON_BUNDLING_PATH
                    def bundlingPath = "${env.WORKSPACE}/bundling_${nodeLabel}_${UUID.randomUUID().toString()}"
                    sh "mkdir -p \"${bundlingPath}\""

                    runCondaEnvBundlingTest(
                        bundlingPath,
                        "withEnv",
                        condaRepo,
                        compositeRepo
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

def runCondaEnvBundlingTest(bundlingPath, envType, condaRepo, compositeRepo) {
    sh label: 'Copy minimal installation', script: """
        cp -a knime_minimal.app "knime test.app"
    """
    def envDir = "${bundlingPath}/org_knime_conda_envbundling_testext_0.1.0/.pixi/envs/default"

    def installTest = {
        sh label: 'Install test extension', script: """
            source common.inc
            installIU org.knime.features.conda.envbundling.testext.feature.group \"${condaRepo},${compositeRepo}\" \"knime test.app\" \"\" \"\" \"\" 2
        """
        if (!fileExists(envDir)) {
            error("Environment directory does not exist: ${envDir}")
        }
    }

    def uninstallTest = { boolean installSuccess ->
        if (!installSuccess) {
            error("Install step failed, uninstall not attempted")
        }
        sh label: 'Uninstall test extension', script: """
            source common.inc
            uninstallIU org.knime.features.conda.envbundling.testext.feature.group \"knime test.app\"
        """
        if (fileExists(envDir)) {
            error("Environment directory still exist after feature uninstallation: ${envDir}")
        }
    }

    try {
        if (envType == "withEnv") {
            withEnv(["KNIME_PYTHON_BUNDLING_PATH=${bundlingPath}"]) {
                boolean installSuccess = runTest("should install environment into custom path", installTest)
                runTest("should uninstall environment from custom path") { uninstallTest(installSuccess) }
            }
        } else {
            boolean installSuccess = runTest("should install environment", installTest)
            runTest("should uninstall environment") { uninstallTest(installSuccess) }
        }
    } finally {
        // Clean up
        sh label: 'Delete knime test.app', script: """
            rm -rf "knime test.app"
        """
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
    reportTestResult("CondaEnvBundling", testName, success, failureMessage)
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
