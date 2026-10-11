#!/bin/bash
# ET20: acceptance experiment for Target 15 (WebSockets and the CLI across controllers): a WebSocket request for a build
# running on A, made to B, is relayed to A; the CLI's console -f on B follows a build running on A; build -s -v on B
# of a job only A can run waits for it and shows its log.
# ET setup (two tmpfs controllers, S0, Postgres, Azurite, token auth); an inbound agent `ala` connected to A only.
# Exits non-zero if any check fails.
#
# Prerequisite: CORE_REF=cnj/t15-remaining experiments/ET1-build.sh
source "$(dirname "$0")/../lib.sh"
export CNJ_JAVA_OPTS="-Dio.jenkins.plugins.cnj.store.WebSocketEcho.enabled=true"
A=8081 B=8082 OUT="$RAW_DIR/ET20" ET1="$VALIDATION_DIR/.et1"
et1() { docker compose --project-directory "$VALIDATION_DIR" -f "$VALIDATION_DIR/compose.et1.yaml" "$@"; }
rm -rf "$OUT"; mkdir -p "$OUT" "$OUT_DIR"
: > "$OUT/results.md"
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
FAILED=0
check() { local d=$1; shift; if "$@" >>"$OUT/check.log" 2>&1; then r "| ✅ | $d |"; else r "| ❌ | $d |"; FAILED=$((FAILED+1)); fi; }
info() { r "| ℹ️ | $* |"; }
eventually() { local t=$1 start=$SECONDS; shift; until "$@"; do (( SECONDS - start > t )) && return 1; sleep 2; done; }
json() { curl -gsf -u "$AUTH" "http://localhost:$1$2"; }
console_has() { json "$1" "$2/consoleText" 2>/dev/null | grep -q -- "$3"; }
exists() { [[ "$(curl -gs -o /dev/null -w '%{http_code}' -u "$AUTH" "http://localhost:$1$2/api/json")" == 200 ]]; }
pipeline() { # name script
  python3 - "$OUT_DIR/$1.xml" "$2" <<'PY'
import sys
from xml.sax.saxutils import escape
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job"><definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>%s</script><sandbox>true</sandbox></definition></flow-definition>""" % escape(sys.argv[2]))
PY
  create_job $A "$1" "$OUT_DIR/$1.xml"; eventually 15 exists $B "/job/$1"
}
# runs a command for at most $1 seconds (macOS has no timeout(1)); its exit code, or 124 if it was stopped
run_for() { local secs=$1; shift; "$@" & local pid=$!; ( sleep "$secs"; kill "$pid" 2>/dev/null ) & local w=$!
  local rc=0; wait "$pid" || rc=$?; kill "$w" 2>/dev/null; return $rc; }
cli() { java -jar "$OUT_DIR/jenkins-cli.jar" -s "http://localhost:$1/" -auth "$AUTH" -http "${@:2}"; }

[[ -f "$ET1/war/jenkins.war" ]] || { echo "run ET1-build.sh first" >&2; exit 2; }

# ------------------------------------------------------------------ setup (as ET6, plus rr)
et1 down -v --remove-orphans >/dev/null 2>&1 || true
rm -rf "$ET1/seed"; mkdir -p "$ET1/seed"
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
docker rm -f et20-ala >/dev/null 2>&1 || true
r "## ET20 results ($(date -u +%Y-%m-%dT%H:%MZ))"
r ""
r "| | Check |"
r "|---|---|"
curl -gsf -o "$OUT_DIR/jenkins-cli.jar" "http://localhost:$B/jnlpJars/jenkins-cli.jar"

# ------------------------------------------------------------------ 1. a WebSocket for a build running on A, opened on B
log "scenario 1: WebSocket relay"
pipeline wsp 'pipeline { agent none; stages { stage("run") { steps { echo "running"; sleep 60 } } } }'
post $A "/job/wsp/build?delay=0sec" >/dev/null
eventually 60 console_has $A /job/wsp/1 running
java "$VALIDATION_DIR/tools/WsEcho.java" "ws://localhost:$B/job/wsp/1/cnj-ws-echo/" "$AUTH" hello > "$OUT/ws-b.txt" 2>&1 || true
info "1 B answered: $(head -c 200 "$OUT/ws-b.txt")"
check "1 a WebSocket opened on B for A's running build is answered by A" grep -q "hello from jenkins-a" "$OUT/ws-b.txt"
java "$VALIDATION_DIR/tools/WsEcho.java" "ws://localhost:$A/job/wsp/1/cnj-ws-echo/" "$AUTH" direct > "$OUT/ws-a.txt" 2>&1 || true
check "1 ... as when opened on A itself" grep -q "direct from jenkins-a" "$OUT/ws-a.txt"

# ------------------------------------------------------------------ 2. console -f on B for a build running on A
log "scenario 2: CLI console -f on B"
pipeline tick 'pipeline { agent none; stages { stage("tick") { steps { script { for (int i = 1; i <= 15; i++) { echo "tick ${i}"; sleep 1 } } } } } }'
post $A "/job/tick/build?delay=0sec" >/dev/null
eventually 60 console_has $A /job/tick/1 "tick 2"
rc=0; run_for 120 java -jar "$OUT_DIR/jenkins-cli.jar" -s "http://localhost:$B/" -auth "$AUTH" -http console tick 1 -f > "$OUT/console-f.txt" 2>&1 || rc=$?
info "2 console -f exit $rc, $(grep -c '^tick\|tick [0-9]' "$OUT/console-f.txt") tick lines"
check "2 console -f on B follows A's build to the end" bash -c "grep -q 'tick 15' '$OUT/console-f.txt' && grep -q 'Finished: SUCCESS' '$OUT/console-f.txt' && [[ $rc == 0 ]]"

# ------------------------------------------------------------------ 3. build -s -v on B of a job only A can run
log "scenario 3: CLI build -s -v on B, the job runs on A (agent ala)"
groovy $A 'def n = new hudson.slaves.DumbSlave("ala", "/home/jenkins/agent", new hudson.slaves.JNLPLauncher()); n.setLabelString("ala"); n.setNumExecutors(1); Jenkins.get().addNode(n)' >/dev/null
eventually 15 bash -c "[[ \$(cd $VALIDATION_DIR && source lib.sh && groovy $B 'println Jenkins.get().getNode(\"ala\") != null' | tr -d '\r\n ') == true ]]"
docker run -d --name et20-ala --network et1_default jenkins/inbound-agent:latest-jdk21 -url http://jenkins-a:8080/ \
  -secret "$(agent_secret $A ala)" -name ala -webSocket -workDir /home/jenkins/agent >/dev/null
eventually 60 bash -c "[[ \$(cd $VALIDATION_DIR && source lib.sh && groovy $A 'println Jenkins.get().getComputer(\"ala\").isOnline()' | tr -d '\r\n ') == true ]]"
cat > "$OUT_DIR/onala.xml" <<'XML'
<?xml version='1.1' encoding='UTF-8'?>
<project><assignedNode>ala</assignedNode><canRoam>false</canRoam><builders><hudson.tasks.Shell><command>for i in 1 2 3 4 5; do echo "ala-line $i"; sleep 1; done</command></hudson.tasks.Shell></builders></project>
XML
create_job $A onala "$OUT_DIR/onala.xml"; eventually 15 exists $B /job/onala
rc=0; run_for 180 java -jar "$OUT_DIR/jenkins-cli.jar" -s "http://localhost:$B/" -auth "$AUTH" -http build onala -s -v > "$OUT/build-s.txt" 2>&1 || rc=$?
info "3 build -s -v exit $rc: $(grep -m1 'Started' "$OUT/build-s.txt"), $(grep -m1 'Completed' "$OUT/build-s.txt")"
check "3 build -s -v on B waits for the build A ran, with its log" bash -c "grep -q 'ala-line 5' '$OUT/build-s.txt' && grep -q 'Completed onala #1 : SUCCESS' '$OUT/build-s.txt' && [[ $rc == 0 ]]"

# ------------------------------------------------------------------ 4. a controller without the cluster's keys (Target 15)
log "scenario 4: a controller started against the store without the cluster's seeded keys"
docker rm -f et20-noseed >/dev/null 2>&1 || true
et1 run -d --no-deps --name et20-noseed -e CNJ_SEED_DIR= -e CNJ_NAME=noseed -e CNJ_INTERNAL_URL=http://et20-noseed:8080/ jenkins-a >/dev/null 2>&1 || true
refuses() { local l; l=$(docker logs et20-noseed 2>&1); grep -q "does not have the key material of the cluster" <<<"$l"; }
check "4 it refuses to start, saying why" eventually 180 refuses
check "4 ... and never became a member" bash -c "! curl -gsf -u '$AUTH' http://localhost:$A/manage/cluster/api/json | grep -q '\"noseed\"'"
docker logs et20-noseed > "$OUT/noseed.log" 2>&1 || true
docker rm -f et20-noseed >/dev/null 2>&1 || true

docker rm -f et20-ala >/dev/null 2>&1 || true
et1 logs jenkins-a > "$OUT/jenkins-a.log" 2>&1; et1 logs jenkins-b > "$OUT/jenkins-b.log" 2>&1
cp "$ET1/BUILD" "$OUT/BUILD" 2>/dev/null || true
r ""
r "Failures: $FAILED"
exit $(( FAILED > 0 ))
