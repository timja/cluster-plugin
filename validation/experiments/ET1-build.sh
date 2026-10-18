#!/bin/bash
# Builds what ET1 runs, from local branches (nothing is fetched or pushed), into validation/.et1/:
#   war/jenkins.war                    core at $CORE_REF            (default: cnj2/all), as 2.586$CORE_CHANGELIST
#   plugins/cloudbees-folder.hpi       folder plugin at $FOLDER_REF (default: cnj/t15-remote-child)
#   plugins/cluster*.hpi               the Cluster plugins, from this checkout (Postgres + Azure Blob Storage)
#   plugins-s3/                        the same plugin set with cluster-s3 instead of cluster-azure-blob (compose.s3.yaml)
# Core is built in a separate worktree so the main checkout can keep working. A checkout already at the wanted commit is
# built as it is (its branch stays checked out). Only distinct versions are installed into the local Maven repository:
# core as 2.586$CORE_CHANGELIST, Stapler as $STAPLER_VERSION; the plugins are only packaged. MVN_BIN runs Maven (e.g. a
# wrapper that waits for a free build slot).
set -euo pipefail
# fsmonitor daemons can hang git in non-interactive runs
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.fsmonitor GIT_CONFIG_VALUE_0=false
HERE="$(cd "$(dirname "$0")/.." && pwd)"; ET1="$HERE/.et1"
CORE=${CORE:-/Users/timja/code/jenkins/jenkins-cnj}
CORE_WT=${CORE_WT:-$CORE}
FOLDER=${FOLDER:-/Users/timja/code/jenkins/cloudbees-folder-plugin}
CORE_REF=${CORE_REF:-cnj2/all}
CORE_CHANGELIST=${CORE_CHANGELIST:--cnj2-all-SNAPSHOT}
FOLDER_REF=${FOLDER_REF:-cnj/t15-remote-child}
# the version lockable-resources and pipeline-build-step are packaged as: at least the one the cluster plugin is built
# against (cluster/pom.xml), and newer than the released versions other plugins need
EXT_CHANGELIST=${EXT_CHANGELIST:-999999-cnj2-SNAPSHOT}
MVN="${MVN_BIN:-mvn} -B -ntp -q -Dspotbugs.skip -Dcheckstyle.skip -Denforcer.skip -Dlicense.skip=true -Dspotless.check.skip -Dspotless.skip=true -Dspotless.apply.skip=true -Djacoco.skip -DskipTests"
mkdir -p "$ET1/war" "$ET1/plugins"
# plugins built against this core (their own jenkins.version is a core snapshot without the APIs they use); their tests
# are not compiled, as some use core test classes
ON_CORE="-Djenkins.version=2.586$CORE_CHANGELIST -Dmaven.test.skip=true"

# at_ref <dir> <ref>: checks out ref (detached) unless dir is already at that commit
at_ref() { [[ "$(git -C "$1" rev-parse HEAD)" == "$(git -C "$1" rev-parse "$2^{commit}")" ]] || git -C "$1" checkout -q --detach "$2"; }

# Target 6: Stapler with prefixed bound object ids, which core is built against (skipped if the worktree is absent)
STAPLER=${STAPLER:-/Users/timja/code/jenkins/stapler-cnj}
STAPLER_REF=${STAPLER_REF:-cnj/t6-m5-bound-id-prefix}
STAPLER_VERSION=${STAPLER_VERSION:-999999-cnj2-bound-id-SNAPSHOT}
CORE_STAPLER=()
if [[ -d "$STAPLER" ]]; then
  echo "stapler $STAPLER_REF ($(git -C "$STAPLER" rev-parse --short "$STAPLER_REF"))"
  at_ref "$STAPLER" "$STAPLER_REF"
  (cd "$STAPLER" && $MVN -Dchangelist="$STAPLER_VERSION" install)
  CORE_STAPLER=(-Dstapler.version="$STAPLER_VERSION")
fi

echo "core $CORE_REF ($(git -C "$CORE" rev-parse --short "$CORE_REF"))"
[[ -d "$CORE_WT" ]] || git -C "$CORE" worktree add -q --detach "$CORE_WT" "$CORE_REF"
at_ref "$CORE_WT" "$CORE_REF"
(cd "$CORE_WT" && $MVN -Dchangelist="$CORE_CHANGELIST" "${CORE_STAPLER[@]}" -pl bom,war -am clean install)
cp "$CORE_WT/war/target/jenkins.war" "$ET1/war/jenkins.war"

echo "cloudbees-folder $FOLDER_REF ($(git -C "$FOLDER" rev-parse --short "$FOLDER_REF"))"
FOLDER_WT="$FOLDER-cnj-build"
[[ -d "$FOLDER_WT" ]] || git -C "$FOLDER" worktree add -q --detach "$FOLDER_WT" "$FOLDER_REF"
at_ref "$FOLDER_WT" "$FOLDER_REF"
(cd "$FOLDER_WT" && $MVN $ON_CORE clean package)
cp "$FOLDER_WT/target/cloudbees-folder.hpi" "$ET1/plugins/"

# Target 4: workflow-cps with ProgramSavedListener, needed by the store plugin (skipped if the worktree is absent)
WFCPS=${WFCPS:-/Users/timja/code/jenkins/workflow-cps-plugin-cnj}
WFCPS_REF=${WFCPS_REF:-cnj/t4-m1-program-saved-listener}
if [[ -d "$WFCPS" ]]; then
  echo "workflow-cps $WFCPS_REF ($(git -C "$WFCPS" rev-parse --short "$WFCPS_REF"))"
  at_ref "$WFCPS" "$WFCPS_REF"
  (cd "$WFCPS" && $MVN -pl dgm-builder,lib,plugin clean package)
  cp "$WFCPS/plugin/target/workflow-cps.hpi" "$ET1/plugins/"
fi

# lockable-resources with the LockCoordinator extension point the cluster plugin implements (skipped if the checkout is absent)
LR=${LR:-/Users/timja/code/jenkins/lockable-resources-cnj2}
LR_REF=${LR_REF:-cnj2/lock-coordinator}
if [[ -d "$LR" ]]; then
  echo "lockable-resources $LR_REF ($(git -C "$LR" rev-parse --short "$LR_REF"))"
  at_ref "$LR" "$LR_REF"
  (cd "$LR" && $MVN -Dchangelist="$EXT_CHANGELIST" clean package)
  cp "$LR/target/lockable-resources.hpi" "$ET1/plugins/"
fi

# Target 6: credentials with reload of credentials changed elsewhere (skipped if the worktree is absent)
CREDS=${CREDS:-/Users/timja/code/jenkins/credentials-plugin-cnj}
CREDS_REF=${CREDS_REF:-cnj/t6-m4-credentials-reload}
if [[ -d "$CREDS" ]]; then
  echo "credentials $CREDS_REF ($(git -C "$CREDS" rev-parse --short "$CREDS_REF"))"
  at_ref "$CREDS" "$CREDS_REF"
  (cd "$CREDS" && $MVN $ON_CORE clean package)
  cp "$CREDS/target/credentials.hpi" "$ET1/plugins/"
fi

# pipeline-build-step with the BuildTriggerRelay extension point the cluster plugin implements (skipped if the checkout is absent)
PBS=${PBS:-/Users/timja/code/jenkins/pipeline-build-step-cnj2}
PBS_REF=${PBS_REF:-cnj2/build-trigger-relay}
if [[ -d "$PBS" ]]; then
  echo "pipeline-build-step $PBS_REF ($(git -C "$PBS" rev-parse --short "$PBS_REF"))"
  at_ref "$PBS" "$PBS_REF"
  (cd "$PBS" && $MVN -Dchangelist="$EXT_CHANGELIST" clean package)
  cp "$PBS/target/pipeline-build-step.hpi" "$ET1/plugins/"
fi

# Target 15: junit-sql-storage whose results follow renamed jobs (skipped if the worktree is absent)
JSQL=${JSQL:-/Users/timja/code/jenkins/junit-sql-storage-plugin-cnj}
JSQL_REF=${JSQL_REF:-cnj/t15-follow-renames}
if [[ -d "$JSQL" ]]; then
  echo "junit-sql-storage $JSQL_REF ($(git -C "$JSQL" rev-parse --short "$JSQL_REF"))"
  at_ref "$JSQL" "$JSQL_REF"
  (cd "$JSQL" && $MVN $ON_CORE clean package)
  cp "$JSQL/target/junit-sql-storage.hpi" "$ET1/plugins/"
fi

# Target 15: branch-api whose indexing takes the revision of a branch's automatic build when none is recorded (an
# indexing on a controller that died after scheduling the build) (skipped if the worktree is absent)
BAPI=${BAPI:-/Users/timja/code/jenkins/branch-api-plugin-cnj}
BAPI_REF=${BAPI_REF:-cnj/t15-first-build-revision}
if [[ -d "$BAPI" ]]; then
  echo "branch-api $BAPI_REF ($(git -C "$BAPI" rev-parse --short "$BAPI_REF"))"
  at_ref "$BAPI" "$BAPI_REF"
  (cd "$BAPI" && $MVN clean package)
  cp "$BAPI/target/branch-api.hpi" "$ET1/plugins/"
fi

# Target 15: configuration-as-code with keepUndeclaredNodes (skipped if the worktree is absent)
CASC=${CASC:-/Users/timja/code/jenkins/configuration-as-code-plugin-cnj}
CASC_REF=${CASC_REF:-cnj/t15-keep-undeclared-nodes}
if [[ -d "$CASC" ]]; then
  echo "configuration-as-code $CASC_REF ($(git -C "$CASC" rev-parse --short "$CASC_REF"))"
  at_ref "$CASC" "$CASC_REF"
  (cd "$CASC" && $MVN -pl plugin -am clean package)
  cp "$CASC/plugin/target/configuration-as-code.hpi" "$ET1/plugins/"
fi

echo "cluster plugins"
(cd "$HERE/.." && $MVN clean package)
rm -f "$ET1"/plugins/persistence-store-postgresql.hpi "$ET1"/plugins/cluster*.hpi
for p in cluster cluster-postgresql cluster-azure-blob; do cp "$HERE/../$p/target/$p.hpi" "$ET1/plugins/"; done
rm -rf "$ET1/plugins-s3"; cp -R "$ET1/plugins" "$ET1/plugins-s3"; rm "$ET1/plugins-s3/cluster-azure-blob.hpi"
cp "$HERE/../cluster-s3/target/cluster-s3.hpi" "$ET1/plugins-s3/"

{ echo "core $CORE_REF $(git -C "$CORE" rev-parse "$CORE_REF")"
  echo "cloudbees-folder $FOLDER_REF $(git -C "$FOLDER" rev-parse "$FOLDER_REF")"
  [[ -d "$WFCPS" ]] && echo "workflow-cps $WFCPS_REF $(git -C "$WFCPS" rev-parse "$WFCPS_REF")"
  [[ -d "$LR" ]] && echo "lockable-resources $LR_REF $(git -C "$LR" rev-parse "$LR_REF")"
  [[ -d "$STAPLER" ]] && echo "stapler $STAPLER_REF $(git -C "$STAPLER" rev-parse "$STAPLER_REF")"
  [[ -d "$CREDS" ]] && echo "credentials $CREDS_REF $(git -C "$CREDS" rev-parse "$CREDS_REF")"
  [[ -d "$PBS" ]] && echo "pipeline-build-step $PBS_REF $(git -C "$PBS" rev-parse "$PBS_REF")"
  [[ -d "$JSQL" ]] && echo "junit-sql-storage $JSQL_REF $(git -C "$JSQL" rev-parse "$JSQL_REF")"
  [[ -d "$BAPI" ]] && echo "branch-api $BAPI_REF $(git -C "$BAPI" rev-parse "$BAPI_REF")"
  [[ -d "$CASC" ]] && echo "configuration-as-code $CASC_REF $(git -C "$CASC" rev-parse "$CASC_REF")"
  echo "cluster-plugin $(git -C "$HERE" rev-parse HEAD)"; } > "$ET1/BUILD"
cat "$ET1/BUILD"; ls -la "$ET1/war" "$ET1/plugins"

echo "harness image (entrypoint installs .et1/plugins and the S0 seed)"
nice docker build -q -t "${DEV_IMAGE:-cnj/jenkins:dev}" "$HERE/jenkins"
