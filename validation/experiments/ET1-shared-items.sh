#!/bin/bash
# ET1: acceptance experiment for Target 1 (shared item configuration).
# Two controllers with ephemeral (tmpfs) homes, identical JCasC and seeded keys (S0), sharing item
# configuration through the Postgres persistence store. Exits non-zero if any check fails.
#
# Prerequisite: experiments/ET1-build.sh has populated validation/.et1/{war,plugins}.
source "$(dirname "$0")/../lib.sh"
# the scratch repos are throwaway; never start fsmonitor daemons for them (they can hang in the background)
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.fsmonitor GIT_CONFIG_VALUE_0=false
A=8081 B=8082 JOBS="$VALIDATION_DIR/scenarios/jobs" OUT="$RAW_DIR/ET1" ET1="$VALIDATION_DIR/.et1"
et1() { docker compose --project-directory "$VALIDATION_DIR" -f "$VALIDATION_DIR/compose.et1.yaml" "$@"; }
rm -rf "$OUT"; mkdir -p "$OUT" "$OUT_DIR"
: > "$OUT/results.md"
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
FAILED=0
check() { # check <description> <command...>
  local d=$1; shift
  if "$@" >>"$OUT/check.log" 2>&1; then r "| ✅ | $d |"; else r "| ❌ | $d |"; FAILED=$((FAILED+1)); fi
}
eventually() { # eventually <seconds> <command...>
  local t=$1 start=$SECONDS; shift
  until "$@"; do (( SECONDS - start > t )) && return 1; sleep 1; done
}
http() { curl -gs -o /dev/null -w '%{http_code}' -u "$AUTH" "http://localhost:$1$2"; }
exists() { [[ "$(http "$1" "$2/api/json")" == 200 ]]; }
absent() { [[ "$(http "$1" "$2/api/json")" == 404 ]]; }
json() { curl -gsf -u "$AUTH" "http://localhost:$1$2"; }
idle() { [[ "$(json "$1" "/computer/api/json?tree=busyExecutors" | python3 -c 'import json,sys; print(json.load(sys.stdin)["busyExecutors"])')" == 0 && \
          "$(json "$1" "/queue/api/json?tree=items[id]" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["items"]))')" == 0 ]]; }
wait_idle() { sleep 3; eventually 300 idle "$1"; }
branches() { json "$1" "/job/mb/api/json?tree=jobs[name]" | python3 -c 'import json,sys; print(" ".join(sorted(j["name"] for j in json.load(sys.stdin)["jobs"])))'; }
has_branches() { [[ "$(branches "$1")" == "$2" ]]; }
conflicts() { groovy "$1" 'print(jenkins.persistence.PersistenceStores.getConflictCount())'; }
store_versions() { et1 exec -T postgres psql -U jenkins -At -c "select key, version from jenkins_store order by key"; }
psql_q() { et1 exec -T postgres psql -U jenkins -At -c "$1"; }

[[ -f "$ET1/war/jenkins.war" && -n "$(ls "$ET1"/plugins/*.hpi 2>/dev/null)" ]] || { echo "run experiments/ET1-build.sh first" >&2; exit 2; }

# ------------------------------------------------------------------ setup
et1 down -v --remove-orphans >/dev/null 2>&1 || true
rm -rf "$ET1/seed" "$ET1/repo"; mkdir -p "$ET1/seed" "$ET1/repo"

log "S0: seeding key material from a throwaway controller"
docker rm -f et1-seed >/dev/null 2>&1 || true
docker run -d --name et1-seed -p 8089:8080 --tmpfs /var/jenkins_home:uid=1000,gid=1000 \
  -e JAVA_OPTS="-Djenkins.install.runSetupWizard=false" -e CASC_JENKINS_CONFIG=/casc -v "$VALIDATION_DIR/jenkins/casc-et1:/casc:ro" \
  -v "$ET1/war:/war-override:ro" -v "$ET1/plugins:/extra-plugins:ro" cnj/jenkins:dev >/dev/null
wait_up 8089
groovy 8089 'hudson.util.Secret.fromString("x").getEncryptedValue(); org.jenkinsci.main.modules.instance_identity.InstanceIdentity.get(); jenkins.model.Jenkins.get().getCrumbIssuer()' >/dev/null
# docker cp cannot read tmpfs mounts
docker exec et1-seed tar -C /var/jenkins_home -cf - secret.key secret.key.not-so-secret identity.key.enc secrets | tar -C "$ET1/seed" -xf -
docker rm -f et1-seed >/dev/null

log "shared git repository for the multibranch scenario"
(
  cd "$ET1/repo" && git init -q -b main && git config user.email et1@example.com && git config user.name et1
  cat > Jenkinsfile <<'JF'
properties([
  parameters([string(name: "GREETING", defaultValue: "hi")]),
  buildDiscarder(logRotator(numToKeepStr: "5")),
  pipelineTriggers([cron("H H * * *")])
])
echo "branch ${env.BRANCH_NAME} says ${params.GREETING}"
JF
  git add Jenkinsfile && git commit -qm init && git branch feature
)
sed 's#file:///tmp/repo#file:///repo#' "$JOBS/multibranch.xml" > "$OUT_DIR/multibranch.xml"

et1 up -d postgres >/dev/null 2>&1; wait_postgres
et1 up -d jenkins-a jenkins-b >/dev/null 2>&1
wait_up $A; wait_up $B
r "## ET1 results ($(date -u +%Y-%m-%dT%H:%MZ))"
r ""
r "| | Check |"
r "|---|---|"
check "store plugin active on A and B" bash -c "[[ \$(source $VALIDATION_DIR/lib.sh; groovy $A 'print(jenkins.persistence.PersistenceStores.get()?.getClass()?.simpleName)') == PostgresPersistenceStore ]]"
check "A and B decrypt each other's secrets (S0 seed)" bash -c "source $VALIDATION_DIR/lib.sh; e=\$(groovy $A 'print(hudson.util.Secret.fromString(\"et1\").getEncryptedValue())'); [[ \$(groovy $B \"print(hudson.util.Secret.decrypt('\$e')?.plainText)\") == et1 ]]"

# ------------------------------------------------------------------ 1. admin lifecycle
log "scenario 1: admin item lifecycle"
create_job $A fs1 "$JOBS/freestyle.xml"
check "1a freestyle created on A appears on B" eventually 10 exists $B /job/fs1
printf '<?xml version="1.1" encoding="UTF-8"?><com.cloudbees.hudson.plugins.folder.Folder plugin="cloudbees-folder"/>' > "$OUT_DIR/folder.xml"
create_job $A f1 "$OUT_DIR/folder.xml"
post $A "/job/f1/createItem?name=inner" -H 'Content-Type: application/xml' --data-binary "@$JOBS/freestyle.xml" >/dev/null
check "1b folder + child created on A appear on B" eventually 10 exists $B /job/f1/job/inner
json $B /job/fs1/config.xml | sed 's#<description>[^<]*</description>#<description>edited on B</description>#' > "$OUT_DIR/fs1-edited.xml"
post $B /job/fs1/config.xml -H 'Content-Type: application/xml' --data-binary "@$OUT_DIR/fs1-edited.xml" >/dev/null
check "1c config edited on B is seen on A" eventually 10 bash -c "curl -gsf -u $AUTH 'http://localhost:$A/job/fs1/api/json?tree=description' | grep -q 'edited on B'"
post $A "/job/f1/confirmRename?newName=f2" >/dev/null || true
check "1d folder renamed on A: new path on B" eventually 10 exists $B /job/f2/job/inner
check "1d folder renamed on A: old path gone on B" eventually 10 absent $B /job/f1
post $B /job/fs1/doDelete >/dev/null || true
check "1e job deleted on B is gone on A" eventually 10 absent $A /job/fs1

# ------------------------------------------------------------------ 2. runtime config written by a build
log "scenario 2: properties() in a build"
python3 - "$OUT_DIR/props.xml" <<'PY'
import html, sys
script = '''properties([
  parameters([string(name: "GREETING", defaultValue: "hi")]),
  buildDiscarder(logRotator(numToKeepStr: "5")),
  pipelineTriggers([cron("H H * * *")])
])
echo "props says ${params.GREETING}"'''
open(sys.argv[1], "w").write(f"""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job"><definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps">
<script>{html.escape(script)}</script><sandbox>true</sandbox></definition></flow-definition>""")
PY
create_job $A props "$OUT_DIR/props.xml"
build $A props >/dev/null; wait_build $A props 1 >/dev/null
check "2a parameter defined by a build on A is seen on B" eventually 15 bash -c "curl -gsf -u $AUTH 'http://localhost:$B/job/props/api/json?tree=property[parameterDefinitions[name]]' | grep -q GREETING"
check "2b cron trigger defined by a build on A is in B's config" bash -c "curl -gsf -u $AUTH http://localhost:$B/job/props/config.xml | grep -q 'H H \* \* \*'"
et1 exec -T jenkins-a sh -c 'find /var/jenkins_home/jobs -name config.xml -exec ls -l --full-time {} +' > "$OUT/local-configs.txt" 2>&1
local_configs=$(et1 exec -T jenkins-a sh -c 'find /var/jenkins_home/jobs -name config.xml | wc -l' | tr -d ' \r')
check "2c no item config.xml written to A's local JENKINS_HOME (found: $local_configs)" test "$local_configs" = 0

# ------------------------------------------------------------------ 3. multibranch
log "scenario 3: multibranch indexing"
create_job $A mb "$OUT_DIR/multibranch.xml"
post $A /job/mb/build >/dev/null; wait_idle $A
check "3a branches indexed on A appear on B" eventually 20 has_branches $B "feature main"
(cd "$ET1/repo" && git branch feature2)
post $A /job/mb/build >/dev/null; wait_idle $A
check "3b branch added and indexed on A appears on B" eventually 20 has_branches $B "feature feature2 main"
(cd "$ET1/repo" && git branch -qD feature)
post $A /job/mb/build >/dev/null; wait_idle $A
check "3c branch deleted and pruned on A is pruned on B" eventually 20 has_branches $B "feature2 main"
post $B /job/mb/build >/dev/null; wait_idle $B; sleep 5
check "3d indexing on B: B still has exactly the branches" has_branches $B "feature2 main"
check "3d indexing on B: A still has exactly the branches" eventually 15 has_branches $A "feature2 main"

# ------------------------------------------------------------------ 4. concurrent writers
log "scenario 4: indexing on A while builds with properties() run on B"
ca0=$(conflicts $A); cb0=$(conflicts $B)
end=$((SECONDS + 120))
while (( SECONDS < end )); do
  post $A /job/mb/build >/dev/null 2>&1 || true
  post $B "/job/mb/job/main/buildWithParameters?GREETING=fromB" >/dev/null 2>&1 || true
  post $B "/job/mb/job/feature2/buildWithParameters?GREETING=fromB" >/dev/null 2>&1 || true
  sleep 4
done
wait_idle $A; wait_idle $B; sleep 5
ca=$(( $(conflicts $A) - ca0 )); cb=$(( $(conflicts $B) - cb0 ))
r "| ℹ️ | conflicts detected during scenario 4: A=$ca B=$cb (warn policy: logged, last writer wins) |"
for br in main feature2; do
  check "4 mb/$br keeps its properties() parameter in the store" bash -c "[[ \$(cd $VALIDATION_DIR && docker compose -f compose.et1.yaml exec -T postgres psql -U jenkins -At -c \"select convert_from(content,'UTF8') from jenkins_store where key='jobs/mb/branches/$br/config.xml'\" | grep -c ParametersDefinitionProperty) -ge 1 ]]"
  check "4 mb/$br shows the parameter on A and B" bash -c "curl -gsf -u $AUTH 'http://localhost:$A/job/mb/job/$br/api/json?tree=property[parameterDefinitions[name]]' | grep -q GREETING && curl -gsf -u $AUTH 'http://localhost:$B/job/mb/job/$br/api/json?tree=property[parameterDefinitions[name]]' | grep -q GREETING"
done

# ------------------------------------------------------------------ 4b. genuinely conflicting writers
log "scenario 4b: builds of one job on A and B rewrite its config with different values"
python3 - "$OUT_DIR/race.xml" <<'PY'
import html, sys
script = 'properties([parameters([string(name: "V", defaultValue: params.V ?: "0")])])\necho "V=${params.V}"'
open(sys.argv[1], "w").write("<?xml version='1.1' encoding='UTF-8'?>\n<flow-definition plugin=\"workflow-job\"><definition class=\"org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition\" plugin=\"workflow-cps\">\n<script>" + html.escape(script) + "</script><sandbox>true</sandbox></definition></flow-definition>")
PY
create_job $A race "$OUT_DIR/race.xml"
build $A race >/dev/null; wait_build $A race 1 >/dev/null
eventually 15 exists $B /job/race
ca0=$(conflicts $A); cb0=$(conflicts $B); v0=$(psql_q "select version from jenkins_store where key='jobs/race/config.xml'")
for i in $(seq 1 40); do
  post $A "/job/race/buildWithParameters?V=a$i" >/dev/null 2>&1 || true
  post $B "/job/race/buildWithParameters?V=b$i" >/dev/null 2>&1 || true
  sleep 0.5
done
wait_idle $A; wait_idle $B; sleep 5
ca=$(( $(conflicts $A) - ca0 )); cb=$(( $(conflicts $B) - cb0 ))
v1=$(psql_q "select version from jenkins_store where key='jobs/race/config.xml'")
r "| ℹ️ | scenario 4b: 80 builds rewrote jobs/race/config.xml; store version $v0 → $v1; conflicts detected A=$ca B=$cb |"
default_of() { grep -o '<defaultValue>[^<]*' | sed 's/<defaultValue>//' | tr -d '\r\n'; }
stored_default=$(psql_q "select convert_from(content,'UTF8') from jenkins_store where key='jobs/race/config.xml'" | default_of)
a_default=$(json $A /job/race/config.xml | default_of)
b_default=$(json $B /job/race/config.xml | default_of)
r "| ℹ️ | scenario 4b: final default parameter value: store=$stored_default A=$a_default B=$b_default |"
check "4b A, B and the store converge on the same job config" test "$stored_default" = "$a_default" -a "$a_default" = "$b_default"
writes=$(psql_q "select count(*) from jenkins_store_events where key='jobs/race/config.xml' and kind='WRITTEN' and version > $v0")
r "| ℹ️ | scenario 4b: $writes writes in the event log; the store holds the last one. Builds reach properties() out of trigger order, so it need not be a40/b40; a stale overwrite would have shown as a conflict |"

# ------------------------------------------------------------------ 6. write amplification (before 5, while A and B are settled)
log "scenario 6: re-index with no changes"
# once first: resolving a permalink nothing asked for before caches it (Target 15: in the store, as a single controller
# writes its permalinks file); what must not happen is writes on every re-index
post $A /job/mb/build >/dev/null; wait_idle $A; sleep 3
before=$(store_versions)
post $A /job/mb/build >/dev/null; wait_idle $A; sleep 3
after=$(store_versions)
diff <(echo "$before") <(echo "$after") > "$OUT/reindex-version-diff.txt" || true
check "6 re-index with no SCM changes bumps no stored versions ($(grep -c '^>' "$OUT/reindex-version-diff.txt") changed)" test ! -s "$OUT/reindex-version-diff.txt"

# ------------------------------------------------------------------ 5. disposable controller
log "scenario 5: destroy B (tmpfs home) and start a fresh one"
et1 rm -sf jenkins-b >/dev/null 2>&1
et1 up -d jenkins-b >/dev/null 2>&1; wait_up $B
for path in /job/f2/job/inner /job/props /job/mb/job/main /job/mb/job/feature2; do
  check "5 fresh B loads $path from the store" eventually 30 exists $B $path
done
check "5 fresh B does not resurrect deleted items" absent $B /job/fs1

# ------------------------------------------------------------------ evidence
psql_q "select key, version, updated_by from jenkins_store order by key" > "$OUT/store.txt"
psql_q "select kind, count(*) from jenkins_store_events group by kind order by kind" > "$OUT/events.txt"
et1 logs -t --no-color jenkins-a > "$OUT/jenkins-a.log" 2>&1
et1 logs -t --no-color jenkins-b > "$OUT/jenkins-b.log" 2>&1
r ""
r "Failures: $FAILED"
exit $FAILED
