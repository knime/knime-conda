#!groovy
def BN = (BRANCH_NAME == 'master' || BRANCH_NAME.startsWith('releases/')) ? BRANCH_NAME : 'releases/2024-12'

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
    // provide the name of the update site project
    knimetools.defaultTychoBuild('org.knime.update.conda')

    // Specifying configurations is optional. If omitted, the default configurations will be used
    // (see jenkins-pipeline-libraries/vars/workflowTests.groovy).
    // In almost all cases you can *remove* this defintion.
    def testConfigurations = [
        "ubuntu18.04 && python3",
        "windows && python3"
    ]

//    workflowTests.runTests(
//        dependencies: [
//            // A list of repositories required for running workflow tests. All repositories that are required for a minimal
//            // KNIME AP installation are added by default and don't need to be specified here. Currently these are:
//            //
//            // 'knime-tp', 'knime-shared', 'knime-core', 'knime-base', 'knime-workbench', 'knime-expressions',
//            // 'knime-js-core','knime-svg', 'knime-product'
//            //
//            // All features (not plug-ins!) in the specified repositories will be installed.
//            repositories: ['knime-conda'],
//            // an optional list of additional bundles/plug-ins from the repositories above that must be installed
//            ius: ['org.knime.json.tests']
//        ],
//        // this is optional and defaults to false
//        withAssertions: true,
//        // this is optional and only needs to be provided if non-default configurations are used, see above
//        configurations: testConfigurations
//    )

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

/* vim: set shiftwidth=4 expandtab smarttab: */
