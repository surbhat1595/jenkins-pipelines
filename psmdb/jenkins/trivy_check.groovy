library changelog: false, identifier: 'lib@hetznertrivy', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/surbhat1595/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    withCredentials([
                    string(credentialsId: 'SNYK_TOKEN', variable: 'SNYK_TOKEN'),
                    string(credentialsId: 'SNYK_ORG_TOKEN', variable: 'SNYK_ORG_TOKEN')
                ]){
      sh """
        echo "Starting Build Stage"
        set -o xtrace
        pwd -P
        ls -laR
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} -e SNYK_TOKEN=${SNYK_TOKEN} -e SNYK_ORG_TOKEN=${SNYK_ORG_TOKEN} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            ls -laR
            uname -a"
            curl -fsSL https://raw.githubusercontent.com/surbhat1595/sbom_verifier/main/install_update_trivy.sh | bash
    """
    }
}

void uploadPSMDBSBOMToTestingDownloadServer(String productName, String packageVersion, String SBOMType) {

    script {
        try {
            uploadSBOMToDownloadsTesting(params.CLOUD, productName, packageVersion, SBOMType)
        } catch (err) {
            echo "Caught: ${err}"
            currentBuild.result = 'UNSTABLE'
        }
    }
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def TIMESTAMP

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
    }

    parameters {
        choice(
             choices: [ 'Hetzner','AWS' ],
             description: 'Cloud infra for build',
             name: 'CLOUD' )
        string(
            defaultValue: 'https://github.com/percona/percona-server-mongodb.git',
            description: 'URL for psmdb_sbom repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '7.0.18-11',
            description: 'Version of Percona Server MongoDB',
            name: 'PSMDB_VERSION')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona-server-mongodb repository',
            name: 'GIT_BRANCH')
	choice(
            choices: 'laboratory\ntesting\nexperimental\nrelease',
            description: 'Packaging repository type',
            name: 'REPO_TYPE')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
    }
    stages {

        stage('Generate SBOM') {
            parallel {
                stage('Generate PSMDB SBOM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PSMDB_SBOM/${PSMDB_VERSION}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PSMDB_VERSION}
                                        cat uploadPath-${PSMDB_VERSION}
                                """
                                stash includes: "uploadPath-${PSMDB_VERSION}", name: "uploadPath-${PSMDB_VERSION}"
                                sh """
                                        curl -fsSL https://raw.githubusercontent.com/surbhat1595/sbom_verifier/main/install_update_trivy.sh | bash
                                """
                        }
                    }
                }
            }  //parallel
        } // stage

    }
    post {
        success {
            //slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built on ${GIT_BRANCH}"
            }
            deleteDir()
        }
        failure {
            //slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for ${GIT_BRANCH} - [${BUILD_URL}]")
            deleteDir()
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
