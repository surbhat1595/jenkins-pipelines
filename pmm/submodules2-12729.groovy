library changelog: false, identifier: 'lib@PMM-12729', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/surbhat1595/jenkins-pipelines.git'
]) _

void runAPItests(String DOCKER_IMAGE_VERSION, GIT_URL, GIT_BRANCH, GIT_COMMIT_HASH, CLIENT_VERSION) {
    apiTestJob = build job: 'pmm2-api-tests', propagate: false, parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_IMAGE_VERSION),
        string(name: 'GIT_URL', value: GIT_URL),
        string(name: 'GIT_BRANCH', value: GIT_BRANCH),
        string(name: 'OWNER', value: "FB"),
        string(name: 'GIT_COMMIT_HASH', value: GIT_COMMIT_HASH)
    ]
    env.API_TESTS_URL = apiTestJob.absoluteUrl
    env.API_TESTS_RESULT = apiTestJob.result
}

void addComment(String COMMENT) {
    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
        sh """
            curl -v -X POST \
                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                -d "{\\"body\\":\\"${COMMENT}\\"}" \
                "https://api.github.com/repos/\$(echo $CHANGE_URL | cut -d '/' -f 4-5)/issues/${CHANGE_ID}/comments"
        """
    }
}

pipeline {
    agent {
        label 'agent-amd64'
    }
    parameters {
        string(
            defaultValue: 'PMM-2.0',
            description: 'Tag/Branch for pmm-submodules repository',
            name: 'GIT_BRANCH')
        choice(
            // default is choices.get(0) - el9
            choices: ['el9', 'el7'],
            description: 'Select the OS to build for',
            name: 'BUILD_OS')
    }
    environment {
        PATH_TO_SCRIPTS = 'sources/pmm/src/github.com/percona/pmm/build/scripts'
    }
    stages {
        stage('Prepare') {
            steps {
                git poll: true,
                    branch: GIT_BRANCH,
                    url: 'http://github.com/Percona-Lab/pmm-submodules'
                sh '''
                    set -o errexit
                    git submodule update --init --jobs 10
                    git submodule status

                    git rev-parse --short HEAD > shortCommit
                '''

                script {
                    env.PMM_VERSION = sh(returnStdout: true, script: "cat VERSION").trim()
                }

                archiveArtifacts 'shortCommit'
//                slackSend botUser: true, channel: '#pmm-ci', color: '#0000FF', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
            }
        }

        stage('Build client source') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -o errexit

                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        ${PATH_TO_SCRIPTS}/build-client-source
                    """
                }
            }
        }
        stage('Build client binary') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -o errexit

                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        ${PATH_TO_SCRIPTS}/build-client-binary
                        aws s3 cp \
                            --acl public-read \
                            results/tarball/pmm2-client-*.tar.gz \
                            s3://pmm-build-cache/PR-BUILDS/pmm2-client/pmm2-client-\${BRANCH_NAME}-\${GIT_COMMIT:0:7}.tar.gz
                    """
                }
                script {
                    def clientPackageURL = sh script:'echo "https://s3.us-east-2.amazonaws.com/pmm-build-cache/PR-BUILDS/pmm2-client/pmm2-client-${BRANCH_NAME}-${GIT_COMMIT:0:7}.tar.gz" | tee CLIENT_URL', returnStdout: true
                    env.CLIENT_URL = sh(returnStdout: true, script: "cat CLIENT_URL").trim()
                }
                stash includes: 'CLIENT_URL', name: 'CLIENT_URL'
            }
        }
        stage('Build client source rpm EL7') {
            when {
                expression { params.BUILD_OS == "el7" }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -o errexit
                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        ${PATH_TO_SCRIPTS}/build-client-srpm centos:7
                    """
                }
            }
        }
        stage('Build client source rpm') {
            when {
                expression { params.BUILD_OS == "el9" }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        set -o errexit
                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        ${PATH_TO_SCRIPTS}/build-client-srpm public.ecr.aws/e7j3v3n0/rpmbuild:ol9
                    '''
                }
            }
        }
        stage('Build client binary rpm EL7') {
            when {
                expression { params.BUILD_OS == "el7" }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -o errexit

                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        ${PATH_TO_SCRIPTS}/build-client-rpm centos:7

                        mkdir -p tmp/pmm-server/RPMS/
                        cp results/rpm/pmm2-client-*.rpm tmp/pmm-server/RPMS/
                    """
                }
            }
        }
        stage('Build client binary rpm') {
            when {
                expression { params.BUILD_OS == "el9" }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        set -o errexit

                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        ${PATH_TO_SCRIPTS}/build-client-rpm public.ecr.aws/e7j3v3n0/rpmbuild:ol9

                        mkdir -p tmp/pmm-server/RPMS/
                        cp results/rpm/pmm2-client-*.rpm tmp/pmm-server/RPMS/
                    '''
                }
            }
        }
        stage('Build client docker') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        docker login -u "${USER}" -p "${PASS}"
                    """
                }
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -o errexit
                        export PUSH_DOCKER=1
                        export DOCKER_CLIENT_TAG=perconalab/pmm-client-fb:\${BRANCH_NAME}-\${GIT_COMMIT:0:7}
                        ${PATH_TO_SCRIPTS}/build-client-docker
                    """
                }
                stash includes: 'results/docker/CLIENT_TAG', name: 'CLIENT_IMAGE'
                archiveArtifacts 'results/docker/CLIENT_TAG'
            }
        }
        stage('Build server packages EL7') {
            when {
                expression { params.BUILD_OS == "el7" }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -o errexit

                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        export RPM_EPOCH=1
                        export PATH=\$PATH:\$(pwd -P)/${PATH_TO_SCRIPTS}

                        ${PATH_TO_SCRIPTS}/build-server-rpm-all
                    """
                }
            }
        }
        stage('Build server packages') {
            when {
                expression { params.BUILD_OS == "el9" }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''

                        set -o errexit

                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        export RPM_EPOCH=1
                        export PATH=${PATH}:$(pwd -P)/${PATH_TO_SCRIPTS}
                        export RPMBUILD_DOCKER_IMAGE=public.ecr.aws/e7j3v3n0/rpmbuild:ol9
                        export RPMBUILD_DIST="el9"

                        ${PATH_TO_SCRIPTS}/build-server-rpm-all
                    '''
                }
            }
        }
        stage('Build server docker EL7') {
            when {
                expression { params.BUILD_OS == "el7" }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        docker login -u "${USER}" -p "${PASS}"
                    """
                }
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -o errexit

                        export PUSH_DOCKER=1
                        export DOCKER_TAG=perconalab/pmm-server-fb:\${BRANCH_NAME}-\${GIT_COMMIT:0:7}

                        ${PATH_TO_SCRIPTS}/build-server-docker
                    """
                }
                stash includes: 'results/docker/TAG', name: 'IMAGE'
                archiveArtifacts 'results/docker/TAG'
            }
        }
        stage('Build server docker') {
            when {
                expression { params.BUILD_OS == "el9" }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        docker login -u "${USER}" -p "${PASS}"
                    '''
                }
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        set -o errexit

                        export PUSH_DOCKER=1
                        export DOCKER_TAG=perconalab/pmm-server-fb:\${BRANCH_NAME}-\${GIT_COMMIT:0:7}

                        export RPMBUILD_DOCKER_IMAGE=public.ecr.aws/e7j3v3n0/rpmbuild:ol9
                        export RPMBUILD_DIST="el9"
                        export DOCKERFILE=Dockerfile.el9

                        ${PATH_TO_SCRIPTS}/build-server-docker
                    '''
                }
                stash includes: 'results/docker/TAG', name: 'IMAGE'
                archiveArtifacts 'results/docker/TAG'
            }
        }
        stage('Trigger workflows in GH')
        {
            steps{
                script{
                    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                        unstash 'IMAGE'
                        unstash 'pmmQABranch'
                        unstash 'pmmUITestBranch'
                        def IMAGE = sh(returnStdout: true, script: "cat results/docker/TAG").trim()
                        def CLIENT_IMAGE = sh(returnStdout: true, script: "cat results/docker/CLIENT_TAG").trim()
                        def CLIENT_URL = sh(returnStdout: true, script: "cat CLIENT_URL").trim()
                        sh """
                            curl -v -X POST \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                -d "{\\"body\\":\\"server docker - ${IMAGE}\\nclient docker - ${CLIENT_IMAGE}\\nclient - ${CLIENT_URL}\\nCreate Staging Instance: https://pmm.cd.percona.com/job/aws-staging-start/parambuild/?DOCKER_VERSION=${IMAGE}&CLIENT_VERSION=${CLIENT_URL}\\"}" \
                                "https://api.github.com/repos/\$(echo $CHANGE_URL | cut -d '/' -f 4-5)/issues/${CHANGE_ID}/comments"
                        """
                        // trigger workflow in GH to run some test there as well, pass server and client images as parameters
                        def FB_COMMIT_HASH = sh(returnStdout: true, script: "cat fbCommitSha").trim()
                        sh """
                            curl -v -X POST \
                                -H "Accept: application/vnd.github.v3+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                "https://api.github.com/repos/\$(echo $CHANGE_URL | cut -d '/' -f 4-5)/actions/workflows/jenkins-dispatch.yml/dispatches" \
                                -d '{"ref":"${CHANGE_BRANCH}","inputs":{"server_image":"${IMAGE}","client_image":"${CLIENT_IMAGE}","sha":"${FB_COMMIT_HASH}"}}'
                        """
                        // trigger workflow in GH to run PMM binary cli tests
                        sh """
                            curl -v -X POST \
                                -H "Accept: application/vnd.github.v3+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                "https://api.github.com/repos/\$(echo $CHANGE_URL | cut -d '/' -f 4-5)/actions/workflows/pmm-cli.yml/dispatches" \
                                -d '{"ref":"${CHANGE_BRANCH}","inputs":{"client_tar_url":"${CLIENT_URL}","sha":"${FB_COMMIT_HASH}"}}'
                        """
                        // trigger workflow in GH to run testsuite tests
                        def PMM_QA_GIT_BRANCH = sh(returnStdout: true, script: "cat pmmQABranch").trim()
                        sh """
                            curl -v -X POST \
                                -H "Accept: application/vnd.github.v3+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                "https://api.github.com/repos/\$(echo $CHANGE_URL | cut -d '/' -f 4-5)/actions/workflows/pmm2-testsuite.yml/dispatches" \
                                -d '{"ref":"${CHANGE_BRANCH}","inputs":{"server_image":"${IMAGE}","client_image":"${CLIENT_IMAGE}","sha":"${FB_COMMIT_HASH}", "pmm_qa_branch": "${PMM_QA_GIT_BRANCH}", "client_version": "${CLIENT_URL}"}}'
                        """
                        // trigger workflow in GH to run ui tests
                        def PMM_UI_TESTS_GIT_BRANCH = sh(returnStdout: true, script: "cat pmmUITestBranch").trim()
                        sh """
                            curl -v -X POST \
                                -H "Accept: application/vnd.github.v3+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                "https://api.github.com/repos/\$(echo $CHANGE_URL | cut -d '/' -f 4-5)/actions/workflows/pmm2-ui-tests-fb.yml/dispatches" \
                                -d '{"ref":"${CHANGE_BRANCH}","inputs":{"server_image":"${IMAGE}","client_image":"${CLIENT_IMAGE}","sha":"${FB_COMMIT_HASH}", "pmm_qa_branch": "${PMM_QA_GIT_BRANCH}", "pmm_ui_branch": "${PMM_UI_TESTS_GIT_BRANCH}", "client_version": "${CLIENT_URL}"}}'
                        """
                        // trigger workflow in GH to run trivy for vulnerability scan
                        sh """
                            curl -v -X POST \
                                -H "Accept: application/vnd.github.v3+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                "https://api.github.com/repos/\$(echo $CHANGE_URL | cut -d '/' -f 4-5)/actions/workflows/trivy_scan.yml/dispatches" \
                                -d '{"ref":"${CHANGE_BRANCH}","inputs":{"server_image":"${IMAGE}","client_image":"${CLIENT_IMAGE}","sha":"${FB_COMMIT_HASH}"}}'
                        """
                    }
                }
            }
        }
        stage('Tests Execution') {
            parallel {
                stage('Test: API') {
                    steps {
                        script {
                            unstash 'IMAGE'
                            unstash 'apiURL'
                            unstash 'apiBranch'
                            unstash 'apiCommitSha'
                            def IMAGE = sh(returnStdout: true, script: "cat results/docker/TAG").trim()
                            def CLIENT_IMAGE = sh(returnStdout: true, script: "cat results/docker/CLIENT_TAG").trim()
                            def CLIENT_URL = sh(returnStdout: true, script: "cat CLIENT_URL").trim()
                            def API_TESTS_URL = sh(returnStdout: true, script: "cat apiURL").trim()
                            def API_TESTS_BRANCH = sh(returnStdout: true, script: "cat apiBranch").trim()
                            def GIT_COMMIT_HASH = sh(returnStdout: true, script: "cat apiCommitSha").trim()
                            runAPItests(IMAGE, API_TESTS_URL, API_TESTS_BRANCH, GIT_COMMIT_HASH, CLIENT_URL)
                            if (!env.API_TESTS_RESULT.equals("SUCCESS")) {
                                sh "exit 1"
                            }
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    if (env.CHANGE_URL) {
                        unstash 'IMAGE'
                        def IMAGE = sh(returnStdout: true, script: "cat results/docker/TAG").trim()
//                        slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${IMAGE}"
                    }
                } else {
                    if(env.API_TESTS_RESULT != "SUCCESS" && env.API_TESTS_URL) {
                        addComment("API tests have failed, Please check: API: ${API_TESTS_URL}")
                    }
                    if(env.BATS_TESTS_RESULT != "SUCCESS" && env.BATS_TESTS_URL) {
                        addComment("pmm2-client testsuite has failed, Please check: BATS: ${BATS_TESTS_URL}")
                    }
                    if(env.UI_TESTS_RESULT != "SUCCESS" && env.UI_TESTS_URL) {
                        addComment("UI tests have failed, Please check: UI: ${UI_TESTS_URL}")
                    }
//                    slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} build job link: ${BUILD_URL}"
                }
            }
        }
    }
}
