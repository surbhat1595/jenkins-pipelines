#!/bin/bash

cd ppg || { echo "Error: ppg directory not found."; exit 1; }

cat << 'EOF' > replace_parameters.sed
/^([[:space:]]*)parameters[[:space:]]*\{/ {
s//\1parameters {\
\1    choice(\
\1         choices: [ 'Hetzner','AWS' ],\
\1         description: 'Cloud infra for build',\
\1         name: 'CLOUD' )/
}
EOF

for file in $(find . -type f -name "pg_percona_telemetry_autobuild_arm.yml" | grep -vE 'param|template'); do
  if grep -qi "name:.*RELEASE" "$file"; then
    GR_FILE=$(grep "script-path" "$file" | awk '{print $NF}' | sed 's|ppg/||')

    echo "Processing YAML: $file -> $GR_FILE"

    if [[ "$GR_FILE" == *groovy* ]]; then

      GROOVY_FILE="./$GR_FILE"  # Ensure correct path
      if [[ -f "$GROOVY_FILE" ]]; then
        echo "Processing Groovy: $GROOVY_FILE"
	sed -i '' "s:lib@master:lib@hetzner:" "$GROOVY_FILE"
        sed -i '' -e 's:pushArtifactFolder(:pushArtifactFolder(params.CLOUD, :g' \
                  -e 's:uploadTarballfromAWS(:uploadTarballfromAWS(params.CLOUD, :g' \
                  -e 's:uploadRPMfromAWS(:uploadRPMfromAWS(params.CLOUD, :g' \
                  -e 's:popArtifactFolder(:popArtifactFolder(params.CLOUD, :g' \
                  -e 's:sync2ProdAutoBuild(:sync2ProdAutoBuild(params.CLOUD, :g' \
                  -e 's:signRPM(:signRPM(params.CLOUD:g' \
                  -e 's:signDEB(:signDEB(params.CLOUD:g' \
                  -e 's:uploadPGTarballfromAWS(:uploadPGTarballfromAWS(params.CLOUD, :g' \
                  -e 's:uploadPGTarballToDownloadsTesting(:uploadPGTarballToDownloadsTesting(params.CLOUD, :g' \
		  -e 's:uploadDEBfromAWS(:uploadDEBfromAWS(params.CLOUD, :g' \
                  "$GROOVY_FILE"
	sed -i '' -E \
      		-e "s/label '(master|micro-amazon)'/label params.CLOUD == 'Hetzner' ? 'launcher-x64' : '\1'/g" \
      		-e "s/label 'docker'/label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'/g" \
      		-e "s/label '(docker-32gb|docker-64gb)'/label params.CLOUD == 'Hetzner' ? 'docker-x64' : '\1'/g" \
      		-e "s/label '([^']*aarch64[^']*)'/label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : '\1'/g" \
      		"$GROOVY_FILE"
      fi
    fi

    sed -i '' -E -f replace_parameters.sed "$file"
  fi
done

for file in $(find . -type f -name "pg_percona_telemetry_autobuild_arm.yml" | grep -vE 'param|template'); do
  NAME=$(grep -i "name:" "$file")

  if [ -n "$NAME" ]; then
    echo "Modifying YAML: $file"

    sed -i '' "s/\(name: \)\"*\(.*\)\"*/\1hetzner-\2/" "$file"

    sed -i '' "s/ARM_pg/hetzner/" "$file"
  fi
done

rm -f replace_parameters.sed

echo "Processing complete."

