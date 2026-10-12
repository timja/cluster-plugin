#!/bin/bash
# ET4: acceptance experiment for Target 4 (running Pipeline builds survive their controller).
# ET setup (two tmpfs controllers, S0, Postgres, Azurite, token auth) plus agent1, an inbound WebSocket agent
# reaching the controllers through a failover URL (profile "agents"). Exits non-zero if any check fails.
#
# Prerequisite: CORE_REF=cnj/t4-m2-build-leases experiments/ET1-build.sh
source "$(dirname "$0")/../lib.sh"
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.fsmonitor GIT_CONFIG_VALUE_0=false
A=8081 B=8082 OUT="$RAW_DIR/ET4" ET1="$VALIDATION_DIR/.et1"
et1() { docker compose --project-directory "$VALIDATION_DIR" -f "$VALIDATION_DIR/compose.et1.yaml" --profile agents "$@"; }
rm -rf "$OUT"; mkdir -p "$OUT" "$OUT_DIR"
: > "$OUT/results.md"
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
FAILED=0
check() { local d=$1; shift; if "$@" >>"$OUT/check.log" 2>&1; then r "| ✅ | $d |"; else r "| ❌ | $d |"; FAILED=$((FAILED+1)); fi; }
info() { r "| ℹ️ | $* |"; }
eventually() { local t=$1 start=$SECONDS; shift; until "$@"; do (( SECONDS - start > t )) && return 1; sleep 2; done; }
http() { curl -gs -o /dev/null -w '%{http_code}' -u "$AUTH" "http://localhost:$1$2"; }
exists() { [[ "$(http "$1" "$2/api/json")" == 200 ]]; }
json() { curl -gsf -u "$AUTH" "http://localhost:$1$2"; }
text() { curl -gsf -u "$AUTH" "http://localhost:$1$2"; }
jq_() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
psql_q() { et1 exec -T postgres psql -U jenkins -At -c "$1"; }
console_has() { text "$1" "$2/consoleText" 2>/dev/null | grep -q -- "$3"; }
count_in_console() { text "$1" "$2/consoleText" 2>/dev/null | grep -c -x -- "$3"; }
building_on() { [[ "$(json "$1" "$2/api/json?tree=building" 2>/dev/null | jq_ 'd["building"]')" == True ]]; }
result_is() { [[ "$(json "$1" "$2/api/json?tree=result" 2>/dev/null | jq_ 'd["result"]')" == "$3" ]]; }
placeholders() { json "$1" "$2/api/json?tree=actions[builds[number,controller]]" 2>/dev/null \
  | jq_ '" ".join(str(b["number"]) + "@" + b["controller"] for a in d["actions"] if a and "builds" in a for b in a["builds"])'; }
has_placeholder() { [[ " $(placeholders "$1" "$2") " == *" $3 "* ]]; }
no_placeholders() { [[ -z "$(placeholders "$1" "$2")" ]]; }
agent_online_on() { [[ "$(groovy "$1" 'print(jenkins.model.Jenkins.get().getComputer("agent1")?.isOnline())' 2>/dev/null)" == true ]]; }
all_lines() { local port=$1 path=$2 prefix=$3 n=$4 i out; out=$(text "$port" "$path/consoleText"); for i in $(seq 1 "$n"); do grep -q -x -- "$prefix $i" <<<"$out" || return 1; done; }
same_text() { cmp -s <(text "$1" "$3") <(text "$2" "$3"); }
in_log() { et1 logs "$1" 2>&1 | grep -q -- "$2"; }
not_in_log() { ! in_log "$@"; }
record_writers() { psql_q "select count(distinct origin) from jenkins_store_events where key = 'jobs/$1/builds/$2/build.xml' and kind = 'WRITTEN'"; }
kill_a() { docker kill -s KILL et1-jenkins-a-1 >/dev/null; }
kill_b() { docker kill -s KILL et1-jenkins-b-1 >/dev/null; }
restart() { et1 up -d "$1" >/dev/null 2>&1; }
pipeline_job() { # name script [durability]
  python3 - "$OUT_DIR/$1.xml" "$2" "${3:-}" <<'PY'
import sys
from xml.sax.saxutils import escape
hint = sys.argv[3]
prop = "<org.jenkinsci.plugins.workflow.job.properties.DurabilityHintJobProperty><hint>%s</hint></org.jenkinsci.plugins.workflow.job.properties.DurabilityHintJobProperty>" % hint if hint else ""
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job"><properties>%s</properties>
<definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>%s</script><sandbox>true</sandbox></definition>
</flow-definition>""" % (prop, escape(sys.argv[2])))
PY
}

[[ -f "$ET1/war/jenkins.war" && -f "$ET1/plugins/workflow-cps.hpi" ]] || { echo "run ET1-build.sh first (with the workflow-cps worktree)" >&2; exit 2; }

# ------------------------------------------------------------------ setup (as ET3, plus agent1)
et1 down -v --remove-orphans >/dev/null 2>&1 || true
rm -rf "$ET1/seed" "$ET1/repo"; mkdir -p "$ET1/seed" "$ET1/repo"
docker rm -f et1-seed >/dev/null 2>&1 || true
docker run -d --name et1-seed -p 8089:8080 --tmpfs /var/jenkins_home:uid=1000,gid=1000 \
  -e JAVA_OPTS="-Djenkins.install.runSetupWizard=false" -e CASC_JENKINS_CONFIG=/casc -v "$VALIDATION_DIR/jenkins/casc-et1:/casc:ro" \
  -v "$ET1/war:/war-override:ro" -v "$ET1/plugins:/extra-plugins:ro" cnj/jenkins:dev >/dev/null
wait_up 8089
groovy 8089 'hudson.util.Secret.fromString("x").getEncryptedValue(); org.jenkinsci.main.modules.instance_identity.InstanceIdentity.get()' >/dev/null
# the agent's secret is an HMAC whose key is part of the seed, so it is valid on every controller
agent_secret 8089 agent1 > "$ET1/agent1.secret"
docker exec et1-seed tar -C /var/jenkins_home -cf - secret.key secret.key.not-so-secret identity.key.enc secrets | tar -C "$ET1/seed" -xf -
docker rm -f et1-seed >/dev/null
[[ -s "$ET1/agent1.secret" ]] || { echo "no agent secret" >&2; exit 2; }

et1 up -d postgres azurite >/dev/null 2>&1; wait_postgres
et1 up -d jenkins-a jenkins-b >/dev/null 2>&1
wait_up $A; wait_up $B
et1 up -d lb agent1 >/dev/null 2>&1
r "## ET4 results ($(date -u +%Y-%m-%dT%H:%MZ))"
r ""
r "| | Check |"
r "|---|---|"
check "agent1 is connected to A (the primary)" eventually 60 agent_online_on $A
check "agent1 is not connected to B" bash -c "! [[ \$(cd $VALIDATION_DIR && source lib.sh && groovy $B 'print(jenkins.model.Jenkins.get().getComputer(\"agent1\")?.isOnline())') == true ]]"

# ------------------------------------------------------------------ 3. controller-only Pipeline, owner killed
log "scenario 3: controller-only Pipeline, SIGKILL A"
pipeline_job ctl 'echo "start"; for (int i = 1; i <= 30; i++) { echo "step ${i}"; sleep 2 }; echo "end"'
create_job $A ctl "$OUT_DIR/ctl.xml"; eventually 15 exists $B /job/ctl
post $A "/job/ctl/build?delay=0sec" >/dev/null
eventually 60 console_has $A /job/ctl/1 "step 5"
check "3 B shows ctl #1 running on jenkins-a" eventually 10 has_placeholder $B /job/ctl 1@jenkins-a
kill_a; killed=$SECONDS
check "3 B adopts and resumes it" eventually 120 building_on $B /job/ctl/1
info "3 adopted $((SECONDS - killed)) s after the kill"
check "3 B logs the resumption" console_has $B /job/ctl/1 "Resuming build"
check "3 the build completes on B with SUCCESS" eventually 180 result_is $B /job/ctl/1 SUCCESS
check "3 every step ran (step 1..30)" all_lines $B /job/ctl/1 step 30
check "3 steps before the kill were not re-run from the start (step 1 once)" test "$(count_in_console $B /job/ctl/1 'step 1')" = 1
info "3 steps repeated after the resume: $(text $B /job/ctl/1/consoleText | grep -x 'step [0-9]*' | sort | uniq -d | tr '\n' ' ')"
restart jenkins-a; wait_up $A
check "3 fresh A lists the build with the same result" eventually 30 result_is $A /job/ctl/1 SUCCESS
check "3 fresh A shows the same console" same_text $A $B /job/ctl/1/consoleText

# ------------------------------------------------------------------ 1. agent build, owner killed during sh
log "scenario 1: sh on agent1, SIGKILL A at about tick 20"
eventually 120 agent_online_on $A || eventually 60 agent_online_on $B
info "1 agent1 connected to: $(agent_online_on $A && echo A || echo B)"
if ! agent_online_on $A; then # the agent stayed on B after scenario 3: move it back to the primary
  docker restart et1-agent1-1 >/dev/null; eventually 90 agent_online_on $A
fi
pipeline_job ag 'stage("a") { echo "stage a" }
node("agent1") { sh "for i in \$(seq 1 60); do echo tick \$i; sleep 1; done" }
input message: "continue?", id: "Go"
node("agent1") { sh "echo after input" }
echo "done"'
create_job $A ag "$OUT_DIR/ag.xml"; eventually 15 exists $B /job/ag
post $A "/job/ag/build?delay=0sec" >/dev/null
check "1 agent sh is running on A" eventually 90 console_has $A /job/ag/1 "tick 20"
kill_a; killed=$SECONDS
check "1 B adopts the build" eventually 120 building_on $B /job/ag/1
info "1 adopted $((SECONDS - killed)) s after the kill"
check "1 agent1 reconnects to B" eventually 120 agent_online_on $B
check "1 the sh step continues to tick 60" eventually 180 console_has $B /job/ag/1 "tick 60"
check "1 every tick is in the log (1..60)" all_lines $B /job/ag/1 tick 60
text $B /job/ag/1/consoleText > "$OUT/ag-1-console.txt" 2>&1 || true
check "1 the sh step was not restarted (tick 1 once)" test "$(count_in_console $B /job/ag/1 'tick 1')" = 1
check "1 the build waits at input on B" eventually 60 console_has $B /job/ag/1 "continue?"
post $B "/job/ag/1/input/Go/proceedEmpty" >/dev/null
check "1 approved on B: the build completes with SUCCESS" eventually 120 result_is $B /job/ag/1 SUCCESS
check "1 post-input agent step ran" console_has $B /job/ag/1 "after input"
check "1 the log has the pre-kill output, the resumption, and the rest" bash -c "cd $VALIDATION_DIR && source lib.sh && curl -gsf -u \"\$AUTH\" http://localhost:$B/job/ag/1/consoleText | python3 -c 'import sys; t=sys.stdin.read(); i=[t.find(s) for s in (\"stage a\", \"tick 10\", \"Resuming build\", \"tick 60\", \"after input\", \"done\")]; sys.exit(0 if all(x >= 0 for x in i) and i == sorted(i) else 1)'"
restart jenkins-a; wait_up $A
check "1 fresh A lists it with the same number and result" eventually 30 result_is $A /job/ag/1 SUCCESS
check "1 fresh A shows the same console" same_text $A $B /job/ag/1/consoleText

# ------------------------------------------------------------------ 2. owner killed while waiting at input
log "scenario 2: waiting at input on B, SIGKILL B; A adopts"
pipeline_job inp 'echo "before input"; input message: "go?", id: "Go"; node("agent1") { sh "echo agent after input" }; echo "finished"'
create_job $B inp "$OUT_DIR/inp.xml"; eventually 15 exists $A /job/inp
post $B "/job/inp/build?delay=0sec" >/dev/null
eventually 60 console_has $B /job/inp/1 "go?"
sleep 3 # let the save point ship
kill_b; killed=$SECONDS
check "2 A adopts the build waiting at input" eventually 120 building_on $A /job/inp/1
info "2 adopted $((SECONDS - killed)) s after the kill"
check "2 A has the pending input" eventually 60 bash -c "[[ \$(cd $VALIDATION_DIR && source lib.sh && curl -gs -o /dev/null -w '%{http_code}' -u \"\$AUTH\" http://localhost:$A/job/inp/1/input/Go/) == 200 ]]"
post $A "/job/inp/1/input/Go/proceedEmpty" >/dev/null
check "2 approved on A: the build completes with SUCCESS" eventually 180 result_is $A /job/inp/1 SUCCESS
check "2 the agent step after input ran" console_has $A /job/inp/1 "agent after input"
restart jenkins-b; wait_up $B

# ------------------------------------------------------------------ 2b. the workspace across an adoption (Target 15)
log "scenario 2b: a later stage reads what an earlier stage left in the workspace, the owner killed in between"
if agent_online_on $A; then OWNER=$A OTHER=$B KILL=kill_a OWNER_SVC=jenkins-a; else OWNER=$B OTHER=$A KILL=kill_b OWNER_SVC=jenkins-b; fi
pipeline_job ws 'pipeline { agent { label "agent1" }; stages {
  stage("write") { steps { sh "echo carried-from-stage-write > carried.txt" } }
  stage("wait") { steps { sleep 40 } }
  stage("read") { steps { sh "cat carried.txt" } } } }'
create_job $OWNER ws "$OUT_DIR/ws.xml"; eventually 15 exists $OTHER /job/ws
post $OWNER "/job/ws/build?delay=0sec" >/dev/null
eventually 90 console_has $OWNER /job/ws/1 "Sleeping for"
sleep 3 # its save point is shipped
$KILL
on_other() { agent_online_on $OTHER; }
check "2b the other controller adopts it, and agent1 reconnects there" eventually 150 on_other
check "2b the read stage finds the file the write stage left: the build completes" eventually 240 result_is $OTHER /job/ws/1 SUCCESS
check "2b ... printing it after the resumption" bash -c "cd $VALIDATION_DIR && source lib.sh && curl -gsf -u \"\$AUTH\" http://localhost:$OTHER/job/ws/1/consoleText | python3 -c 'import sys; t=sys.stdin.read(); i=t.find(\"Resuming\"); i=t.find(\"+ cat carried.txt\", i) if i >= 0 else -1; i=t.find(\"\\ncarried-from-stage-write\", i) if i >= 0 else -1; sys.exit(0 if i >= 0 else 1)'"
restart $OWNER_SVC; wait_up $OWNER

# ------------------------------------------------------------------ 4. partition: owner paused, not killed
log "scenario 4: docker pause A until B adopts, then unpause"
pipeline_job pt 'echo "start"; for (int i = 1; i <= 40; i++) { echo "beat ${i}"; sleep 2 }; echo "end"'
create_job $A pt "$OUT_DIR/pt.xml"; eventually 15 exists $B /job/pt
post $A "/job/pt/build?delay=0sec" >/dev/null
eventually 60 console_has $A /job/pt/1 "beat 5"
docker pause et1-jenkins-a-1 >/dev/null; paused=$SECONDS
check "4 B adopts while A is paused" eventually 120 building_on $B /job/pt/1
info "4 adopted $((SECONDS - paused)) s after the pause"
docker unpause et1-jenkins-a-1 >/dev/null
check "4 A notices it lost the build" eventually 90 in_log jenkins-a "Ownership of pt #1 was lost"
check "4 the build completes on B with SUCCESS" eventually 180 result_is $B /job/pt/1 SUCCESS
check "4 only B published the record" test "$(record_writers pt 1)" = 1
check "4 A lists B's build (its own copy discarded)" eventually 60 result_is $A /job/pt/1 SUCCESS
check "4 A's console is B's (no appends from A after the fence)" eventually 30 same_text $A $B /job/pt/1/consoleText
check "4 every beat is in the log" all_lines $B /job/pt/1 beat 40
text $B /job/pt/1/consoleText > "$OUT/pt-1-console.txt" 2>&1 || true

# ------------------------------------------------------------------ 5. no false adoption
log "scenario 5: a long build on a healthy A"
pipeline_job calm 'for (int i = 1; i <= 15; i++) { echo "calm ${i}"; sleep 2 }'
create_job $A calm "$OUT_DIR/calm.xml"; eventually 15 exists $B /job/calm
post $A "/job/calm/build?delay=0sec" >/dev/null
eventually 60 console_has $A /job/calm/1 "calm 1"
seen_b=0
for i in $(seq 1 15); do has_placeholder $B /job/calm 1@jenkins-a || seen_b=$((seen_b + 1)); sleep 2; done
check "5 completes on A with SUCCESS" eventually 90 result_is $A /job/calm/1 SUCCESS
check "5 B never showed it as anything but running on A" bash -c "[[ $seen_b -le 1 ]]"
check "5 B never adopted it" not_in_log jenkins-b "Adopting calm #1"

# ------------------------------------------------------------------ 6. PERFORMANCE_OPTIMIZED is not adopted
log "scenario 6: PERFORMANCE_OPTIMIZED build, SIGKILL A"
pipeline_job fast 'for (int i = 1; i <= 30; i++) { echo "fast ${i}"; sleep 2 }' PERFORMANCE_OPTIMIZED
create_job $A fast "$OUT_DIR/fast.xml"; eventually 15 exists $B /job/fast
post $A "/job/fast/build?delay=0sec" >/dev/null
eventually 60 console_has $A /job/fast/1 "fast 3"
eventually 10 has_placeholder $B /job/fast 1@jenkins-a
kill_a; killed=$SECONDS
check "6 B removes its placeholder" eventually 120 no_placeholders $B /job/fast
check "6 B does not run it: it is closed as aborted (Target 12)" eventually 60 bash -c "curl -gsf -u '$AUTH' 'http://localhost:$B/job/fast/1/api/json?tree=result' | grep -q ABORTED && curl -gsf -u '$AUTH' http://localhost:$B/job/fast/1/consoleText | grep -q 'closed as aborted' && [[ \$(curl -gsf -u '$AUTH' http://localhost:$B/job/fast/1/consoleText | grep -c '^fast 1\$') -le 1 ]]"
check "6 ... keeping what it printed on A (Target 15)" bash -c "curl -gsf -u '$AUTH' http://localhost:$B/job/fast/1/consoleText | grep -q '^fast 3\$'"
restart jenkins-a; wait_up $A

# ------------------------------------------------------------------ 7. cost of save points
log "scenario 7: 200-step Pipeline"
pipeline_job steps 'for (int i = 1; i <= 200; i++) { echo "s${i}" }'
create_job $A steps "$OUT_DIR/steps.xml"
post $A "/job/steps/build?delay=0sec" >/dev/null
eventually 120 result_is $A /job/steps/1 SUCCESS
dur=$(json $A /job/steps/1/api/json?tree=duration | jq_ 'd["duration"]')
commits=$(psql_q "select count(*) from jenkins_store_events where key = 'jobs/steps/running/1.xml'")
info "7 200 echo steps: ${dur} ms; placeholder writes (save point commits + start/end): ${commits}"
pipeline_job steps0 'for (int i = 1; i <= 200; i++) { echo "s${i}" }' PERFORMANCE_OPTIMIZED
create_job $A steps0 "$OUT_DIR/steps0.xml"
post $A "/job/steps0/build?delay=0sec" >/dev/null
eventually 120 result_is $A /job/steps0/1 SUCCESS
dur0=$(json $A /job/steps0/1/api/json?tree=duration | jq_ 'd["duration"]')
info "7 the same with PERFORMANCE_OPTIMIZED (no save points): ${dur0} ms"
leftover=$(groovy $A 'println jenkins.persistence.PersistenceStores.getBlobStore().list("jobs").findAll { it.contains("/running/") }.collect { it.replaceAll("/running/.*", "") }.unique().join(" ")')
groovy $A 'println jenkins.persistence.PersistenceStores.getBlobStore().list("jobs").findAll { it.contains("/running/") }.join("\n")' > "$OUT/leftover-save-points.txt" 2>&1 || true
info "7 builds with save points left in blob storage: ${leftover:-none}"
check "7 no save points left in blob storage after completion" test -z "$leftover"

et1 logs jenkins-a > "$OUT/jenkins-a.log" 2>&1; et1 logs jenkins-b > "$OUT/jenkins-b.log" 2>&1; et1 logs agent1 > "$OUT/agent1.log" 2>&1
cp "$ET1/BUILD" "$OUT/BUILD" 2>/dev/null || true
r ""
r "Failures: $FAILED"
echo "ET4 exit=$FAILED"
exit $FAILED
