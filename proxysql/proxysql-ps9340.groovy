library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/surbhat1595/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        mkdir test
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/scripts/proxysql_builder.sh -O proxysql_builder.sh
        pwd -P
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            sed -i "s/^RPM_RELEASE=.*/RPM_RELEASE=${RPM_RELEASE}/g" proxysql_builder.sh
            sed -i "s/^DEB_RELEASE=.*/DEB_RELEASE=${DEB_RELEASE}/g" proxysql_builder.sh
            bash -x ./proxysql_builder.sh --builddir=\${build_dir}/test --install_deps=1
            bash -x ./proxysql_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --proxysql_repo=${PROXYSQL_REPO} --pat_repo=${PAT_REPO} --pat_tag=${PAT_TAG} --proxysql_branch=${PROXYSQL_BRANCH} --proxysql_ver=${VERSION} --branch=${GIT_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}"
    """
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def AWS_STASH_PATH

pipeline {
    agent {
        label 'docker'
    }
    parameters {
        string(
            defaultValue: 'https://github.com/percona/proxysql-packaging.git',
            description: 'URL for proxysql packaging repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'v2.1',
            description: 'Tag/Branch for proxysql packaging repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/sysown/proxysql.git',
            description: 'URL for proxysql repository',
            name: 'PROXYSQL_REPO')
        string(
            defaultValue: 'v2.6.2',
            description: 'Tag/Branch for proxysql repository',
            name: 'PROXYSQL_BRANCH')  
        string(
            defaultValue: '2.6.2',
            description: 'PROXYSQL release value',
            name: 'VERSION')  
        string(
            defaultValue: 'https://github.com/percona/proxysql-admin-tool.git',
            description: 'URL for proxysql-admin-tool repository',
            name: 'PAT_REPO')
        string(
            defaultValue: 'v2',
            description: 'Tag/Branch for proxysql-admin-tool repository',
            name: 'PAT_TAG')   
        string(
            defaultValue: '1.1',
            description: 'RPM release value',
            name: 'RPM_RELEASE')
        string(
            defaultValue: '1.1',
            description: 'DEB release value',
            name: 'DEB_RELEASE')
        string(
            defaultValue: 'proxysql',
            description: 'PROXYSQL repo name',
            name: 'PROXYSQL_DEST_REPO')
        choice(
            choices: 'laboratory\ntesting\nexperimental',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
    }
    stages {
        stage('Create PROXYSQL source tarball') {
            steps {
                // slackNotify("", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                buildStage("oraclelinux:8", "--get_sources=1")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/proxysql.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/proxysql.properties
                   cat uploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                pushArtifactFolder("source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS("source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        stage('Build PROXYSQL generic source packages') {
            parallel {
                stage('Build PROXYSQL generic source rpm') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_src_rpm=1")

                        pushArtifactFolder("srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("srpm/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage
        stage('Build PROXYSQL RPMs/DEBs/Binary tarballs') {
            parallel {
                stage('Oracle Linux 8') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_rpm=1")

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_rpm=1")
            
                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                } 

            }
        }

        stage('Sign packages') {
            steps {
                signRPM()
            }
        }
        stage('Push to public repository') {
            steps {
                // sync packages
                sync2ProdAutoBuild(PROXYSQL_DEST_REPO, COMPONENT)
            }
        }

    }
    post {
        success {
            // slackNotify("", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built on ${PROXYSQL_BRANCH} + ${PAT_TAG} - [${BUILD_URL}]"
            }
            deleteDir()
        }
        failure {
           // slackNotify("", "#FF0000", "[${JOB_NAME}]: build failed for ${GIT_BRANCH} - [${BUILD_URL}]")
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
