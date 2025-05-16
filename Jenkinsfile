#!groovy
def BN = (BRANCH_NAME == 'master' || BRANCH_NAME.startsWith('releases/')) ? BRANCH_NAME : 'releases/2025-07'

library "knime-pipeline@$BN"

properties([
    // provide a list of upstream jobs which should trigger a rebuild of this job
    pipelineTriggers([
        upstream("knime-python/${BRANCH_NAME.replaceAll('/', '%2F')}" +
            ", knime-json/${env.BRANCH_NAME.replaceAll('/', '%2F')}")
    ]),
    parameters(workflowTests.getConfigurationsAsParameters()),
    buildDiscarder(logRotator(numToKeepStr: '5')),
    disableConcurrentBuilds()
])

try {
    knimetools.defaultTychoBuild('org.knime.update.conda')
    testInstallCondaEnvAction(BN)

    stage('Sonarqube analysis') {
        env.lastStage = env.STAGE_NAME
        // Passing the test configuration is optional but must be done when they are used above in the workflow tests.
        // Therefore you can *remove* the argument in almost all cases.
        // In case you don't have any workflow tests but still want a Sonarqube analysis, pass an empty list, i.e. [].
        workflowTests.runSonar(testConfigurations)
    }
} catch (ex) {
    currentBuild.result = 'FAILURE'
    throw ex
} finally {
    notifications.notifyBuild(currentBuild.result);
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

def testInstallCondaEnvAction(String baseBranch) {
    def branch = env.CHANGE_BRANCH ?: env.BRANCH_NAME

    def testBody = { nodeLabel ->
        node("workflow-tests && ${nodeLabel}") {
            stage("Install Conda Environment Action Tests on ${nodeLabel}") {
                knimetools.materializeResources(['common.inc'])

                def String compositeRepo = "https://jenkins.devops.knime.com/p2/knime/composites/${baseBranch}"
                def String condaRepo = "https://jenkins.devops.knime.com/p2/knime/knime-conda/${branch}"

                sh label: 'Create minimal installation', script: """
                    source common.inc
                    installIU org.knime.minimal.product \"${compositeRepo}\" knime_minimal.app \"\" \"\" \"\" 1
                """

                // Test basic installation
                sh label: 'Copy minimal installation', script: """
                    cp -a knime_minimal.app "knime test.app"
                """
                sh label: 'Install test extension', script: """
                    source common.inc
                    installIU org.knime.features.conda.envbundling.testext.feature.group \"${condaRepo},${compositeRepo}\" \"knime test.app\" \"\" \"\" \"\" 1
                """
                // Check if environment was created in bundling folder and report test result
                def envVersion = nodeLabel == "windows" ? "_0.1.0" : "" // Windows also has a version number in the path
                def envDir = "knime test.app/bundling/org_knime_conda_envbundling_testext${envVersion}/.pixi/envs/default"
                reportTestResult("CondaEnvBundling", "should install environment", fileExists(envDir), "Environment directory does not exist: ${envDir}")

                // Test uninstallation
                sh label: 'Uninstall test extension', script: """
                    source common.inc
                    uninstallIU org.knime.features.conda.envbundling.testext.feature.group \"knime test.app\"
                """
                reportTestResult("CondaEnvBundling", "should uninstall environment", !fileExists(envDir), "Environment directory still exist after feature uninstallation: ${envDir}")

                // Clean up
                sh label: 'Delete knime test.app', script: """
                    rm -rf "knime test.app"
                """

                // Create a temporary directory for KNIME_PYTHON_BUNDLING_PATH
                def bundlingPath = "${env.WORKSPACE}/bundling_${nodeLabel}_${UUID.randomUUID().toString()}"
                sh "mkdir -p \"${bundlingPath}\""

                sh label: 'Copy minimal installation', script: """
                    cp -a knime_minimal.app "knime test.app"
                """

                withEnv(["KNIME_PYTHON_BUNDLING_PATH=${bundlingPath}"]) {
                    sh label: 'Install test extension', script: """
                        source common.inc
                        installIU org.knime.features.conda.envbundling.testext.feature.group \"${condaRepo},${compositeRepo}\" \"knime test.app\" \"\" \"\" \"\" 1
                    """

                    // Check if environment was created in bundling folder and report test result
                    def envVersion = nodeLabel == "windows" ? "_0.1.0" : "" // Windows also has a version number in the path
                    def envDir = "${bundlingPath}/org_knime_conda_envbundling_testext${envVersion}/.pixi/envs/default"
                    reportTestResult("CondaEnvBundling", "should install environment into custom path", fileExists(envDir), "Environment directory does not exist: ${envDir}")

                    // Test uninstallation
                    sh label: 'Uninstall test extension', script: """
                        source common.inc
                        uninstallIU org.knime.features.conda.envbundling.testext.feature.group \"knime test.app\"
                    """
                    reportTestResult("CondaEnvBundling", "should uninstall environment from custom path", !fileExists(envDir), "Environment directory still exist after feature uninstallation: ${envDir}")
                }

                // Clean up
                sh label: 'Delete knime test.app', script: """
                    rm -rf "knime test.app"
                """
            }
        }
    }

    parallel(
        'ubuntu22.04': { testBody('ubuntu22.04') },
        'windows': { testBody('windows') },
        'macosx': { testBody('macosx') },
    )
}

/* vim: set shiftwidth=4 expandtab smarttab: */
