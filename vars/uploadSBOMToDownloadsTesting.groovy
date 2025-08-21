def must(String name, String val) {
  if (!val || !val.trim()) {
    error("Required var '${name}' is missing or empty")
  }
}

def call(String CLOUD_NAME, String PRODUCT_NAME, String PRODUCT_VERSION, String SBOMType) {
    def nodeLabel = (CLOUD_NAME == 'Hetzner') ? 'launcher-x64' : 'master'
    node(nodeLabel) {
        deleteDir()
        unstash "uploadPath-${PRODUCT_VERSION}"
        def path_to_build = sh(returnStdout: true, script: "cat uploadPath-${PRODUCT_VERSION}").trim()

        // (1) Groovy pre-checks (fail early)
        must('path_to_build', path_to_build)
        must('PRODUCT_NAME', PRODUCT_NAME)
        must('PRODUCT_VERSION', PRODUCT_VERSION)
        must('SBOMType', SBOMType)

        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
            // include credential checks too
            must('KEY_PATH', env.KEY_PATH)
            must('USER', env.USER)

            sh """
                #!/usr/bin/env bash
                set -Eeuo pipefail
                set -o xtrace

                # (2) Minimal Bash loop for required vars
                path_to_build='${path_to_build}'
                PRODUCT_NAME='${PRODUCT_NAME}'
                PRODUCT_VERSION='${PRODUCT_VERSION}'
                SBOMType='${SBOMType}'
                KEY_PATH='${KEY_PATH}'
                USER='${USER}'

                required=( path_to_build PRODUCT_NAME PRODUCT_VERSION SBOMType KEY_PATH USER )
                for v in "\${required[@]}"; do
                  val="\${!v:-}"
                  if [[ -z "\$val" ]]; then
                    echo "ERROR: \$v is unset or empty" >&2
                    exit 1
                  fi
                done

                # /etc/hosts entry (idempotent)
                if ! grep -Eq '^10\\.30\\.6\\.9\\s+repo\\.ci\\.percona\\.com(\\s|\$)' /etc/hosts; then
                  echo '10.30.6.9 repo.ci.percona.com' | sudo tee -a /etc/hosts >/dev/null
                fi

                # Cut prefix if it's provided
                cutProductVersion=\$(echo "\$PRODUCT_VERSION" | sed 's/^release-//g')

                # Upload PSMDB SBOMS to tarball testing downloads
                if [[ "\$PRODUCT_NAME" == "psmdb_sbom" ]]; then
                    PSMDB_VERSION=\$(echo "\$cutProductVersion" | cut -d'-' -f1)
                    echo "\$cutProductVersion"
                    echo "\$PSMDB_VERSION"

                    ssh -o StrictHostKeyChecking=no -i "\$KEY_PATH" "\$USER@repo.ci.percona.com" \\
                      ssh -o StrictHostKeyChecking=no -p 2222 jenkins-deploy.jenkins-deploy.web.r.int.percona.com \\
                      "mkdir -p /data/downloads/TESTING/psmdb-\${PSMDB_VERSION}/\${PRODUCT_NAME}-\${cutProductVersion}"

                    ssh -o StrictHostKeyChecking=no -i "\$KEY_PATH" "\$USER@repo.ci.percona.com" \\
                      rsync -avti --dry-run -e "ssh -o StrictHostKeyChecking=no -p 2222" --bwlimit=50000 --progress \\
                      "\${path_to_build%/}/\${SBOMType}/"* \\
                      jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/TESTING/psmdb-\${PSMDB_VERSION}/\${PRODUCT_NAME}-\${cutProductVersion}/
                else
                    ssh -o StrictHostKeyChecking=no -i "\$KEY_PATH" "\$USER@repo.ci.percona.com" \\
                      ssh -o StrictHostKeyChecking=no -p 2222 jenkins-deploy.jenkins-deploy.web.r.int.percona.com \\
                      "mkdir -p /data/downloads/TESTING/\${PRODUCT_NAME}-\${cutProductVersion}"

                    ssh -o StrictHostKeyChecking=no -i "\$KEY_PATH" "\$USER@repo.ci.percona.com" \\
                      rsync -avti --dry-run -e "ssh -o StrictHostKeyChecking=no -p 2222" --bwlimit=50000 --progress \\
                      "\${path_to_build%/}/\${SBOMType}/"* \\
                      jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/TESTING/\${PRODUCT_NAME}-\${cutProductVersion}/
                fi

                curl -sS https://www.percona.com/admin/config/percona/percona_downloads/crawl_directory >/dev/null
            """
        }
        deleteDir()
    }
}
