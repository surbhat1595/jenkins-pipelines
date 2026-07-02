#!/bin/bash
#
# Sign RPM packages in an upload path and push them to a yum repository.
# Intended to run on repo.ci.percona.com (where signpackage and createrepo live).
#
# Usage:
#   SIGN_PASSWORD=... ./scripts/sign_and_upload_rpm.sh \
#       --upload-path=UPLOAD/experimental/BUILDS/foo/bar \
#       --repo=ppg-17.6 \
#       --component=testing
#
#   ./scripts/sign_and_upload_rpm.sh --upload-path=... --repo=... --component=... --sync-prod
#
set -o errexit
set -o nounset
set -o pipefail

UPLOAD_PATH=""
REPO_NAME=""
COMPONENT=""
SYNC_PROD=false
DRY_RUN=false

usage() {
    cat <<EOF
Usage: $0 [OPTIONS]

Sign RPMs under UPLOAD_PATH/binary/redhat/ and publish them to
/srv/repo-copy/\${REPO}/yum/\${COMPONENT}/.

Required:
  --upload-path=PATH   Build path on repo server (e.g. UPLOAD/experimental/BUILDS/...)
  --repo=NAME          Target repository name (e.g. ppg-17.6, psmdb-60)
  --component=NAME     Yum component (testing, experimental, laboratory, release)

Optional:
  --sync-prod          Rsync repo-copy to production after publish
  --dry-run            Print actions without signing or copying
  --help               Show this help

Environment:
  SIGN_PASSWORD        GPG passphrase (required unless --dry-run)
EOF
    exit 1
}

normalize_upload_path() {
    local path="$1"

    if [[ "${path}" == UPLOAD/* ]]; then
        path="/srv/${path}"
    elif [[ "${path}" != /* ]]; then
        path="/srv/UPLOAD/${path}"
    fi

    if [[ ! -d "${path}/binary/redhat" ]]; then
        echo "ERROR: ${path}/binary/redhat not found" >&2
        exit 1
    fi

    echo "${path}"
}

parse_arguments() {
    for arg in "$@"; do
        case "${arg}" in
            --upload-path=*) UPLOAD_PATH="${arg#*=}" ;;
            --repo=*) REPO_NAME="${arg#*=}" ;;
            --component=*) COMPONENT="${arg#*=}" ;;
            --sync-prod) SYNC_PROD=true ;;
            --dry-run) DRY_RUN=true ;;
            --help|-h) usage ;;
            *) echo "ERROR: unknown argument: ${arg}" >&2; usage ;;
        esac
    done

    if [[ -z "${UPLOAD_PATH}" || -z "${REPO_NAME}" || -z "${COMPONENT}" ]]; then
        echo "ERROR: --upload-path, --repo, and --component are required" >&2
        usage
    fi

    if [[ "${DRY_RUN}" == false && -z "${SIGN_PASSWORD:-}" ]]; then
        echo "ERROR: SIGN_PASSWORD must be set" >&2
        exit 1
    fi

    UPLOAD_PATH="$(normalize_upload_path "${UPLOAD_PATH}")"
    COMPONENT="$(echo "${COMPONENT}" | tr '[:upper:]' '[:lower:]')"
}

sign_rpms() {
    local rpm_glob="${UPLOAD_PATH}/binary/redhat/*/*/*.rpm"
    local rpms

    shopt -s nullglob
    rpms=(${rpm_glob})
    shopt -u nullglob

    if [[ ${#rpms[@]} -eq 0 ]]; then
        echo "ERROR: no RPM packages found under ${UPLOAD_PATH}/binary/redhat" >&2
        exit 1
    fi

    echo "Signing ${#rpms[@]} RPM package(s)..."
    if [[ "${DRY_RUN}" == true ]]; then
        printf '  would sign: %s\n' "${rpms[@]}"
        return
    fi

    printf '%s\n' "${rpms[@]}" | xargs -n 1 signpackage --verbose --password "${SIGN_PASSWORD}" --rpm
}

push_to_repo() {
    local createrepo_opts=""
    local yum_destination="${COMPONENT}"
    local production_destination="${COMPONENT}"

    if [[ "${REPO_NAME}" == "psmdb-50" || "${REPO_NAME}" == "psmdb-60" ]]; then
        createrepo_opts="--no-database"
    fi

    if [[ "${yum_destination}" == "release" ]]; then
        yum_destination="main"
    fi

  pushd "${UPLOAD_PATH}/binary" >/dev/null
    for rhel in $(ls -1 redhat); do
        local rpm_dest_path="/srv/repo-copy/${REPO_NAME}/yum/${yum_destination}/${rhel}"

        echo "Publishing RPMs for el${rhel} -> ${rpm_dest_path}/RPMS"

        mkdir -p "${rpm_dest_path}/RPMS"
        for arch in $(ls -1 "redhat/${rhel}"); do
            local repo_path="${rpm_dest_path}/RPMS/${arch}"
            mkdir -p "${repo_path}"

            if compgen -G "redhat/${rhel}/${arch}/*.rpm" >/dev/null; then
                if [[ "${DRY_RUN}" == true ]]; then
                    echo "  would rsync redhat/${rhel}/${arch}/*.rpm -> ${repo_path}/"
                else
                    rsync -aHv "redhat/${rhel}/${arch}/"*.rpm "${repo_path}/"
                fi
            fi

            if [[ "${DRY_RUN}" == true ]]; then
                echo "  would createrepo --update ${createrepo_opts} ${repo_path}"
                echo "  would gpg-sign ${repo_path}/repodata/repomd.xml"
                continue
            fi

            createrepo --update ${createrepo_opts} "${repo_path}"
            if [[ -f "${repo_path}/repodata/repomd.xml.asc" ]]; then
                rm -f "${repo_path}/repodata/repomd.xml.asc"
            fi
            gpg --detach-sign --armor --passphrase "${SIGN_PASSWORD}" "${repo_path}/repodata/repomd.xml"
        done

        mkdir -p "${rpm_dest_path}/SRPMS"
        if [[ -d "../source/redhat" ]] && find ../source/redhat -name '*.src.rpm' | grep -q .; then
            if [[ "${DRY_RUN}" == true ]]; then
                echo "  would copy source RPMs -> ${rpm_dest_path}/SRPMS"
            else
                cp -v "$(find ../source/redhat -name '*.src.rpm')" "${rpm_dest_path}/SRPMS/"
            fi
        fi

        if [[ "${DRY_RUN}" == true ]]; then
            echo "  would createrepo --update ${createrepo_opts} ${rpm_dest_path}/SRPMS"
            echo "  would gpg-sign ${rpm_dest_path}/SRPMS/repodata/repomd.xml"
            continue
        fi

        createrepo --update ${createrepo_opts} "${rpm_dest_path}/SRPMS"
        if [[ -f "${rpm_dest_path}/SRPMS/repodata/repomd.xml.asc" ]]; then
            rm -f "${rpm_dest_path}/SRPMS/repodata/repomd.xml.asc"
        fi
        gpg --detach-sign --armor --passphrase "${SIGN_PASSWORD}" "${rpm_dest_path}/SRPMS/repodata/repomd.xml"
    done
  popd >/dev/null

    if [[ "${production_destination}" == "main" ]]; then
        production_destination="release"
    fi

    if [[ "${SYNC_PROD}" == false ]]; then
        return
    fi

    echo "Syncing ${REPO_NAME}/yum/${production_destination}/ to production..."
    if [[ "${DRY_RUN}" == true ]]; then
        echo "  would rsync /srv/repo-copy/${REPO_NAME}/yum/${production_destination}/ -> 10.30.9.32:/www/repo.percona.com/htdocs/${REPO_NAME}/yum/${production_destination}/"
        echo "  would rsync /srv/repo-copy/version -> 10.30.9.32:/www/repo.percona.com/htdocs/"
        return
    fi

    date +%s > /srv/repo-copy/version
    rsync -avt --bwlimit=50000 --delete --progress \
        --exclude=.nfs* --exclude=rsync-* --exclude=*.bak \
        "/srv/repo-copy/${REPO_NAME}/yum/${production_destination}/" \
        "10.30.9.32:/www/repo.percona.com/htdocs/${REPO_NAME}/yum/${production_destination}/"
    rsync -avt --bwlimit=50000 --delete --progress \
        --exclude=.nfs* --exclude=rsync-* --exclude=*.bak \
        /srv/repo-copy/version \
        10.30.9.32:/www/repo.percona.com/htdocs/
}

main() {
    parse_arguments "$@"

    echo "Upload path : ${UPLOAD_PATH}"
    echo "Repository  : ${REPO_NAME}"
    echo "Component   : ${COMPONENT}"
    echo "Sync prod   : ${SYNC_PROD}"
    echo "Dry run     : ${DRY_RUN}"
    echo

    sign_rpms
    push_to_repo

    echo "Done."
}

main "$@"
