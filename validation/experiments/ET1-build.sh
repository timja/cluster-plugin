#!/bin/bash
# Builds what ET1 runs, from local branches (nothing is fetched or pushed), into validation/.et1/:
#   war/jenkins.war                    core at $CORE_REF            (default: cnj/t1-integration, else top of the core stack)
#   plugins/cloudbees-folder.hpi       folder plugin at $FOLDER_REF (default: cnj/t1-m2-folder-children)
#   plugins/persistence-store-postgresql.hpi   from impl/ in this checkout
# Core is built in a separate worktree so the main checkout can keep working.
set -euo pipefail
# fsmonitor daemons can hang git in non-interactive runs
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.fsmonitor GIT_CONFIG_VALUE_0=false
HERE="$(cd "$(dirname "$0")/.." && pwd)"; ET1="$HERE/.et1"
CORE=${CORE:-/Users/timja/code/jenkins/jenkins}
CORE_WT=${CORE_WT:-/Users/timja/code/jenkins/jenkins-cnj-test}
FOLDER=${FOLDER:-/Users/timja/code/jenkins/cloudbees-folder-plugin}
CORE_REF=${CORE_REF:-$(git -C "$CORE" rev-parse -q --verify cnj/t1-integration >/dev/null && echo cnj/t1-integration || echo cnj/t1-m4-item-change-applier)}
FOLDER_REF=${FOLDER_REF:-cnj/t1-m2-folder-children}
MVN="mvn -B -ntp -q -Dspotbugs.skip -Dcheckstyle.skip -Denforcer.skip -Dlicense.skip=true -Dspotless.check.skip -Djacoco.skip -DskipTests"
mkdir -p "$ET1/war" "$ET1/plugins"

echo "core $CORE_REF ($(git -C "$CORE" rev-parse --short "$CORE_REF"))"
[[ -d "$CORE_WT" ]] || git -C "$CORE" worktree add -q --detach "$CORE_WT" "$CORE_REF"
git -C "$CORE_WT" checkout -q --detach "$CORE_REF"
(cd "$CORE_WT" && $MVN -pl bom,war -am install)
cp "$CORE_WT/war/target/jenkins.war" "$ET1/war/jenkins.war"

echo "cloudbees-folder $FOLDER_REF ($(git -C "$FOLDER" rev-parse --short "$FOLDER_REF"))"
FOLDER_WT="$FOLDER-cnj-build"
[[ -d "$FOLDER_WT" ]] || git -C "$FOLDER" worktree add -q --detach "$FOLDER_WT" "$FOLDER_REF"
git -C "$FOLDER_WT" checkout -q --detach "$FOLDER_REF"
(cd "$FOLDER_WT" && $MVN package)
cp "$FOLDER_WT/target/cloudbees-folder.hpi" "$ET1/plugins/"

echo "persistence-store-postgresql"
(cd "$HERE/../impl/persistence-store-postgresql" && $MVN package)
cp "$HERE/../impl/persistence-store-postgresql/target/persistence-store-postgresql.hpi" "$ET1/plugins/"

{ echo "core $CORE_REF $(git -C "$CORE" rev-parse "$CORE_REF")"
  echo "cloudbees-folder $FOLDER_REF $(git -C "$FOLDER" rev-parse "$FOLDER_REF")"
  echo "cloud-native-jenkins $(git -C "$HERE" rev-parse HEAD)"; } > "$ET1/BUILD"
cat "$ET1/BUILD"; ls -la "$ET1/war" "$ET1/plugins"

echo "harness image (entrypoint installs .et1/plugins and the S0 seed)"
docker build -q -t cnj/jenkins:dev "$HERE/jenkins"
