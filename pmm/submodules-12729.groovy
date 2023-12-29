library changelog: false, identifier: 'lib@PMM-12729-pmm2', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/surbhat1595/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'PMM-2.0',
            description: 'Tag/Branch for pmm-submodules repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '',
            description: 'URL for pmm-submodules repository PR',
            name: 'CH_URL')
        string(
            defaultValue: '',
            description: 'ID for pmm-submodules repository PR',
            name: 'CH_ID')
        choice(
//            default is choices.get(0) - el9
            choices: ['el9', 'el7'],
            description: 'Select the OS to build for',
            name: 'BUILD_OS')
    }
    environment {
        PATH_TO_SCRIPTS = 'sources/pmm/src/github.com/percona/pmm/build/scripts'
        PMM_VERSION = """${sh(returnStdout: true, script: "cat VERSION").trim()}"""
    }
    stages {
        stage('Prepare') {
            steps {
                withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                sh '''
                    set -o errexit
                    if [ -s ci.yml ]; then
                        sudo rm -rf results tmp || :
                        git reset --hard
                        git clean -fdx
                        python3 ci.py
                        . ./.git-sources
                        echo $pmm_commit > apiCommitSha
                        echo $pmm_branch > apiBranch
                        echo $pmm_url > apiURL
                        echo $pmm_qa_branch > pmmQABranch
                        echo $pmm_qa_commit > pmmQACommitSha
                        echo $pmm_ui_tests_branch > pmmUITestBranch
                        echo $pmm_ui_tests_commit > pmmUITestsCommitSha
                    else
                        sudo rm -rf results tmp || :
                        git reset --hard
                        git clean -fdx
                        git submodule foreach --recursive git reset --hard
                        git submodule foreach --recursive git clean -fdx
                        git submodule status
                        export commit_sha=$(git submodule status | grep 'pmm-managed' | awk -F ' ' '{print $1}')
                        export api_tests_commit_sha=$(git submodule status | grep 'sources/pmm/src' | awk -F ' ' '{print $1}')
                        export api_tests_branch=$(git config -f .gitmodules submodule.pmm.branch)
                        export api_tests_url=$(git config -f .gitmodules submodule.pmm.url)
                        echo $api_tests_commit_sha > apiCommitSha
                        echo $api_tests_branch > apiBranch
                        echo $api_tests_url > apiURL
                        cat apiBranch
                        cat apiURL
                        export pmm_qa_commit_sha=$(git submodule status | grep 'pmm-qa' | awk -F ' ' '{print $1}')
                        export pmm_qa_branch=$(git config -f .gitmodules submodule.pmm-qa.branch)
                        echo $pmm_qa_branch > pmmQABranch
                        echo $pmm_qa_commit_sha > pmmQACommitSha
                        export pmm_ui_tests_commit_sha=$(git submodule status | grep 'pmm-ui-tests' | awk -F ' ' '{print $1}')
                        export pmm_ui_tests_branch=$(git config -f .gitmodules submodule.pmm-ui-tests.branch)
                        echo $pmm_ui_tests_branch > pmmUITestBranch
                        echo $pmm_ui_tests_commit_sha > pmmUITestsCommitSha
                    fi
                    export fb_commit_sha=$(git rev-parse HEAD)
                    echo $fb_commit_sha > fbCommitSha
                    echo $CHANGE_URL > changeUrl
                    echo $CHANGE_ID > changeId
                '''
                }
                script {
                    env.PMM_VERSION = sh(returnStdout: true, script: "cat VERSION").trim()
                }
                stash includes: 'apiBranch', name: 'apiBranch'
                stash includes: 'apiURL', name: 'apiURL'
                stash includes: 'pmmQABranch', name: 'pmmQABranch'
                stash includes: 'apiCommitSha', name: 'apiCommitSha'
                stash includes: 'pmmQACommitSha', name: 'pmmQACommitSha'
                stash includes: 'pmmUITestBranch', name: 'pmmUITestBranch'
                stash includes: 'pmmUITestsCommitSha', name: 'pmmUITestsCommitSha'
                stash includes: 'fbCommitSha', name: 'fbCommitSha'
               // slackSend channel: '#pmm-ci', color: '#0000FF', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
            }
        }


        stage('Trigger PMM2 Submodules pipeline') {
            when {
                        beforeAgent true
                        expression {
                            env.PMM_VERSION =~ '^2.'
                        }
                 }
            steps {
                script {
                    env.PMM_BRANCH = sh(returnStdout: true, script: "cat apiBranch").trim()
                    env.CHANGE_URL = sh(returnStdout: true, script: "cat changeUrl").trim()
                    env.CHANGE_ID = sh(returnStdout: true, script: "cat changeId").trim()
                }
                build job: 'submodules2-12729', parameters: [
                    string(name: 'GIT_BRANCH', value: "${PMM_BRANCH}"),
                    string(name: 'CH_URL', value: "${CHANGE_URL}"),
                    string(name: 'CH_ID', value: "${CHANGE_ID}"),
                    string(name: 'BUILD_OS', value: params.BUILD_OS)
                ]
            }
        }
        stage('Trigger PMM3 Submodules pipeline') {
            when {
                        beforeAgent true
                        expression {
                            env.PMM_VERSION =~ '^3.'
                        }
                 }
            steps {
                script {
                    env.PMM_BRANCH = sh(returnStdout: true, script: "cat apiBranch").trim()
                    env.CHANGE_URL = sh(returnStdout: true, script: "cat changeUrl").trim()
                    env.CHANGE_ID = sh(returnStdout: true, script: "cat changeId").trim()
                }
                build job: 'submodules3-12729', parameters: [
                    string(name: 'GIT_BRANCH', value: "${PMM_BRANCH}"),
                    string(name: 'CH_URL', value: "${CHANGE_URL}"),
                    string(name: 'CH_ID', value: "${CHANGE_ID}"),
                    string(name: 'BUILD_OS', value: params.BUILD_OS)
                ]
            }
        }
    }
}
