#!/bin/bash
# ET5: acceptance experiment for Target 5 (a cluster-wide build queue and cross-build coordination).
# ET4 setup (two tmpfs controllers, S0, Postgres, Azurite, token auth, agent1 through the failover URL),
# plus lockable-resources with cluster locks and the resource "res" declared on both. Exits non-zero if any check fails.
#
# Prerequisite: CORE_REF=cnj/t5-m3-cluster-periodic-work experiments/ET1-build.sh
source "$(dirname "$0")/../lib.sh"
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.fsmonitor GIT_CONFIG_VALUE_0=false
A=8081 B=8082 OUT="$RAW_DIR/ET5" ET1="$VALIDATION_DIR/.et1"
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
idle() { [[ "$(json "$1" "/computer/api/json?tree=busyExecutors" | jq_ 'd["busyExecutors"]')" == 0 && \
          "$(json "$1" "/queue/api/json?tree=items[id]" | jq_ 'len(d["items"])')" == 0 ]]; }
wait_idle() { sleep 3; eventually 300 idle "$1"; }
both_idle() { idle $A && idle $B; }
numbers() { json "$1" "$2/api/json?tree=builds[number]" | jq_ '" ".join(str(b["number"]) for b in sorted(d["builds"], key=lambda b: b["number"]))'; }
numbers_are() { [[ "$(numbers "$1" "$2")" == "$3" ]]; }
build_count() { json "$1" "$2/api/json?tree=builds[number]" | jq_ 'len(d["builds"])'; }
result_is() { [[ "$(json "$1" "$2/api/json?tree=result" 2>/dev/null | jq_ 'd["result"]')" == "$3" ]]; }
building_on() { [[ "$(json "$1" "$2/api/json?tree=building" 2>/dev/null | jq_ 'd["building"]')" == True ]]; }
console_has() { text "$1" "$2/consoleText" 2>/dev/null | grep -q -- "$3"; }
agent_online_on() { [[ "$(groovy "$1" 'print(jenkins.model.Jenkins.get().getComputer("agent1")?.isOnline())' 2>/dev/null)" == true ]]; }
# schedule <port> <path> : POST and print the queue item id from the Location header
schedule() { curl -gs -u "$AUTH" -X POST -o /dev/null -D - "http://localhost:$1$2" | awk -F'/' 'tolower($0) ~ /^location:/ {for (i=NF; i>0; i--) if ($i ~ /^[0-9]+\r?$/) {gsub(/\r/, "", $i); print $i; exit}}'; }
queue_executable() { json "$1" "/queue/item/$2/api/json" 2>/dev/null | jq_ '(d.get("executable") or {}).get("number")'; }
queue_cancelled() { [[ "$(json "$1" "/queue/item/$2/api/json" 2>/dev/null | jq_ 'd.get("cancelled")')" == True ]]; }
in_queue_on() { [[ "$(json "$1" "/queue/api/json?tree=items[id]" | jq_ '" ".join(str(i["id"]) for i in d["items"])')" == *"$2"* ]]; }
freestyle() { # name command [extra xml]
  python3 - "$OUT_DIR/$1.xml" "$2" "${3:-}" <<'PY'
import sys
from xml.sax.saxutils import escape
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<project>%s<builders><hudson.tasks.Shell><command>%s</command></hudson.tasks.Shell></builders></project>""" % (sys.argv[3], escape(sys.argv[2])))
PY
}
pipeline_job() { # name script [properties xml]
  python3 - "$OUT_DIR/$1.xml" "$2" "${3:-}" <<'PY'
import sys
from xml.sax.saxutils import escape
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job"><properties>%s</properties>
<definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>%s</script><sandbox>true</sandbox></definition>
</flow-definition>""" % (sys.argv[3], escape(sys.argv[2])))
PY
}
STRING_PARAM='<hudson.model.ParametersDefinitionProperty><parameterDefinitions><hudson.model.StringParameterDefinition><name>N</name><defaultValue>0</defaultValue></hudson.model.StringParameterDefinition></parameterDefinitions></hudson.model.ParametersDefinitionProperty>'
create_both() { create_job $A "$1" "$OUT_DIR/$1.xml"; eventually 15 exists $B "/job/$1"; }

[[ -f "$ET1/war/jenkins.war" && -f "$ET1/plugins/lockable-resources.hpi" ]] || { echo "run ET1-build.sh first (with lockable-resources)" >&2; exit 2; }

# ------------------------------------------------------------------ setup (as ET4)
et1 down -v --remove-orphans >/dev/null 2>&1 || true
rm -rf "$ET1/seed" "$ET1/repo"; mkdir -p "$ET1/seed" "$ET1/repo"
docker rm -fv et1-seed >/dev/null 2>&1 || true
docker run -d --name et1-seed -p 8089:8080 --tmpfs /var/jenkins_home:uid=1000,gid=1000 \
  -e JAVA_OPTS="-Djenkins.install.runSetupWizard=false" -e CASC_JENKINS_CONFIG=/casc -v "$VALIDATION_DIR/jenkins/casc-et1:/casc:ro" \
  -v "$ET1/war:/war-override:ro" -v "$ET1/plugins:/extra-plugins:ro" cnj/jenkins:dev >/dev/null
wait_up 8089
groovy 8089 'hudson.util.Secret.fromString("x").getEncryptedValue(); org.jenkinsci.main.modules.instance_identity.InstanceIdentity.get()' >/dev/null
agent_secret 8089 agent1 > "$ET1/agent1.secret"
docker exec et1-seed tar -C /var/jenkins_home -cf - secret.key secret.key.not-so-secret identity.key.enc secrets | tar -C "$ET1/seed" -xf -
docker rm -fv et1-seed >/dev/null
et1 up -d postgres azurite >/dev/null 2>&1; wait_postgres
et1 up -d jenkins-a jenkins-b >/dev/null 2>&1
wait_up $A; wait_up $B
et1 up -d lb agent1 >/dev/null 2>&1
r "## ET5 results ($(date -u +%Y-%m-%dT%H:%MZ))"
r ""
r "| | Check |"
r "|---|---|"
check "agent1 is connected to A" eventually 60 agent_online_on $A
# the lockable resource "res", declared (not ephemeral) on a controller, as configuration as code would
declare_res() { groovy "$1" 'def m = org.jenkins.plugins.lockableresources.LockableResourcesManager.get(); if (m.fromName("res") == null) { m.addResource(new org.jenkins.plugins.lockableresources.LockableResource("res")) }' >/dev/null; }
declare_res $A; declare_res $B
check "both controllers share the queue" bash -c "for p in $A $B; do [[ \$(cd $VALIDATION_DIR && source lib.sh && groovy \$p 'println jenkins.persistence.PersistenceStores.get().queue() != null') == true ]] || exit 1; done"

# ------------------------------------------------------------------ 1. fold once
log "scenario 1: Build Now on A and B within the quiet period"
freestyle fold 'echo "fold on $HOSTNAME"' '<quietPeriod>15</quietPeriod>'
create_both fold
id_a=$(schedule $A "/job/fold/build?delay=15sec")
sleep 5
id_b=$(schedule $B "/job/fold/build?delay=15sec")
info "1 queue item ids: A=$id_a B=$id_b"
check "1 the second request folded into the same queue item" test -n "$id_a" -a "$id_a" = "$id_b"
check "1 the item is in both queues" bash -c "cd $VALIDATION_DIR && source lib.sh && for p in $A $B; do curl -gsf -u \"\$AUTH\" http://localhost:\$p/queue/api/json?tree=items[id] | grep -q '\"id\":$id_a' || exit 1; done"
sleep 15; wait_idle $A; wait_idle $B
check "1 exactly one build" eventually 30 numbers_are $A /job/fold "1"
check "1 and B agrees" eventually 30 numbers_are $B /job/fold "1"
causes=$(groovy $A 'println Jenkins.get().getItemByFullName("fold").getBuildByNumber(1).getAction(hudson.model.CauseAction).getCauseCounts().values().sum()')
check "1 with both requests as causes (got $causes)" test "$causes" = 2

# ------------------------------------------------------------------ 2. exactly once, spread
log "scenario 2: 40 parameterized builds submitted alternately to A and B"
freestyle many 'echo "id=$N host=$HOSTNAME"; sleep 2' "<concurrentBuild>true</concurrentBuild><properties>$STRING_PARAM</properties>"
create_both many
: > "$OUT/many-ids.txt"
for i in $(seq 1 20); do
  echo "a$i $(schedule $A "/job/many/buildWithParameters?N=a$i&delay=0sec")" >> "$OUT/many-ids.txt" &
  echo "b$i $(schedule $B "/job/many/buildWithParameters?N=b$i&delay=0sec")" >> "$OUT/many-ids.txt" &
done
wait; sleep 5; eventually 300 both_idle
check "2 forty builds, numbered 1..40" eventually 60 numbers_are $A /job/many "$(seq 1 40 | tr '\n' ' ' | sed 's/ $//')"
# output lines only (the shell's -x trace repeats each command with a leading "+")
for n in $(seq 1 40); do text $A "/job/many/$n/consoleText"; done | grep -v '^+' > "$OUT/many-consoles.txt"
check "2 every request ran exactly once" test "$(grep -o 'id=[ab][0-9]*' "$OUT/many-consoles.txt" | sort | uniq | wc -l | tr -d ' ')" = 40 -a "$(grep -c 'id=[ab][0-9]*' "$OUT/many-consoles.txt")" = 40
hosts=$(grep -o 'host=jenkins-[ab]' "$OUT/many-consoles.txt" | sort | uniq -c | tr '\n' ' ')
info "2 builds per controller: $hosts"
check "2 both controllers ran some" test "$(grep -o 'host=jenkins-[ab]' "$OUT/many-consoles.txt" | sort -u | wc -l | tr -d ' ')" = 2
check "2 queue ids are unique cluster-wide" test "$(awk '{print $2}' "$OUT/many-ids.txt" | sort -u | grep -c .)" = 40
one_id=$(awk 'NR==1 {print $2}' "$OUT/many-ids.txt"); other_id=$(awk 'NR==2 {print $2}' "$OUT/many-ids.txt")
check "2 queue/item/<id> resolves to the build on both controllers" bash -c "[[ -n \"$one_id\" ]] && cd $VALIDATION_DIR && source lib.sh && a=\$(curl -gsf -u \"\$AUTH\" http://localhost:$A/queue/item/$one_id/api/json | python3 -c 'import json,sys; print((json.load(sys.stdin).get(\"executable\") or {}).get(\"number\"))') && b=\$(curl -gsf -u \"\$AUTH\" http://localhost:$B/queue/item/$one_id/api/json | python3 -c 'import json,sys; print((json.load(sys.stdin).get(\"executable\") or {}).get(\"number\"))') && [[ \$a != None && \$a == \$b ]]"

# ------------------------------------------------------------------ 3. non-concurrent across controllers
log "scenario 3: non-concurrent job building on A, Build Now on B"
freestyle serial 'echo "serial start $HOSTNAME $(date +%s)"; sleep 20; echo "serial end $(date +%s)"'
create_both serial
schedule $A "/job/serial/build?delay=0sec" >/dev/null
eventually 60 building_on $A /job/serial/1
schedule $B "/job/serial/build?delay=0sec" >/dev/null
sleep 6
check "3 B's request waits while #1 builds on A" bash -c "! (cd $VALIDATION_DIR && source lib.sh && curl -gsf -u \"\$AUTH\" http://localhost:$A/job/serial/2/api/json >/dev/null 2>&1)"
eventually 180 result_is $A /job/serial/2 SUCCESS
e1=$(text $A /job/serial/1/consoleText | awk '/^serial end/ {print $3}'); s2=$(text $A /job/serial/2/consoleText | awk '/^serial start/ {print $4}')
check "3 the two builds did not overlap (#1 ended $e1, #2 started $s2)" test -n "$e1" -a -n "$s2" -a "${s2:-0}" -ge "${e1:-1}"

# ------------------------------------------------------------------ 4. cancel anywhere
log "scenario 4: item in its quiet period created on A, cancelled on B"
freestyle cxl 'echo "should never run"'
create_both cxl
cid=$(schedule $A "/job/cxl/build?delay=60sec")
eventually 15 in_queue_on $B "$cid"
check "4 cancelled on B" test "$(curl -gs -o /dev/null -w '%{http_code}' -u "$AUTH" -X POST "http://localhost:$B/queue/cancelItem?id=$cid")" = 204
check "4 gone from A's queue" eventually 15 bash -c "! (cd $VALIDATION_DIR && source lib.sh && curl -gsf -u \"\$AUTH\" 'http://localhost:$A/queue/api/json?tree=items[id]' | grep -q '\"id\":$cid')"
check "4 reported cancelled on A" eventually 15 queue_cancelled $A "$cid"

# ------------------------------------------------------------------ 5. stop anywhere
log "scenario 5: build running on A stopped from B"
pipeline_job longrun 'echo "running"; sleep 120'
create_both longrun
schedule $A "/job/longrun/build?delay=0sec" >/dev/null
eventually 60 console_has $A /job/longrun/1 "running"
sleep 2
post $B "/job/longrun/running-elsewhere/stop?number=1" >/dev/null
check "5 aborted on A" eventually 60 result_is $A /job/longrun/1 ABORTED
check "5 the log says who stopped it" console_has $A /job/longrun/1 "Stopped from jenkins-b"

# ------------------------------------------------------------------ 6. the queue survives its controller
log "scenario 6: three items waiting on A, SIGKILL A"
freestyle surv 'echo "survived $N on $HOSTNAME"' "<properties>$STRING_PARAM</properties>"
create_both surv
for n in 1 2 3; do schedule $A "/job/surv/buildWithParameters?N=$n&delay=20sec" >/dev/null; done
eventually 15 bash -c "[[ \$(cd $VALIDATION_DIR && source lib.sh && curl -gsf -u \"\$AUTH\" 'http://localhost:$B/queue/api/json?tree=items[id]' | python3 -c 'import json,sys; print(len(json.load(sys.stdin)[\"items\"]))') -ge 3 ]]"
docker kill -s KILL et1-jenkins-a-1 >/dev/null
check "6 all three run on B" eventually 120 bash -c "[[ \$(cd $VALIDATION_DIR && source lib.sh && curl -gsf -u \"\$AUTH\" 'http://localhost:$B/job/surv/api/json?tree=builds[number,result]' | python3 -c 'import json,sys; print(sum(1 for b in json.load(sys.stdin)[\"builds\"] if b[\"result\"] == \"SUCCESS\"))') == 3 ]]"
check "6 they ran on jenkins-b" bash -c "cd $VALIDATION_DIR && source lib.sh && for n in 1 2 3; do curl -gsf -u \"\$AUTH\" http://localhost:$B/job/surv/\$n/consoleText | grep -q 'on jenkins-b' || exit 1; done"
et1 up -d jenkins-a >/dev/null 2>&1; wait_up $A; declare_res $A
docker restart et1-agent1-1 >/dev/null; eventually 90 agent_online_on $A

# ------------------------------------------------------------------ 7. agent affinity
log "scenario 7: label agent1 scheduled on B, agent connected to A"
freestyle onagent 'echo "on the agent: $(hostname)"' '<assignedNode>agent1</assignedNode><canRoam>false</canRoam>'
create_both onagent
schedule $B "/job/onagent/build?delay=0sec" >/dev/null
check "7 it runs, on the controller holding the agent" eventually 120 result_is $A /job/onagent/1 SUCCESS
check "7 it ran on A (whose executor on agent1 took it)" bash -c "cd $VALIDATION_DIR && source lib.sh && [[ \$(groovy $A 'print(Jenkins.get().getItemByFullName(\"onagent\").getBuildByNumber(1).getBuiltOnStr())') == agent1 ]]"

# ------------------------------------------------------------------ 8. milestone
log "scenario 8: #1 on A sleeps before milestone 1, #2 on B passes it first"
pipeline_job ms 'if (params.N == "1") { echo "one waiting"; sleep 30 }; milestone 1; echo "passed ${params.N}"; sleep 5' "$STRING_PARAM"
create_both ms
schedule $A "/job/ms/buildWithParameters?N=1&delay=0sec" >/dev/null
eventually 60 console_has $A /job/ms/1 "one waiting"
schedule $B "/job/ms/buildWithParameters?N=2&delay=0sec" >/dev/null
check "8 #2 passes milestone 1" eventually 90 console_has $B /job/ms/2 "passed 2"
check "8 #1 on A is cancelled" eventually 90 bash -c "cd $VALIDATION_DIR && source lib.sh && r=\$(curl -gsf -u \"\$AUTH\" 'http://localhost:$A/job/ms/1/api/json?tree=result' | python3 -c 'import json,sys; print(json.load(sys.stdin)[\"result\"])'); [[ \$r == ABORTED || \$r == NOT_BUILT ]]"
check "8 #1 never passed the milestone" bash -c "! (cd $VALIDATION_DIR && source lib.sh && curl -gsf -u \"\$AUTH\" http://localhost:$A/job/ms/1/consoleText | grep -q 'passed 1')"

# ------------------------------------------------------------------ 9. lock across controllers
log "scenario 9: lock('res') from A and B at once"
pipeline_job lk1 'lock("res") { echo "holding res 1"; sleep 15 }'
pipeline_job lk2 'lock("res") { echo "holding res 2"; sleep 15 }'
create_both lk1; create_both lk2
schedule $A "/job/lk1/build?delay=0sec" >/dev/null &
schedule $B "/job/lk2/build?delay=0sec" >/dev/null &
wait
check "9 both complete" eventually 120 bash -c "cd $VALIDATION_DIR && source lib.sh && for j in lk1 lk2; do for p in $A $B; do curl -gsf -u \"\$AUTH\" http://localhost:\$p/job/\$j/1/api/json?tree=result | grep -q SUCCESS && continue 2; done; exit 1; done"
# the store's record of who held res: holds must strictly alternate (acquired, released, acquired, released)
psql_q "select kind || ' ' || extract(epoch from at) from jenkins_store_events where key = 'lockable-resources/res' order by seq" > "$OUT/res-events.txt"
info "9 res holds: $(awk '{printf "%s@%.1f ", $1, $2}' "$OUT/res-events.txt")"
check "9 res was held by one build at a time (store events alternate, two holds)" test "$(awk '{print $1}' "$OUT/res-events.txt" | tr '\n' ' ')" = "WRITTEN DELETED WRITTEN DELETED "
info "9 the waiter got res $(awk 'NR==2 {r=$2} NR==3 {printf "%.1f", $2 - r}' "$OUT/res-events.txt") s after its release"

# ------------------------------------------------------------------ 10. upstream trigger and build step
log "scenario 10: upstream trigger and build step"
freestyle down 'echo "down on $HOSTNAME"'
create_both down
python3 - "$OUT_DIR/up.xml" <<'PY'
import sys
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<project><builders><hudson.tasks.Shell><command>echo up</command></hudson.tasks.Shell></builders>
<publishers><hudson.tasks.BuildTrigger><childProjects>down</childProjects><threshold><name>SUCCESS</name><ordinal>0</ordinal><color>BLUE</color><completeBuild>true</completeBuild></threshold></hudson.tasks.BuildTrigger></publishers></project>""")
PY
create_both up
schedule $B "/job/up/build?delay=0sec" >/dev/null
eventually 120 result_is $B /job/up/1 SUCCESS; sleep 10; eventually 300 both_idle
check "10 one upstream build on B triggered exactly one downstream build" eventually 30 numbers_are $A /job/down "1"
schedule $A "/job/up/build?delay=0sec" >/dev/null
eventually 120 result_is $A /job/up/2 SUCCESS; sleep 10; eventually 300 both_idle
check "10 one more on A triggered exactly one more" eventually 30 numbers_are $B /job/down "1 2"
freestyle callee 'echo "callee on $HOSTNAME"'
create_both callee
pipeline_job caller 'def b = build job: "callee", wait: true; echo "callee result ${b.result}"'
create_both caller
schedule $A "/job/caller/build?delay=0sec" >/dev/null
check "10 build(wait: true) from A completes" eventually 120 result_is $A /job/caller/1 SUCCESS
check "10 the callee ran on A, where the caller waits" console_has $A /job/callee/1 "callee on jenkins-a"

# ------------------------------------------------------------------ 11. cluster-singleton periodic work
log "scenario 11: cluster-singleton periodic work"
# through reflection: the script console's Groovy cannot call static interface methods directly
ALLOWS='println jenkins.util.PeriodicWorkCoordinator.getMethod("allows", hudson.model.PeriodicWork).invoke(null, hudson.ExtensionList.lookupSingleton(jenkins.model.BackgroundGlobalBuildDiscarder))'
ra=$(groovy $A "$ALLOWS"); rb=$(groovy $B "$ALLOWS")
info "11 BackgroundGlobalBuildDiscarder allowed for this period: A=$ra B=$rb"
period=$(groovy $A 'println System.currentTimeMillis().intdiv(hudson.ExtensionList.lookupSingleton(jenkins.model.BackgroundGlobalBuildDiscarder).getRecurrencePeriod())')
claims=$(psql_q "select count(*) from jenkins_trigger_claims where item = '_periodic' and trigger = 'jenkins.model.BackgroundGlobalBuildDiscarder' and tick = $period")
info "11 claims for this period: $claims (the discarder may already have run after startup and claimed it)"
check "11 at most one controller runs it this period" bash -c "[[ \"$ra $rb\" != 'true true' ]]"
check "11 and exactly one claim is recorded for it" test "$claims" = 1
nonsingleton=$(groovy $A 'println jenkins.util.PeriodicWorkCoordinator.getMethod("allows", hudson.model.PeriodicWork).invoke(null, hudson.ExtensionList.lookupSingleton(hudson.model.LoadStatistics.LoadStatisticsUpdater))')
check "11 per-controller work is not coordinated" test "$nonsingleton" = true

et1 logs jenkins-a > "$OUT/jenkins-a.log" 2>&1; et1 logs jenkins-b > "$OUT/jenkins-b.log" 2>&1
psql_q "select id, task, state, claimed_by, build_number from jenkins_queue order by id" > "$OUT/queue.txt"
cp "$ET1/BUILD" "$OUT/BUILD" 2>/dev/null || true
r ""
r "Failures: $FAILED"
echo "ET5 exit=$FAILED"
exit $FAILED
