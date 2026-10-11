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
MVN="mvn -B -ntp -q -Dspotbugs.skip -Dcheckstyle.skip -Denforcer.skip -Dlicense.skip=true -Dspotless.check.skip -Dspotless.skip=true -Dspotless.apply.skip=true -Djacoco.skip -DskipTests"
mkdir -p "$ET1/war" "$ET1/plugins"

# Target 6: Stapler with prefixed bound object ids, which core is built against (skipped if the worktree is absent)
STAPLER=${STAPLER:-/Users/timja/code/jenkins/stapler-cnj}
STAPLER_REF=${STAPLER_REF:-cnj/t6-m5-bound-id-prefix}
if [[ -d "$STAPLER" ]]; then
  echo "stapler $STAPLER_REF ($(git -C "$STAPLER" rev-parse --short "$STAPLER_REF"))"
  git -C "$STAPLER" checkout -q "$STAPLER_REF"
  (cd "$STAPLER" && $MVN install)
fi

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

# Target 4: workflow-cps with ProgramSavedListener, needed by the store plugin (skipped if the worktree is absent)
WFCPS=${WFCPS:-/Users/timja/code/jenkins/workflow-cps-plugin-cnj}
WFCPS_REF=${WFCPS_REF:-cnj/t4-m1-program-saved-listener}
if [[ -d "$WFCPS" ]]; then
  echo "workflow-cps $WFCPS_REF ($(git -C "$WFCPS" rev-parse --short "$WFCPS_REF"))"
  git -C "$WFCPS" checkout -q "$WFCPS_REF"
  (cd "$WFCPS" && $MVN -pl dgm-builder,lib,plugin install)
  cp "$WFCPS/plugin/target/workflow-cps.hpi" "$ET1/plugins/"
fi

# Target 5: lockable-resources with cluster locks (skipped if the checkout is absent)
LR=${LR:-/Users/timja/code/jenkins/lockable-resources-plugin}
LR_REF=${LR_REF:-cnj/t15-cluster-ui}
if [[ -d "$LR" ]]; then
  echo "lockable-resources $LR_REF ($(git -C "$LR" rev-parse --short "$LR_REF"))"
  git -C "$LR" checkout -q "$LR_REF"
  (cd "$LR" && $MVN package)
  cp "$LR/target/lockable-resources.hpi" "$ET1/plugins/"
fi

# Target 6: credentials with reload of credentials changed elsewhere (skipped if the worktree is absent)
CREDS=${CREDS:-/Users/timja/code/jenkins/credentials-plugin-cnj}
CREDS_REF=${CREDS_REF:-cnj/t6-m4-credentials-reload}
if [[ -d "$CREDS" ]]; then
  echo "credentials $CREDS_REF ($(git -C "$CREDS" rev-parse --short "$CREDS_REF"))"
  git -C "$CREDS" checkout -q "$CREDS_REF"
  (cd "$CREDS" && $MVN package)
  cp "$CREDS/target/credentials.hpi" "$ET1/plugins/"
fi

# Target 15: pipeline-build-step with downstream outcomes delivered across controllers (skipped if the worktree is absent)
PBS=${PBS:-/Users/timja/code/jenkins/pipeline-build-step-plugin-cnj}
PBS_REF=${PBS_REF:-cnj/t15-remote-completion}
if [[ -d "$PBS" ]]; then
  echo "pipeline-build-step $PBS_REF ($(git -C "$PBS" rev-parse --short "$PBS_REF"))"
  git -C "$PBS" checkout -q "$PBS_REF"
  (cd "$PBS" && $MVN package)
  cp "$PBS/target/pipeline-build-step.hpi" "$ET1/plugins/"
fi

# Target 15: junit-sql-storage whose results follow renamed jobs (skipped if the worktree is absent)
JSQL=${JSQL:-/Users/timja/code/jenkins/junit-sql-storage-plugin-cnj}
JSQL_REF=${JSQL_REF:-cnj/t15-follow-renames}
if [[ -d "$JSQL" ]]; then
  echo "junit-sql-storage $JSQL_REF ($(git -C "$JSQL" rev-parse --short "$JSQL_REF"))"
  git -C "$JSQL" checkout -q "$JSQL_REF"
  (cd "$JSQL" && $MVN package)
  cp "$JSQL/target/junit-sql-storage.hpi" "$ET1/plugins/"
fi

# Target 15: configuration-as-code with keepUndeclaredNodes (skipped if the worktree is absent)
CASC=${CASC:-/Users/timja/code/jenkins/configuration-as-code-plugin-cnj}
CASC_REF=${CASC_REF:-cnj/t15-keep-undeclared-nodes}
if [[ -d "$CASC" ]]; then
  echo "configuration-as-code $CASC_REF ($(git -C "$CASC" rev-parse --short "$CASC_REF"))"
  git -C "$CASC" checkout -q "$CASC_REF"
  (cd "$CASC" && $MVN -pl plugin -am package)
  cp "$CASC/plugin/target/configuration-as-code.hpi" "$ET1/plugins/"
fi

echo "persistence-store-postgresql"
(cd "$HERE/../impl/persistence-store-postgresql" && $MVN package)
cp "$HERE/../impl/persistence-store-postgresql/target/persistence-store-postgresql.hpi" "$ET1/plugins/"

{ echo "core $CORE_REF $(git -C "$CORE" rev-parse "$CORE_REF")"
  echo "cloudbees-folder $FOLDER_REF $(git -C "$FOLDER" rev-parse "$FOLDER_REF")"
  [[ -d "$WFCPS" ]] && echo "workflow-cps $WFCPS_REF $(git -C "$WFCPS" rev-parse "$WFCPS_REF")"
  [[ -d "$LR" ]] && echo "lockable-resources $LR_REF $(git -C "$LR" rev-parse "$LR_REF")"
  [[ -d "$STAPLER" ]] && echo "stapler $STAPLER_REF $(git -C "$STAPLER" rev-parse "$STAPLER_REF")"
  [[ -d "$CREDS" ]] && echo "credentials $CREDS_REF $(git -C "$CREDS" rev-parse "$CREDS_REF")"
  [[ -d "$PBS" ]] && echo "pipeline-build-step $PBS_REF $(git -C "$PBS" rev-parse "$PBS_REF")"
  [[ -d "$JSQL" ]] && echo "junit-sql-storage $JSQL_REF $(git -C "$JSQL" rev-parse "$JSQL_REF")"
  [[ -d "$CASC" ]] && echo "configuration-as-code $CASC_REF $(git -C "$CASC" rev-parse "$CASC_REF")"
  echo "cloud-native-jenkins $(git -C "$HERE" rev-parse HEAD)"; } > "$ET1/BUILD"
cat "$ET1/BUILD"; ls -la "$ET1/war" "$ET1/plugins"

echo "harness image (entrypoint installs .et1/plugins and the S0 seed)"
docker build -q -t cnj/jenkins:dev "$HERE/jenkins"
