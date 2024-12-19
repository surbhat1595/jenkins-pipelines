library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        mkdir test
        wget \$(echo ${BUILD_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BUILD_BRANCH}/mysql-shell_builder.sh -O mysql-shell_builder.sh
        pwd -P
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            bash -x ./mysql-shell_builder.sh --builddir=\${build_dir}/test --install_deps=1 --mysqlshell_branch=$SHELL_BRANCH
            bash -x mysql-shell_builder.sh --builddir=\${build_dir}/test --repo_mysqlshell=$SHELL_REPO --mysqlshell_branch=$SHELL_BRANCH --repo=${PS_REPO} --branch_db=${PS_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}"
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
        label 'docker-32gb'
    }
    parameters {
        string(
            defaultValue: 'https://github.com/percona/mysql-shell-packaging.git',
            description: 'URL for mysql-shell packaging repository',
            name: 'BUILD_REPO')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for mysql-shell packaging repository',
            name: 'BUILD_BRANCH')
        string(
            defaultValue: 'https://github.com/mysql/mysql-shell.git',
            description: 'URL for mysql-shell repository',
            name: 'SHELL_REPO')
        string(
            defaultValue: '8.0.33',
            description: 'Tag/Branch for mysql-shell repository',
            name: 'SHELL_BRANCH')  
        string(
            defaultValue: 'https://github.com/percona/percona-server.git',
            description: 'URL for percona-server repository',
            name: 'PS_REPO')
        string(
            defaultValue: '8.0.33',
            description: 'Tag/Branch for percona-server repository',
            name: 'PS_BRANCH')   
        string(
            defaultValue: '1',
            description: 'RPM release value',
            name: 'RPM_RELEASE')
        string(
            defaultValue: '1',
            description: 'DEB release value',
            name: 'DEB_RELEASE')
        choice(
            choices: 'testing\nlaboratory\nexperimental',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '15', artifactNumToKeepStr: '15'))
    }
    stages {
        stage('Create MYSQL-SHELL source tarball') {
            steps {
                // slackNotify("", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]")
                script {
                    cleanUpWS()
                    PS_MAJOR_RELEASE = sh(returnStdout: true, script: ''' echo ${PS_BRANCH} | sed "s/release-//g" | sed "s/\\.//g" | awk '{print substr($0, 0, 2)}' ''').trim()
                    //if ("${PS_MAJOR_RELEASE}" == "80") {
                    //    buildStage("debian:buster", "--get_sources=1")
                    //} else {
                    buildStage("ubuntu:focal", "--get_sources=1")
                    //}
                }
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/mysql-shell.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/mysql-shell.properties
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
        stage('Build MYSQL-SHELL generic source packages') {
            parallel {
                stage('Build MYSQL-SHELL generic source rpm') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("amazonlinux:2023", "--build_src_rpm=1")

                        pushArtifactFolder("srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("srpm/", AWS_STASH_PATH)
                    }
                }
            }
        }
        stage('Build MYSQL-SHELL RPMs/DEBs/Binary tarballs') {
            parallel {
                stage('Amazon Linux 2023') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("amazonlinux:2023", "--build_rpm=1")

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
                sync2ProdAutoBuild('mysql-shell', COMPONENT)
            }
        }

    }
    post {
        success {
            // slackNotify("", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built on ${SHELL_BRANCH}, path to packages: experimental/${AWS_STASH_PATH}"
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
