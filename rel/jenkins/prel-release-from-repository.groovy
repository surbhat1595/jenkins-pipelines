library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'jenkins'
    }
    parameters {
        choice(
             choices: [ 'Hetzner','AWS' ],
             description: 'Cloud infra for build',
             name: 'CLOUD' )
        string(
            defaultValue: '',
            description: 'PATH_TO_BUILD must be in form $DESTINATION/**release**/$revision (path under /srv/UPLOAD)',
            name: 'PATH_TO_BUILD')
        booleanParam(name: 'REVERSE', defaultValue: false, description: 'please use reverse sync if you want to fix repo copy on signing server. it will be overwritten with known working copy from production')
        booleanParam(name: 'REMOVE_BEFORE_PUSH', defaultValue: false, description: 'check to remove sources and binary version if equals pushing')
        booleanParam(name: 'REMOVE_LOCKFILE', defaultValue: false, description: 'remove lockfile after unsuccessful push')
        choice(
            choices: 'TESTING\nRELEASE\nEXPERIMENTAL\nLABORATORY',
            description: 'repo component to push to (PREL component repository)',
            name: 'COMPONENT')
        booleanParam(name: 'SKIP_RPM_PUSH', defaultValue: false, description: 'Skip push to PREL RPM repository')
        booleanParam(name: 'SKIP_DEB_PUSH', defaultValue: false, description: 'Skip push to PREL DEB repository')
        booleanParam(name: 'SKIP_MAIN_YUM_PUSH', defaultValue: false, description: 'Skip publishing percona-release-*.noarch.rpm to /srv/repo-copy/yum/')
        booleanParam(name: 'SKIP_MAIN_APT_PUSH', defaultValue: false, description: 'Skip publishing percona-release_* .deb files to /srv/repo-copy/apt/')
        booleanParam(name: 'SKIP_REPO_SYNC', defaultValue: false, description: 'Skip sync repos to production')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timestamps ()
    }
    environment {
        REPOSITORY = 'PREL'
    }
    stages {
        stage('Push to RPM repository') {
            steps {
                withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
                    withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                        sh """
                            if [ ${SKIP_RPM_PUSH} = false ] || [ ${SKIP_MAIN_YUM_PUSH} = false ]; then
                                if [ x"${PATH_TO_BUILD}" = x ]; then
                                    echo "Empty path!"
                                    exit 1
                                fi
                                ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} ${USER}@repo.ci.percona.com << 'ENDSSH'
                                    set -o errexit
                                    set -o xtrace
                                    echo /srv/UPLOAD/${PATH_TO_BUILD}
                                    cd /srv/UPLOAD/${PATH_TO_BUILD}

                                    REPOCOMP=\$(echo "${COMPONENT}" | tr '[:upper:]' '[:lower:]')
                                    LCREPOSITORY=\$(echo "${REPOSITORY}" | tr '[:upper:]' '[:lower:]')
                                    export PATH="/usr/local/reprepro5/bin:\${PATH}"
                                    RHVERS=\$(ls -1 binary/redhat | grep -v 6)

                                    if [ ${SKIP_RPM_PUSH} = false ]; then
                                        export REPOPATH="repo-copy/\${LCREPOSITORY}/yum"
                                        echo "<*> Pushing to PREL repo: /srv/\${REPOPATH}/\${REPOCOMP}"
                                        # -------------------------------------> source processing (prel/yum)
                                        if [[ -d source/redhat ]]; then
                                            SRCRPM=\$(find source/redhat -name '*.src.rpm')
                                            for rhel in \${RHVERS}; do
                                                mkdir -p /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS
                                                cp -v \${SRCRPM} /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS
                                                createrepo --update /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS
                                                if [[ -f /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS/repodata/repomd.xml.asc ]]; then
                                                    rm -f /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS/repodata/repomd.xml.asc
                                                fi
                                                gpg --detach-sign --armor --passphrase $SIGN_PASSWORD /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS/repodata/repomd.xml
                                            done
                                        fi
                                        # -------------------------------------> binary processing (prel/yum)
                                        pushd binary
                                        for rhel in \${RHVERS}; do
                                            mkdir -p /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS
                                            for arch in \$(ls -1 redhat/\${rhel}); do
                                                mkdir -p /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}
                                                cp -av redhat/\${rhel}/\${arch}/*.rpm /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/
                                                createrepo --update /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/
                                                if [ -f  /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/repodata/repomd.xml.asc ]; then
                                                    rm -f  /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/repodata/repomd.xml.asc
                                                fi
                                                gpg --detach-sign --armor --passphrase $SIGN_PASSWORD /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/repodata/repomd.xml
                                            done
                                        done
                                        popd
                                    fi

                                    # -------------------------------------> publish percona-release noarch RPM at /srv/repo-copy/yum/
                                    if [ ${SKIP_MAIN_YUM_PUSH} = false ]; then
                                        SAMPLE_RPM=\$(find binary/redhat -name 'percona-release-*.noarch.rpm' 2>/dev/null | head -1)
                                        if [ -z "\${SAMPLE_RPM}" ]; then
                                            echo "<!> No percona-release noarch RPM found under binary/redhat — skipping top-level yum publish"
                                        else
                                            mkdir -p /srv/repo-copy/yum
                                            PREL_RPM_NAME=\$(basename \${SAMPLE_RPM})
                                            echo "<*> Publishing \${PREL_RPM_NAME} to /srv/repo-copy/yum/"
                                            cp -v \${SAMPLE_RPM} /srv/repo-copy/yum/\${PREL_RPM_NAME}
                                            cd /srv/repo-copy/yum
                                            rm -f percona-release-latest.noarch.rpm
                                            ln -sv \${PREL_RPM_NAME} percona-release-latest.noarch.rpm
                                            cd -
                                        fi
                                    fi

                                    date +%s > /srv/repo-copy/version
ENDSSH
                           else
                              echo "The step is skipped."
                           fi
                        """
                    }
                }
            }
        }
        stage('Push to DEB repository') {
            steps {
                withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
                    withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                        sh """
                            if [ ${SKIP_DEB_PUSH} = false ] || [ ${SKIP_MAIN_APT_PUSH} = false ]; then
                                if [ x"${PATH_TO_BUILD}" = x ]; then
                                    echo "Empty path!"
                                    exit 1
                                fi
                                ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} ${USER}@repo.ci.percona.com << 'ENDSSH'
                                    set -o errexit
                                    set -o xtrace
                                    cd /srv/UPLOAD/${PATH_TO_BUILD}

                                    REPOPUSH_ARGS=""
                                    REPOCOMP=\$(echo "${COMPONENT}" | tr '[:upper:]' '[:lower:]')
                                    LCREPOSITORY=\$(echo "${REPOSITORY}" | tr '[:upper:]' '[:lower:]')
                                    if [ ${REMOVE_BEFORE_PUSH} = true ]; then
                                        if [[ ! ${COMPONENT} == RELEASE ]]; then
                                            REPOPUSH_ARGS=" --remove-package "
                                        else
                                            echo "it is not allowed to remove packages from RELEASE repository"
                                            exit 1
                                        fi
                                    fi
                                    export PATH="/usr/local/reprepro5/bin:\${PATH}"
                                    export REPOPATH="/srv/repo-copy/\${LCREPOSITORY}/apt"
                                    set -e
                                    echo "<*> path to PREL apt repo is "\${REPOPATH}
                                    echo "<*> reprepro binary is "\$(which reprepro)
                                    cd /srv/UPLOAD/${PATH_TO_BUILD}/binary/debian
                                    CODENAMES=\$(ls -1)
                                    echo "<*> Distributions are: "\${CODENAMES}

                                    if [ ${SKIP_DEB_PUSH} = false ]; then
                                        if [[ ${REMOVE_LOCKFILE} = true ]]; then
                                            echo "<*> Removing lock file as requested..."
                                            rm -vf  \${REPOPATH}/db/lockfile
                                        fi

                                        # In the prel/apt repo, RELEASE is published as the "main" component.
                                        PREL_REPOCOMP=\${REPOCOMP}
                                        if [[ ${COMPONENT} == RELEASE ]]; then
                                            PREL_REPOCOMP=main
                                        fi

                                        # -------------------------------------> source pushing (prel/apt only)
                                        if [[ ${COMPONENT} == RELEASE ]]; then
                                            if [ -d /srv/UPLOAD/${PATH_TO_BUILD}/source/debian ]; then
                                                cd /srv/UPLOAD/${PATH_TO_BUILD}/source/debian
                                                DSC=\$(find . -type f -name '*.dsc')
                                                for DSC_FILE in \${DSC}; do
                                                    echo "<*> DSC file is "\${DSC_FILE}
                                                    for _codename in \${CODENAMES}; do
                                                        echo "<*> CODENAME: "\${_codename}
                                                        repopush --gpg-pass=${SIGN_PASSWORD} --package=\${DSC_FILE} --repo-path=\${REPOPATH} --component=\${PREL_REPOCOMP} --codename=\${_codename} --verbose \${REPOPUSH_ARGS} || true
                                                        sleep 5
                                                    done
                                                done
                                            fi
                                        fi

                                        # -------------------------------------> PREL-specific: dereference shared reprepro
                                        # pool file for arch-all (_all.deb) packages by removing them from every codename
                                        # first, then push a single canonical _all.deb for all codenames.
                                        cd /srv/UPLOAD/${PATH_TO_BUILD}/binary
                                        canonical_dir=/tmp/canonical_all_debs_\$\$
                                        mkdir -p \${canonical_dir}
                                        for all_deb in \$(find debian -name '*_all.deb' | sort); do
                                            base=\$(basename \${all_deb})
                                            if [ ! -f \${canonical_dir}/\${base} ]; then
                                                echo \${all_deb} > \${canonical_dir}/\${base}
                                            fi
                                        done
                                        for all_deb_base in \$(ls \${canonical_dir} 2>/dev/null); do
                                            pkg_name=\$(echo \${all_deb_base} | cut -d'_' -f1)
                                            for push_dist in \$(ls -1 debian); do
                                                /usr/local/reprepro5/bin/reprepro -Vb \${REPOPATH} -C \${PREL_REPOCOMP} remove \${push_dist} \${pkg_name} 2>/dev/null || true
                                            done
                                        done

                                        # -------------------------------------> binary pushing (prel/apt)
                                        cd /srv/UPLOAD/${PATH_TO_BUILD}/binary/debian
                                        for _codename in \${CODENAMES}; do
                                            echo "<*> CODENAME: "\${_codename}
                                            pushd \${_codename}
                                            DEBS=\$(find . -type f -name '*.*deb' )
                                            for _deb in \${DEBS}; do
                                                push_deb=\${_deb}
                                                pkg_fname=\$(basename \${_deb})
                                                if [ -f \${canonical_dir}/\${pkg_fname} ]; then
                                                    # canonical paths are relative to binary/, we are in binary/debian/<codename>
                                                    push_deb="../../\$(cat \${canonical_dir}/\${pkg_fname})"
                                                fi
                                                repopush --gpg-pass=${SIGN_PASSWORD} --package=\${push_deb} --repo-path=\${REPOPATH} --component=\${PREL_REPOCOMP} --codename=\${_codename} --verbose \${REPOPUSH_ARGS}
                                            done
                                            popd
                                        done

                                        rm -rf \${canonical_dir}
                                    fi

                                    # -------------------------------------> publish a single canonical percona-release deb at
                                    # /srv/repo-copy/apt/ and symlink percona-release_latest.<codename>_all.deb for every
                                    # supported codename (plus the .generic variant) to it. The package is arch-all and
                                    # identical across codenames except for the changelog/dist tag, so one copy is enough.
                                    if [ ${SKIP_MAIN_APT_PUSH} = false ]; then
                                        mkdir -p /srv/repo-copy/apt
                                        cd /srv/UPLOAD/${PATH_TO_BUILD}/binary/debian
                                        SAMPLE_DEB=\$(find . -type f -name 'percona-release_*_all.deb' | head -1)
                                        if [ -z "\${SAMPLE_DEB}" ]; then
                                            echo "<!> No percona-release deb found under binary/debian — skipping top-level apt publish"
                                        else
                                            # Extract <verrel> (e.g. 1.0-33) from filename. Handles both
                                            #   percona-release_<verrel>.<codename>_<arch>.deb  and
                                            #   percona-release_<verrel>_<arch>.deb
                                            base=\$(basename \${SAMPLE_DEB})
                                            tmp=\${base#percona-release_}
                                            tmp=\${tmp%_*.deb}
                                            case "\${tmp}" in
                                                *.bookworm|*.bullseye|*.buster|*.trixie|*.focal|*.jammy|*.noble|*.resolute|*.generic)
                                                    VERREL=\${tmp%.*}
                                                    ;;
                                                *)
                                                    VERREL=\${tmp}
                                                    ;;
                                            esac

                                            CANONICAL_NAME="percona-release_\${VERREL}.generic_all.deb"
                                            echo "<*> Publishing \${CANONICAL_NAME} to /srv/repo-copy/apt/"
                                            cp -v \${SAMPLE_DEB} /srv/repo-copy/apt/\${CANONICAL_NAME}

                                            cd /srv/repo-copy/apt
                                            for _codename in bookworm bullseye buster trixie focal noble resolute generic; do
                                                _link="percona-release_latest.\${_codename}_all.deb"
                                                rm -f \${_link}
                                                ln -sv \${CANONICAL_NAME} \${_link}
                                            done
                                            cd -
                                        fi
                                    fi

                                    date +%s > /srv/repo-copy/version
ENDSSH
                            else
                                echo "The step is skipped."
                            fi
                        """
                    }
                }
            }
        }
        stage('Sync repos to production') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh """
                        if [ ${SKIP_REPO_SYNC} = false ]; then
                            ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} ${USER}@repo.ci.percona.com << 'ENDSSH'
                                set -o errexit
                                set -o xtrace
                                LCREPOSITORY=\$(echo "${REPOSITORY}" | tr '[:upper:]' '[:lower:]')
                                cd /srv/repo-copy/
                                RSYNC_TRANSFER_OPTS=" -avt --delete --delete-excluded --delete-after --progress"
                                if [[ ${REVERSE} = true ]]; then
                                    rsync \${RSYNC_TRANSFER_OPTS} 10.30.9.32:/www/repo.percona.com/htdocs/\${LCREPOSITORY}/* /srv/repo-copy/\${LCREPOSITORY}/
                                else
                                    # PREL-specific repo (repo.percona.com/prel/)
                                    rsync \${RSYNC_TRANSFER_OPTS} --exclude=*.sh --exclude=*.bak /srv/repo-copy/\${LCREPOSITORY}/* 10.30.9.32:/www/repo.percona.com/htdocs/\${LCREPOSITORY}/

                                    # top-level yum (repo.percona.com/yum/) — only the percona-release rpm + symlink at root.
                                    # Use --include/--exclude to avoid touching component subdirs (release/, testing/, ...).
                                    if [ ${SKIP_MAIN_YUM_PUSH} = false ] && [ -d /srv/repo-copy/yum ]; then
                                        rsync -avt --progress --links \
                                            --include='percona-release-*.noarch.rpm' \
                                            --include='percona-release-latest.noarch.rpm' \
                                            --exclude='*' \
                                            /srv/repo-copy/yum/ 10.30.9.32:/www/repo.percona.com/htdocs/yum/
                                    fi

                                    # top-level apt (repo.percona.com/apt/) — only the percona-release debs + symlinks at root.
                                    # Don't touch the reprepro structure (pool/, dists/, db/, conf/) managed by other jobs.
                                    if [ ${SKIP_MAIN_APT_PUSH} = false ] && [ -d /srv/repo-copy/apt ]; then
                                        rsync -avt --progress --links \
                                            --include='percona-release_*.deb' \
                                            --exclude='*' \
                                            /srv/repo-copy/apt/ 10.30.9.32:/www/repo.percona.com/htdocs/apt/
                                    fi

                                    rsync \${RSYNC_TRANSFER_OPTS} --exclude=*.sh --exclude=*.bak /srv/repo-copy/version 10.30.9.32:/www/repo.percona.com/htdocs/
                                fi
ENDSSH
                        else
                            echo "The step is skipped."
                        fi
                    """
                }
            }
        }
        stage('Refresh downloads area') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh """
                       ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} ${USER}@repo.ci.percona.com << 'ENDSSH'
                           if [ ${COMPONENT} = RELEASE ]; then
                               curl -k https://www.percona.com/admin/config/percona/percona_downloads/crawl_directory
                           fi
ENDSSH
                    """
                }
            }
        }
        stage('Cleanup') {
            steps {
                deleteDir()
            }
        }
    }
    post {
        always {
            script {
                currentBuild.description = "Repo: ${REPOSITORY}/${COMPONENT}, path to packages: ${PATH_TO_BUILD}"
            }
        }
    }
}
