#!/bin/bash
# ET3: acceptance experiment for Target 3 (shared build records and history).
# Same setup as ET1/ET2: two controllers on tmpfs homes, identical JCasC, seeded keys (S0), Postgres store,
# plus Azurite for build snapshots and Pipeline logs. Exits non-zero if any check fails.
#
# Prerequisite: CORE_REF=cnj/t3-m4-explicit-numbering experiments/ET1-build.sh
source "$(dirname "$0")/../lib.sh"
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.fsmonitor GIT_CONFIG_VALUE_0=false
A=8081 B=8082 OUT="$RAW_DIR/ET3" ET1="$VALIDATION_DIR/.et1"
et1() { docker compose --project-directory "$VALIDATION_DIR" -f "$VALIDATION_DIR/compose.et1.yaml" "$@"; }
rm -rf "$OUT"; mkdir -p "$OUT" "$OUT_DIR"
: > "$OUT/results.md"
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
FAILED=0
check() { local d=$1; shift; if "$@" >>"$OUT/check.log" 2>&1; then r "| ✅ | $d |"; else r "| ❌ | $d |"; FAILED=$((FAILED+1)); fi; }
info() { r "| ℹ️ | $* |"; }
eventually() { local t=$1 start=$SECONDS; shift; until "$@"; do (( SECONDS - start > t )) && return 1; sleep 2; done; }
http() { curl -gs -o /dev/null -w '%{http_code}' -u "$AUTH" "http://localhost:$1$2"; }
exists() { [[ "$(http "$1" "$2/api/json")" == 200 ]]; }
gone() { [[ "$(http "$1" "$2/api/json")" == 404 ]]; }
json() { curl -gsf -u "$AUTH" "http://localhost:$1$2"; }
text() { curl -gsf -u "$AUTH" "http://localhost:$1$2"; }
jq_() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
idle() { [[ "$(json "$1" "/computer/api/json?tree=busyExecutors" | jq_ 'd["busyExecutors"]')" == 0 && \
          "$(json "$1" "/queue/api/json?tree=items[id]" | jq_ 'len(d["items"])')" == 0 ]]; }
wait_idle() { sleep 3; eventually 300 idle "$1"; }
psql_q() { et1 exec -T postgres psql -U jenkins -At -c "$1"; }
numbers() { json "$1" "$2/api/json?tree=builds[number]" | jq_ '" ".join(str(b["number"]) for b in sorted(d["builds"], key=lambda b: b["number"]))'; }
result_of() { json "$1" "$2/api/json?tree=result" | jq_ 'd["result"]'; }
same() { [[ "$1" == "$2" ]]; }
same_text() { cmp -s <(text "$1" "$3") <(text "$2" "$3"); }
permalinks() { json "$1" "$2/api/json?tree=lastBuild[number],lastSuccessfulBuild[number],lastFailedBuild[number]" \
  | jq_ '" ".join(k + "=" + str((d.get(k) or {}).get("number")) for k in ["lastBuild", "lastSuccessfulBuild", "lastFailedBuild"])'; }
placeholders() { json "$1" "$2/api/json?tree=actions[builds[number,controller]]" \
  | jq_ '" ".join(str(b["number"]) + "@" + b["controller"] for a in d["actions"] if a and "builds" in a for b in a["builds"])'; }
has_placeholder() { [[ " $(placeholders "$1" "$2") " == *" $3 "* ]]; }
no_placeholders() { [[ -z "$(placeholders "$1" "$2")" ]]; }
pipeline_job() { # name script
  python3 - "$OUT_DIR/$1.xml" "$2" <<'PY'
import sys
from xml.sax.saxutils import escape
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job"><properties><hudson.model.ParametersDefinitionProperty><parameterDefinitions>
<hudson.model.StringParameterDefinition><name>ID</name><defaultValue>x</defaultValue></hudson.model.StringParameterDefinition>
<hudson.model.StringParameterDefinition><name>FAIL</name><defaultValue>false</defaultValue></hudson.model.StringParameterDefinition>
</parameterDefinitions></hudson.model.ParametersDefinitionProperty></properties>
<definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>%s</script><sandbox>true</sandbox></definition>
</flow-definition>""" % escape(sys.argv[2]))
PY
}
# flow graph and step logs of a Pipeline build, as seen by a controller
GRAPH='def b = Jenkins.get().getItemByFullName("@JOB@").getBuildByNumber(@N@)
def out = new StringBuilder()
new org.jenkinsci.plugins.workflow.graph.FlowGraphWalker(b.execution).sort { it.id as int }.each { n ->
  out << n.id << " " << n.displayFunctionName << "\n"
  def la = n.getAction(org.jenkinsci.plugins.workflow.actions.LogAction)
  if (la != null) { def w = new StringWriter(); la.logText.writeLogTo(0, w); out << "  log: " << w.toString().trim().replace("\n", " | ") << "\n" }
}
println out'
graph() { local g=${GRAPH//@JOB@/$2}; groovy "$1" "${g//@N@/$3}"; }
same_graph() { [[ -n "$(graph "$1" "$3" "$4")" ]] && cmp -s <(graph "$1" "$3" "$4") <(graph "$2" "$3" "$4"); }
numbers_are() { [[ "$(numbers "$1" "$2")" == "$3" ]]; }
permalinks_agree() { local a; a="$(permalinks "$A" "$1")"; [[ -n "$a" && "$a" == "$(permalinks "$B" "$1")" ]]; }
blob_names() { groovy "$1" "println jenkins.persistence.PersistenceStores.getBlobStore().list(\"$2\").sort().join(' ')"; }
blob_store_class() { groovy "$1" 'println jenkins.persistence.PersistenceStores.getBlobStore()?.getClass()?.simpleName'; }
both_use_blobs() { [[ "$(blob_store_class $A)" == *AzureBlobStore* && "$(blob_store_class $B)" == *AzureBlobStore* ]]; }
console_has() { text "$1" "$2" 2>/dev/null | grep -q -- "$3"; }
some_console_has() { local n; for n in $(seq "$3" "$4"); do console_has "$1" "$2/$n/consoleText" "$5" && return 0; done; return 1; }
result_is() { [[ "$(result_of "$1" "$2" 2>/dev/null)" == "$3" ]]; }
desc_is() { [[ "$(json "$1" "$2/api/json?tree=description" | jq_ 'd["description"]')" == "$3" ]]; }
no_local_dir() { ! docker exec "$1" test -d "/var/jenkins_home/$2"; }

[[ -f "$ET1/war/jenkins.war" && -n "$(ls "$ET1"/plugins/*.hpi 2>/dev/null)" ]] || { echo "run ET1-build.sh first" >&2; exit 2; }

# ------------------------------------------------------------------ setup (as ET1/ET2)
et1 down -v --remove-orphans >/dev/null 2>&1 || true
rm -rf "$ET1/seed" "$ET1/repo"; mkdir -p "$ET1/seed" "$ET1/repo"
docker rm -f et1-seed >/dev/null 2>&1 || true
docker run -d --name et1-seed -p 8089:8080 --tmpfs /var/jenkins_home:uid=1000,gid=1000 \
  -e JAVA_OPTS="-Djenkins.install.runSetupWizard=false" -e CASC_JENKINS_CONFIG=/casc -v "$VALIDATION_DIR/jenkins/casc-et1:/casc:ro" \
  -v "$ET1/war:/war-override:ro" -v "$ET1/plugins:/extra-plugins:ro" cnj/jenkins:dev >/dev/null
wait_up 8089
groovy 8089 'hudson.util.Secret.fromString("x").getEncryptedValue(); org.jenkinsci.main.modules.instance_identity.InstanceIdentity.get()' >/dev/null
docker exec et1-seed tar -C /var/jenkins_home -cf - secret.key secret.key.not-so-secret identity.key.enc secrets | tar -C "$ET1/seed" -xf -
docker rm -f et1-seed >/dev/null

et1 up -d postgres azurite >/dev/null 2>&1; wait_postgres
et1 up -d jenkins-a jenkins-b >/dev/null 2>&1
wait_up $A; wait_up $B
r "## ET3 results ($(date -u +%Y-%m-%dT%H:%MZ))"
r ""
r "| | Check |"
r "|---|---|"
check "both controllers use the blob store" both_use_blobs

# ------------------------------------------------------------------ 1. freestyle with a JUnit report
log "scenario 1: freestyle build with JUnit on A"
python3 - "$OUT_DIR/fs.xml" <<'PY'
import sys
from xml.sax.saxutils import escape
cmd = """echo "hello freestyle"
cat > r.xml <<'EOF'
<testsuite name="s"><testcase classname="K" name="ok"/><testcase classname="K" name="bad"><failure message="boom"/></testcase></testsuite>
EOF"""
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<project><assignedNode>built-in</assignedNode><canRoam>false</canRoam>
<builders><hudson.tasks.Shell><command>%s</command></hudson.tasks.Shell></builders>
<publishers><hudson.tasks.junit.JUnitResultArchiver plugin="junit"><testResults>r.xml</testResults><allowEmptyResults>false</allowEmptyResults></hudson.tasks.junit.JUnitResultArchiver></publishers>
</project>""" % escape(cmd))
PY
create_job $A fs "$OUT_DIR/fs.xml"
eventually 15 exists $B /job/fs
post $A "/job/fs/build?delay=0sec" >/dev/null; wait_idle $A
check "1 B lists the freestyle build made on A" eventually 30 exists $B /job/fs/1
check "1 same result on both ($(result_of $A /job/fs/1))" same "$(result_of $A /job/fs/1)" "$(result_of $B /job/fs/1)"
check "1 same duration on both" same "$(json $A /job/fs/1/api/json?tree=duration)" "$(json $B /job/fs/1/api/json?tree=duration)"
check "1 console text is identical on B" same_text $A $B /job/fs/1/consoleText
check "1 test report has the same counts on B" same "$(json $A '/job/fs/1/testReport/api/json?tree=passCount,failCount,skipCount')" "$(json $B '/job/fs/1/testReport/api/json?tree=passCount,failCount,skipCount')"
info "1 test report on B: $(json $B '/job/fs/1/testReport/api/json?tree=passCount,failCount,skipCount')"

# ------------------------------------------------------------------ 2. Pipeline: console, graph, step logs
log "scenario 2: multi-stage Pipeline on A"
pipeline_job pl 'stage("one") { echo "first stage says hi" }
stage("two") { echo "second stage line 1"; echo "second stage line 2" }
stage("three") { echo "done ${params.ID}" }'
create_job $A pl "$OUT_DIR/pl.xml"
eventually 15 exists $B /job/pl
post $A "/job/pl/buildWithParameters?ID=pl1&delay=0sec" >/dev/null; wait_idle $A
check "2 B lists the Pipeline build made on A" eventually 30 exists $B /job/pl/1
check "2 consoleText is byte-identical on B" same_text $A $B /job/pl/1/consoleText
check "2 flow graph and step logs are identical on B" same_graph $A $B pl 1
graph $B pl 1 > "$OUT/pl-graph-B.txt"
info "2 flow nodes on B: $(grep -vc '  log:' "$OUT/pl-graph-B.txt"), step logs: $(grep -c '  log:' "$OUT/pl-graph-B.txt")"
info "2 blobs of pl #1: $(blob_names $A jobs/pl/builds/1)"
check "2 the log is in log storage (append blobs), next to the snapshot" same "$(blob_names $A jobs/pl/builds/1)" "jobs/pl/builds/1/files.tgz jobs/pl/builds/1/log jobs/pl/builds/1/log-index"

# ------------------------------------------------------------------ 3. interleaved history
log "scenario 3: 10 builds on A and 10 on B of one job"
pipeline_job il 'echo "interleaved ${params.ID}"; if (params.FAIL == "true") { error "failing on purpose" }'
create_job $A il "$OUT_DIR/il.xml"
eventually 15 exists $B /job/il
for i in $(seq 1 10); do
  fail=false; [[ $i == 4 ]] && fail=true
  post $A "/job/il/buildWithParameters?ID=a$i&FAIL=$fail&delay=0sec" >/dev/null 2>&1 &
  post $B "/job/il/buildWithParameters?ID=b$i&delay=0sec" >/dev/null 2>&1 &
done
wait; wait_idle $A; wait_idle $B; sleep 3
expected="$(seq 1 20 | tr '\n' ' ' | sed 's/ $//')"
check "3 A lists builds 1..20" eventually 30 numbers_are $A /job/il "$expected"
check "3 B lists builds 1..20" eventually 30 numbers_are $B /job/il "$expected"
check "3 permalinks agree" eventually 30 permalinks_agree /job/il
info "3 permalinks on A: $(permalinks $A /job/il); on B: $(permalinks $B /job/il)"
check "3 lastFailedBuild is the failed build, on A" console_has $A /job/il/lastFailedBuild/consoleText "interleaved a4"
check "3 lastFailedBuild is the failed build, on B" console_has $B /job/il/lastFailedBuild/consoleText "interleaved a4"

# ------------------------------------------------------------------ 4. edit and delete
log "scenario 4: description set on B, build deleted on A"
pipeline_job ed 'echo "to be edited ${params.ID}"'
create_job $A ed "$OUT_DIR/ed.xml"
eventually 15 exists $B /job/ed
post $A "/job/ed/buildWithParameters?ID=1&delay=0sec" >/dev/null; wait_idle $A
post $A "/job/ed/buildWithParameters?ID=2&delay=0sec" >/dev/null; wait_idle $A
eventually 30 exists $B /job/ed/2
post $B "/job/ed/1/submitDescription?description=set-on-B" >/dev/null
check "4 a description set on B for a build that ran on A is visible on A" eventually 30 desc_is $A /job/ed/1 set-on-B
json $B /job/ed/2/api/json >/dev/null # B materializes #2
check "4 B materialized #2 locally before the delete" docker exec et1-jenkins-b-1 test -f /var/jenkins_home/jobs/ed/builds/2/build.xml
post $A "/job/ed/2/doDelete" >/dev/null
check "4 a build deleted on A is gone on B" eventually 30 gone $B /job/ed/2
check "4 and its local directory on B is deleted" eventually 30 no_local_dir et1-jenkins-b-1 jobs/ed/builds/2
check "4 B's history is [1]" numbers_are $B /job/ed "1"

# ------------------------------------------------------------------ 5. disposable controller
log "scenario 5: destroy A and start a fresh A"
et1 rm -sf jenkins-a >/dev/null 2>&1
et1 up -d jenkins-a >/dev/null 2>&1; wait_up $A
check "5 fresh A has no local build directories to start with" no_local_dir et1-jenkins-a-1 jobs/il/builds/1
check "5 fresh A lists all builds of il" eventually 30 numbers_are $A /job/il "$expected"
check "5 B still lists all builds of il" numbers_are $B /job/il "$expected"
check "5 fresh A opens the console of a build A ran before" console_has $A /job/pl/1/consoleText "first stage says hi"
check "5 fresh A opens the console of a build B ran" some_console_has $A /job/il 1 20 "interleaved b"
check "5 fresh A opens the console of a freestyle build" console_has $A /job/fs/1/consoleText "hello freestyle"
check "5 fresh A shows the test results" same "$(json $A '/job/fs/1/testReport/api/json?tree=passCount,failCount,skipCount')" "$(json $B '/job/fs/1/testReport/api/json?tree=passCount,failCount,skipCount')"
check "5 fresh A shows the flow graph and step logs" same_graph $A $B pl 1

# ------------------------------------------------------------------ 6. running builds
log "scenario 6: a 60 s build on A as seen from B"
pipeline_job long 'echo "long ${params.ID} start"; for (int i = 1; i <= 12; i++) { echo "tick ${i}"; sleep 5 }; echo "long ${params.ID} end"'
create_job $A long "$OUT_DIR/long.xml"
eventually 15 exists $B /job/long
post $A "/job/long/buildWithParameters?ID=1&delay=0sec" >/dev/null
started=$SECONDS
check "6 B shows a placeholder for #1 running on jenkins-a" eventually 20 has_placeholder $B /job/long 1@jenkins-a
info "6 placeholder visible on B after $((SECONDS - started)) s"
check "6 A shows no placeholder for its own build" no_placeholders $A /job/long
check "6 B's read-only console shows the output so far" eventually 30 console_has $B /job/long/running-console/1 "tick 2"
check "6 placeholder replaced by the full build on B when it completes" eventually 120 result_is $B /job/long/1 SUCCESS
check "6 no placeholder left on B" no_placeholders $B /job/long
check "6 completed console identical on B" same_text $A $B /job/long/1/consoleText
log "scenario 6b: SIGKILL A during a running build"
post $A "/job/long/buildWithParameters?ID=2&delay=0sec" >/dev/null
eventually 30 has_placeholder $B /job/long 2@jenkins-a
docker kill -s KILL et1-jenkins-a-1 >/dev/null
killed=$SECONDS
check "6 B removes the stale placeholder within 90 s" eventually 90 no_placeholders $B /job/long
info "6 stale placeholder removed $((SECONDS - killed)) s after the kill"
et1 up -d jenkins-a >/dev/null 2>&1; wait_up $A

# ------------------------------------------------------------------ 7. rename
log "scenario 7: rename il to il2 on B"
post $B "/job/il/confirmRename?newName=il2" >/dev/null
check "7 A sees the rename" eventually 30 exists $A /job/il2
check "7 history intact on A under the new name" eventually 30 numbers_are $A /job/il2 "$expected"
check "7 history intact on B under the new name" numbers_are $B /job/il2 "$expected"
check "7 console of an old build readable on A" console_has $A /job/il2/1/consoleText "interleaved"
check "7 console of an old build readable on B" console_has $B /job/il2/2/consoleText "interleaved"
check "7 nothing left under the old name in the blob store" same "$(blob_names $A jobs/il)" ""
post $A "/job/il2/buildWithParameters?ID=after-rename&delay=0sec" >/dev/null; wait_idle $A
check "7 numbering continues after the rename (#21)" eventually 30 exists $B /job/il2/21

# ------------------------------------------------------------------ 8. delete and recreate
log "scenario 8: delete fs on A, recreate it on B"
post $A "/job/fs/doDelete" >/dev/null
check "8 the job is gone on B" eventually 30 gone $B /job/fs
create_job $B fs "$OUT_DIR/fs.xml"
eventually 15 exists $A /job/fs
post $B "/job/fs/build?delay=0sec" >/dev/null; wait_idle $B
check "8 first build of the recreated job is numbered after the old ones (#2)" eventually 30 numbers_are $B /job/fs "2"
check "8 no old build in its history on A" eventually 30 numbers_are $A /job/fs "2"

# ------------------------------------------------------------------ 9. large data is not in Postgres
log "scenario 9: what Postgres holds"
check "9 no snapshots or logs in jenkins_store" same "$(psql_q "select count(*) from jenkins_store where key like '%/files.tgz' or key like '%/log' or key like '%/log-index'")" 0
max=$(psql_q "select coalesce(max(length(content)), 0) from jenkins_store")
check "9 largest jenkins_store value is under 1 MB ($max bytes)" test "$max" -lt 1048576
blobs=$(groovy $A 'println jenkins.persistence.PersistenceStores.getBlobStore().list("jobs").size()')
check "9 snapshots and logs are in the blob container ($blobs blobs)" test "${blobs:-0}" -gt 10
psql_q "select key, length(content) from jenkins_store order by key" > "$OUT/jenkins_store.txt"
groovy $A 'println jenkins.persistence.PersistenceStores.getBlobStore().list("jobs").join("\n")' > "$OUT/blobs.txt"

et1 logs jenkins-a > "$OUT/jenkins-a.log" 2>&1; et1 logs jenkins-b > "$OUT/jenkins-b.log" 2>&1
r ""
r "Failures: $FAILED"
echo "ET3 exit=$FAILED"
exit $FAILED
