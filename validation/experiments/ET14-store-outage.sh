#!/bin/bash
# ET14: acceptance experiment for Target 14 M1 (surviving outages of the persistence store and the blob store).
# ET setup (two tmpfs controllers, S0, Postgres, Azurite, token auth). Builds run while Postgres is paused, restarted,
# and while Azurite is stopped; a controller boots while Postgres is down.
# Exits non-zero if any check fails.
#
# Prerequisite: CORE_REF=cnj/t14-m1-resilience experiments/ET1-build.sh
source "$(dirname "$0")/../lib.sh"
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.fsmonitor GIT_CONFIG_VALUE_0=false
A=$PORT_A B=$PORT_B OUT="$RAW_DIR/ET14" ET1="$ET1_DIR"
et1() { docker compose --project-directory "$VALIDATION_DIR" -f "$VALIDATION_DIR/compose.et1.yaml" "$@"; }
rm -rf "$OUT"; mkdir -p "$OUT" "$OUT_DIR"
: > "$OUT/results.md"
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
FAILED=0
check() { local d=$1; shift; if "$@" >>"$OUT/check.log" 2>&1; then r "| ✅ | $d |"; else r "| ❌ | $d |"; FAILED=$((FAILED+1)); fi; }
info() { r "| ℹ️ | $* |"; }
eventually() { local t=$1 start=$SECONDS; shift; until "$@"; do (( SECONDS - start > t )) && return 1; sleep 2; done; }
json() { curl -gsf -m 20 -u "$AUTH" "http://localhost:$1$2"; }
code() { curl -gs -m 20 -o /dev/null -w '%{http_code}' -u "$AUTH" "http://localhost:$1$2"; }
jq_() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
result_on() { json "$1" "$2/api/json?tree=result" 2>/dev/null | jq_ 'd["result"]' 2>/dev/null; }
result_anywhere() { local r; for p in $A $B; do r=$(result_on $p "$1"); [[ -n "$r" && "$r" != None ]] && { echo "$r"; return; }; done; echo none; }
is_result() { [[ "$(result_anywhere "$1")" == "$2" ]]; }
console_anywhere() { for p in $A $B; do json $p "$1/consoleText" 2>/dev/null && return; done; }
psql_q() { et1 exec -T postgres psql -U jenkins -At -c "$1"; }
writers_of() { psql_q "select count(distinct origin) from jenkins_store_events where key = 'jobs/$1/builds/$2/build.xml' and kind = 'WRITTEN'"; }
ticks_once() { # job number count: the console has "tick 1".."tick N" once each, in order, and the end line
  console_anywhere "/job/$1/$2" > "$OUT/console-$1-$2.txt" 2>/dev/null
  [[ "$(grep -o '^tick [0-9]*' "$OUT/console-$1-$2.txt" | tr '\n' ' ')" == "$(seq -f 'tick %g' 1 "$3" | tr '\n' ' ')" ]] \
    && grep -q '^the end' "$OUT/console-$1-$2.txt"
}
no_log() { [[ "$(et1 logs jenkins-a jenkins-b 2>&1 | grep -c -- "$1")" == 0 ]]; }
live_members() { psql_q "select count(*) from jenkins_members where heartbeat_at > now() - interval '20 seconds'"; }
pipeline() { # name script
  python3 - "$OUT_DIR/$1.xml" "$2" <<'PY'
import sys
from xml.sax.saxutils import escape
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job"><definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>%s</script><sandbox>true</sandbox></definition></flow-definition>""" % escape(sys.argv[2]))
PY
  create_job $A "$1" "$OUT_DIR/$1.xml"; eventually 15 exists $B "/job/$1"
}
exists() { [[ "$(code "$1" "$2/api/json")" == 200 ]]; }
freestyle() { # name command
  python3 - "$OUT_DIR/$1.xml" "$2" <<'PY'
import sys
from xml.sax.saxutils import escape
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<project><assignedNode>built-in</assignedNode><canRoam>false</canRoam><builders><hudson.tasks.Shell><command>%s</command></hudson.tasks.Shell></builders></project>""" % escape(sys.argv[2]))
PY
  create_job $A "$1" "$OUT_DIR/$1.xml"; eventually 15 exists $B "/job/$1"
}
# start_pair <suffix>: a 60-tick Pipeline on A and a 60-tick freestyle build on B
start_pair() {
  pipeline "p$1" 'for (int i = 1; i <= 60; i++) { echo "tick ${i}"; sleep 2 }; echo "the end"'
  freestyle "f$1" 'for i in $(seq 1 60); do echo "tick $i"; sleep 2; done; echo "the end"'
  post $A "/job/p$1/build?delay=0sec" >/dev/null; post $B "/job/f$1/build?delay=0sec" >/dev/null
  eventually 60 bash -c "curl -gsf -u '$AUTH' http://localhost:$A/job/p$1/1/consoleText | grep -q 'tick 3'"
  eventually 60 bash -c "curl -gsf -u '$AUTH' http://localhost:$B/job/f$1/1/consoleText | grep -q 'tick 3'"
}
both_whole() { ticks_once "p$1" 1 60 && ticks_once "f$1" 1 60; }
one_writer_each() { [[ "$(writers_of "p$1" 1)" == 1 && "$(writers_of "f$1" 1)" == 1 ]]; }
build_count() { json $A "/job/$1/api/json?tree=builds[number]" | jq_ 'len(d["builds"])'; }
single_builds() { [[ "$(build_count "p$1")" == 1 && "$(build_count "f$1")" == 1 ]]; }
# survived <suffix>: both builds completed SUCCESS, with whole consoles, published once each
survived() {
  check "$1: the Pipeline completed SUCCESS" eventually 300 is_result "/job/p$1/1" SUCCESS
  check "$1: the freestyle build completed SUCCESS" eventually 300 is_result "/job/f$1/1" SUCCESS
  check "$1: both consoles are whole (every tick once, and the end)" eventually 60 both_whole "$1"
  check "$1: each build was published, by one controller only" eventually 60 one_writer_each "$1"
  check "$1: and there is no second build" single_builds "$1"
}
system_message_reaches_b() { [[ "$(groovy $B 'print Jenkins.get().systemMessage')" == "after the outage" ]]; }
no_takeovers() { ! et1 logs jenkins-a jenkins-b 2>&1 | grep -E 'Ownership of .* was lost|Adopting |closed as aborted|Reclaimed '; }
no_reclaims() { ! et1 logs jenkins-a jenkins-b 2>&1 | grep -E 'queue item\(s\) claimed by gone controllers back'; }
not_serving() { [[ "$(curl -gs -m 10 -o /dev/null -w '%{http_code}' -u "$AUTH" http://localhost:$A/api/json)" != 200 ]]; }
logs_waiting() { [[ "$(et1 logs jenkins-a 2>&1 | grep -c 'Waiting for the persistence store')" -gt 0 ]]; }
a_up() { curl -gsf -m 5 -u "$AUTH" http://localhost:$A/api/json >/dev/null; }
two_live() { [[ "$(live_members)" == 2 ]]; }
history_on_a() { for j in p1 f1 p2 f2 p3 f3 q4 p5 f5; do [[ "$(result_on $A "/job/$j/1")" == SUCCESS ]] || return 1; done; }

[[ -f "$ET1/war/jenkins.war" ]] || { echo "run ET1-build.sh first" >&2; exit 2; }

# ------------------------------------------------------------------ setup (as ET6)
et1 down -v --remove-orphans >/dev/null 2>&1 || true
rm -rf "$ET1/seed" "$ET1/repo"; mkdir -p "$ET1/seed" "$ET1/repo"
docker rm -fv ${P}seed >/dev/null 2>&1 || true
docker run -d --name ${P}seed -p $PORT_SEED:8080 --tmpfs /var/jenkins_home:uid=1000,gid=1000 \
  -e JAVA_OPTS="-Djenkins.install.runSetupWizard=false" -e CASC_JENKINS_CONFIG=/casc -v "$VALIDATION_DIR/jenkins/casc-et1:/casc:ro" \
  -v "$ET1/war:/war-override:ro" -v "$ET1/plugins:/extra-plugins:ro" cnj/jenkins:dev >/dev/null
wait_up $PORT_SEED
groovy $PORT_SEED 'hudson.util.Secret.fromString("x").getEncryptedValue(); org.jenkinsci.main.modules.instance_identity.InstanceIdentity.get()' >/dev/null
docker exec ${P}seed tar -C /var/jenkins_home -cf - secret.key secret.key.not-so-secret identity.key.enc secrets | tar -C "$ET1/seed" -xf -
docker rm -fv ${P}seed >/dev/null
et1 up -d postgres $BLOB_SERVICE >/dev/null 2>&1
until et1 exec -T postgres pg_isready -U jenkins >/dev/null 2>&1; do sleep 1; done
et1 up -d jenkins-a jenkins-b >/dev/null 2>&1
wait_up $A; wait_up $B
r "## ET14 results ($(date -u +%Y-%m-%dT%H:%MZ))"
r ""
r "| | Check |"
r "|---|---|"

# ------------------------------------------------------------------ 1. a short Postgres outage
log "scenario 1: Postgres paused for 10 s while builds run"
start_pair 1
docker pause ${P}postgres-1 >/dev/null; sleep 10; docker unpause ${P}postgres-1 >/dev/null
survived 1

# ------------------------------------------------------------------ 2. a long Postgres outage
log "scenario 2: Postgres paused for 60 s while builds run"
start_pair 2
docker pause ${P}postgres-1 >/dev/null
sleep 30
for page in / /api/json /job/p2/ /login; do
  for port in $A $B; do
    t=$(curl -gs -m 30 -o /dev/null -w '%{http_code} %{time_total}' -u "$AUTH" "http://localhost:$port$page" || true)
    echo "$port $page $t" >> "$OUT/pages-during-outage.txt"
  done
done
info "2 pages during the outage (port, page, status, seconds): $(tr '\n' ';' < "$OUT/pages-during-outage.txt")"
check "2 every page answered within 10 s during the outage (degraded, not hung)" bash -c "awk '\$3 != 200 || \$4 > 10 { bad = 1 } END { exit bad }' '$OUT/pages-during-outage.txt'"
sleep 30
docker unpause ${P}postgres-1 >/dev/null
survived 2
groovy $A 'Jenkins.get().setSystemMessage("after the outage")' >/dev/null || true
check "2 a configuration change works after the outage, and reaches the other controller" eventually 30 system_message_reaches_b

# ------------------------------------------------------------------ 3. Postgres restarted
log "scenario 3: Postgres restarted while builds run"
start_pair 3
et1 restart postgres >/dev/null 2>&1
survived 3

# ------------------------------------------------------------------ 4. a build queued during an outage
log "scenario 4: a build requested while Postgres is paused"
freestyle q4 'echo queued during the outage'
docker pause ${P}postgres-1 >/dev/null
qcode=0; post $B "/job/q4/build?delay=0sec" -m 20 >/dev/null 2>&1 || qcode=$?
sleep 20
docker unpause ${P}postgres-1 >/dev/null
info "4 the build request during the outage: curl exit $qcode"
check "4 it ran once the store was back" eventually 180 is_result /job/q4/1 SUCCESS
sleep 20
check "4 and only once" bash -c "[[ $(build_count q4) == 1 ]]"

# ------------------------------------------------------------------ 5. the blob store unavailable
log "scenario 5: Azurite stopped for 60 s while builds run"
start_pair 5
et1 stop azurite >/dev/null 2>&1; sleep 60; et1 start azurite >/dev/null 2>&1
survived 5

# ------------------------------------------------------------------ the damage the outages must not do
check "no build was aborted or taken over" no_takeovers
check "no queue item was put back for a controller wrongly taken for gone" no_reclaims

# ------------------------------------------------------------------ 6. booting while Postgres is down
log "scenario 6: A restarted while Postgres is down"
et1 stop jenkins-a >/dev/null 2>&1
et1 stop postgres >/dev/null 2>&1
et1 rm -f jenkins-a >/dev/null 2>&1; et1 up -d --no-deps jenkins-a >/dev/null 2>&1 # (not Postgres with it)
sleep 40
info "6 A while the store is down: /login $(code $A /login)"
check "6 A is not serving while it waits for the store" not_serving
check "6 A logs that it waits" logs_waiting
et1 start postgres >/dev/null 2>&1
up_start=$SECONDS
check "6 A comes up once the store is back" eventually 240 a_up
info "6 A was up $((SECONDS - up_start)) s after the store came back"
check "6 exactly two live members (no ghost from the attempts)" eventually 60 two_live
check "6 the history is all there on the restarted A" eventually 60 history_on_a

et1 logs jenkins-a > "$OUT/jenkins-a.log" 2>&1; et1 logs jenkins-b > "$OUT/jenkins-b.log" 2>&1
cp "$ET1/BUILD" "$OUT/BUILD" 2>/dev/null || true
r ""
r "Failures: $FAILED"
echo "ET14 exit=$FAILED"
exit $FAILED
