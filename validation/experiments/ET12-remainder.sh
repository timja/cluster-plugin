#!/bin/bash
# ET12: acceptance experiment for Target 12 (placement by label, files added to completed builds, the restore sweep).
# ET setup (two tmpfs controllers, S0, Postgres, Azurite, token auth); an inbound agent `lin` connected to B only.
# Exits non-zero if any check fails.
#
# Prerequisite: CORE_REF=cnj/t12-m1-remainder experiments/ET1-build.sh
source "$(dirname "$0")/../lib.sh"
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.fsmonitor GIT_CONFIG_VALUE_0=false
export CNJ_JAVA_OPTS="-Djenkins.persistence.BuildRecords.lateFilesPeriodSeconds=5"
A=8081 B=8082 OUT="$RAW_DIR/ET12" ET1="$VALIDATION_DIR/.et1"
et1() { docker compose --project-directory "$VALIDATION_DIR" -f "$VALIDATION_DIR/compose.et1.yaml" "$@"; }
rm -rf "$OUT"; mkdir -p "$OUT" "$OUT_DIR"
: > "$OUT/results.md"
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
FAILED=0
check() { local d=$1; shift; if "$@" >>"$OUT/check.log" 2>&1; then r "| ✅ | $d |"; else r "| ❌ | $d |"; FAILED=$((FAILED+1)); fi; }
info() { r "| ℹ️ | $* |"; }
eventually() { local t=$1 start=$SECONDS; shift; until "$@"; do (( SECONDS - start > t )) && return 1; sleep 2; done; }
json() { curl -gsf -u "$AUTH" "http://localhost:$1$2"; }
jq_() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
g() { groovy "$1" "$2" | tr -d '\r' | sed -e 's/[[:space:]]*$//'; }
exists() { [[ "$(curl -gs -o /dev/null -w '%{http_code}' -u "$AUTH" "http://localhost:$1$2/api/json")" == 200 ]]; }
result_is() { [[ "$(json "$1" "$2/api/json?tree=result" 2>/dev/null | jq_ 'd["result"]')" == "$3" ]]; }
console_has() { json "$1" "$2/consoleText" 2>/dev/null | grep -q -- "$3"; }
psql_q() { et1 exec -T postgres psql -U jenkins -At -c "$1"; }
writer_of() { psql_q "select m.name from jenkins_store_events e join jenkins_members m on m.origin = e.origin where e.key = 'jobs/$1/builds/$2/build.xml' and e.kind = 'WRITTEN' order by seq desc limit 1"; }
pipeline() { # name script
  python3 - "$OUT_DIR/$1.xml" "$2" <<'PY'
import sys
from xml.sax.saxutils import escape
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job"><definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>%s</script><sandbox>true</sandbox></definition></flow-definition>""" % escape(sys.argv[2]))
PY
  create_job $A "$1" "$OUT_DIR/$1.xml"; eventually 15 exists $B "/job/$1"
}

[[ -f "$ET1/war/jenkins.war" ]] || { echo "run ET1-build.sh first" >&2; exit 2; }

# ------------------------------------------------------------------ setup (as ET6, plus the agent lin on B)
et1 down -v --remove-orphans >/dev/null 2>&1 || true
docker rm -fv et12-lin >/dev/null 2>&1 || true
rm -rf "$ET1/seed" "$ET1/repo"; mkdir -p "$ET1/seed" "$ET1/repo"
docker rm -fv et1-seed >/dev/null 2>&1 || true
docker run -d --name et1-seed -p 8089:8080 --tmpfs /var/jenkins_home:uid=1000,gid=1000 \
  -e JAVA_OPTS="-Djenkins.install.runSetupWizard=false" -e CASC_JENKINS_CONFIG=/casc -v "$VALIDATION_DIR/jenkins/casc-et1:/casc:ro" \
  -v "$ET1/war:/war-override:ro" -v "$ET1/plugins:/extra-plugins:ro" cnj/jenkins:dev >/dev/null
wait_up 8089
groovy 8089 'hudson.util.Secret.fromString("x").getEncryptedValue(); org.jenkinsci.main.modules.instance_identity.InstanceIdentity.get()' >/dev/null
docker exec et1-seed tar -C /var/jenkins_home -cf - secret.key secret.key.not-so-secret identity.key.enc secrets | tar -C "$ET1/seed" -xf -
docker rm -fv et1-seed >/dev/null
et1 up -d postgres azurite >/dev/null 2>&1; wait_postgres
et1 up -d jenkins-a jenkins-b >/dev/null 2>&1
wait_up $A; wait_up $B
r "## ET12 results ($(date -u +%Y-%m-%dT%H:%MZ))"
r ""
r "| | Check |"
r "|---|---|"
g $A 'def n = new hudson.slaves.DumbSlave("lin", "/home/jenkins/agent", new hudson.slaves.JNLPLauncher()); n.setLabelString("lin"); n.setNumExecutors(1); Jenkins.get().addNode(n)' >/dev/null
eventually 15 bash -c "[[ \$(cd $VALIDATION_DIR && source lib.sh && groovy $B 'println Jenkins.get().getNode(\"lin\") != null' | tr -d '\r\n ') == true ]]"
docker run -d --name et12-lin --network et1_default jenkins/inbound-agent:latest-jdk21 -url http://jenkins-b:8080/ \
  -secret "$(agent_secret $B lin)" -name lin -webSocket -workDir /home/jenkins/agent >/dev/null
check "lin is connected to B only" eventually 60 bash -c "[[ \$(cd $VALIDATION_DIR && source lib.sh && groovy $B 'println Jenkins.get().getComputer(\"lin\").isOnline()' | tr -d '\r\n ') == true && \$(cd $VALIDATION_DIR && source lib.sh && groovy $A 'println Jenkins.get().getComputer(\"lin\").isOnline()' | tr -d '\r\n ') == false ]]"

# ------------------------------------------------------------------ 1. placement by label
log "scenario 1: Pipelines started on A needing an agent only B has"
pipeline lab1 "node('lin') { sh 'echo on-agent-\$(hostname)' }"
pipeline lab2 'node("built-in") { echo "first, on the controller" }; node("lin") { sh "echo then-on-agent" }'
post $A "/job/lab1/build?delay=0sec" >/dev/null
post $A "/job/lab2/build?delay=0sec" >/dev/null
check "1 lab1 completes" eventually 180 result_is $B /job/lab1/1 SUCCESS
check "1 on lin, by B (handed over from A)" bash -c "[[ \$(cd $VALIDATION_DIR && docker compose -f compose.et1.yaml exec -T postgres psql -U jenkins -At -c \"select m.name from jenkins_store_events e join jenkins_members m on m.origin = e.origin where e.key = 'jobs/lab1/builds/1/build.xml' and e.kind = 'WRITTEN' order by seq desc limit 1\") == jenkins-b ]] && curl -gsf -u '$AUTH' http://localhost:$B/job/lab1/1/consoleText | grep -q on-agent-"
check "1 lab2 (first on A's controller, then on lin) completes with both parts" eventually 180 bash -c "curl -gsf -u '$AUTH' http://localhost:$B/job/lab2/1/consoleText | grep -q 'first, on the controller' && curl -gsf -u '$AUTH' http://localhost:$B/job/lab2/1/consoleText | grep -q then-on-agent && [[ \$(curl -gsf -u '$AUTH' 'http://localhost:$B/job/lab2/1/api/json?tree=result') == *SUCCESS* ]]"
info "1 A logged: $(et1 logs jenkins-a 2>&1 | grep -o 'Handing [^:]* over' | sort -u | tr '\n' ';')"

# Target 15: parallel node blocks needing agents of different controllers do not bounce the build between them
log "scenario 1b: parallel branches, one on A's built-in node, one on lin (B only)"
pipeline lab3 'parallel here: { node("built-in") { sleep 25; echo "here-done" } }, there: { node("lin") { sh "echo there-done" } }'
post $A "/job/lab3/build?delay=0sec" >/dev/null
check "1b lab3 completes, with both branches" eventually 240 bash -c "curl -gsf -u '$AUTH' http://localhost:$B/job/lab3/1/consoleText | grep -q here-done && curl -gsf -u '$AUTH' http://localhost:$B/job/lab3/1/consoleText | grep -q there-done && [[ \$(curl -gsf -u '$AUTH' 'http://localhost:$B/job/lab3/1/api/json?tree=result') == *SUCCESS* ]]"
hand=$(et1 logs jenkins-a 2>&1 | grep -c 'Handing jobs/lab3\|Handing lab3' || true)
info "1b A handed lab3 over $hand time(s)"
check "1b handed over once, after the branch on A's node finished (not back and forth)" test "$hand" -le 1

# Target 15: build(wait: true) with the downstream build on another controller, also with the upstream adopted
log "scenario 1c: a Pipeline on A waits for a downstream build that runs on B (lin)"
cat > "$OUT_DIR/down.xml" <<'XML'
<?xml version='1.1' encoding='UTF-8'?>
<project><assignedNode>lin</assignedNode><canRoam>false</canRoam><builders><hudson.tasks.Shell><command>sleep 15; echo down-ran-on-$(hostname)</command></hudson.tasks.Shell></builders></project>
XML
create_job $A down "$OUT_DIR/down.xml"; eventually 15 exists $B /job/down
pipeline up1 'pipeline { agent none; stages { stage("call") { steps { script { def b = build job: "down", wait: true; echo "down result ${b.result} #${b.number}" } } } } }'
post $A "/job/up1/build?delay=0sec" >/dev/null
check "1c up1 completes with the downstream result" eventually 240 bash -c "curl -gsf -u '$AUTH' http://localhost:$A/job/up1/1/consoleText | grep -q 'down result SUCCESS' && [[ \$(curl -gsf -u '$AUTH' 'http://localhost:$A/job/up1/1/api/json?tree=result') == *SUCCESS* ]]"
check "1c ... the downstream build ran on B, not pinned to A" bash -c "[[ \$(cd $VALIDATION_DIR && docker compose -f compose.et1.yaml exec -T postgres psql -U jenkins -At -c \"select m.name from jenkins_store_events e join jenkins_members m on m.origin = e.origin where e.key = 'jobs/down/builds/1/build.xml' and e.kind = 'WRITTEN' order by seq desc limit 1\") == jenkins-b ]]"
log "scenario 1d: ... and A is killed while it waits"
pipeline up2 'pipeline { agent none; stages { stage("call") { steps { script { def b = build job: "down", wait: true; echo "down result ${b.result} #${b.number}" } } } } }'
post $A "/job/up2/build?delay=0sec" >/dev/null
eventually 120 bash -c "curl -gsf -u '$AUTH' http://localhost:$A/job/up2/1/consoleText | grep -q 'Starting building'" || true
docker kill -s KILL et1-jenkins-a-1 >/dev/null
check "1d B adopts up2, which completes with the downstream result" eventually 300 bash -c "curl -gsf -u '$AUTH' http://localhost:$B/job/up2/1/consoleText | grep -q 'down result SUCCESS' && [[ \$(curl -gsf -u '$AUTH' 'http://localhost:$B/job/up2/1/api/json?tree=result') == *SUCCESS* ]]"
et1 up -d jenkins-a >/dev/null 2>&1; wait_up $A

# ------------------------------------------------------------------ 2. files added to a completed build
log "scenario 2: a file added to a completed build on A"
cat > "$OUT_DIR/late.xml" <<'XML'
<?xml version='1.1' encoding='UTF-8'?>
<project><assignedNode>built-in</assignedNode><canRoam>false</canRoam><builders><hudson.tasks.Shell><command>echo late</command></hudson.tasks.Shell></builders></project>
XML
create_job $A late "$OUT_DIR/late.xml"; eventually 15 exists $B /job/late
post $A "/job/late/build?delay=0sec" >/dev/null
eventually 60 result_is $A /job/late/1 SUCCESS
eventually 30 result_is $B /job/late/1 SUCCESS
g $B 'println new File(Jenkins.get().getItem("late").getBuildByNumber(1).rootDir, "build.xml").exists()' >/dev/null # B has it materialised
g $A 'new File(Jenkins.get().getItem("late").getBuildByNumber(1).rootDir, "late.txt").text = "added later"' >/dev/null
check "2 B's copy of the build gets the file" eventually 60 bash -c "[[ \$(cd $VALIDATION_DIR && source lib.sh && groovy $B 'def f = new File(Jenkins.get().getItem(\"late\").getBuildByNumber(1).rootDir, \"late.txt\"); println(f.exists() ? f.text : \"none\")' | tr -d '\r') == 'added later'* ]]"

# ------------------------------------------------------------------ 3-4. both controllers gone while builds run
log "scenario 3: both controllers killed while a Pipeline and a freestyle build run"
pipeline sleeper 'echo "before"; sleep 45; echo "after its controller was gone"'
cat > "$OUT_DIR/long.xml" <<'XML'
<?xml version='1.1' encoding='UTF-8'?>
<project><assignedNode>built-in</assignedNode><canRoam>false</canRoam><builders><hudson.tasks.Shell><command>sleep 300</command></hudson.tasks.Shell></builders></project>
XML
create_job $A long "$OUT_DIR/long.xml"; eventually 15 exists $B /job/long
post $A "/job/sleeper/build?delay=0sec" >/dev/null; post $A "/job/long/build?delay=0sec" >/dev/null
eventually 60 console_has $A /job/sleeper/1 before
eventually 60 bash -c "curl -gsf -u '$AUTH' http://localhost:$A/job/long/1/api/json?tree=building | grep -q true"
sleep 5 # a save point
docker kill -s KILL et1-jenkins-a-1 et1-jenkins-b-1 >/dev/null
et1 rm -sf jenkins-a jenkins-b >/dev/null 2>&1
sleep 25 # past the membership timeout
et1 up -d jenkins-a jenkins-b >/dev/null 2>&1; wait_up $A; wait_up $B
code=$(curl -gs -o /dev/null -w '%{http_code}' -u "$AUTH" "http://localhost:$B/job/sleeper/1/api/json")
check "4 a request for a build of the old controller is answered (not forwarded to its namesake: $code)" bash -c "[[ '$code' != 000 && '$code' != 502 ]]"
check "3 the Pipeline is resumed and completes" eventually 240 bash -c "for p in $A $B; do curl -gsf -u '$AUTH' http://localhost:\$p/job/sleeper/1/api/json?tree=result | grep -q SUCCESS && curl -gsf -u '$AUTH' http://localhost:\$p/job/sleeper/1/consoleText | grep -q 'after its controller was gone' && exit 0; done; exit 1"
check "3 the freestyle build is closed as aborted" eventually 240 bash -c "for p in $A $B; do curl -gsf -u '$AUTH' http://localhost:\$p/job/long/1/api/json?tree=result | grep -q ABORTED && exit 0; done; exit 1"
check "3 with a console saying why, on both controllers" bash -c "curl -gsf -u '$AUTH' http://localhost:$A/job/long/1/consoleText | grep -q 'closed as aborted' && curl -gsf -u '$AUTH' http://localhost:$B/job/long/1/consoleText | grep -q 'closed as aborted'"
check "4 no routing warnings after the restart" bash -c "! (cd $VALIDATION_DIR && docker compose -f compose.et1.yaml logs jenkins-a jenkins-b 2>&1 | grep -q 'Could not route')"

et1 logs jenkins-a > "$OUT/jenkins-a.log" 2>&1; et1 logs jenkins-b > "$OUT/jenkins-b.log" 2>&1; docker logs et12-lin > "$OUT/lin.log" 2>&1
docker rm -fv et12-lin >/dev/null 2>&1 || true
cp "$ET1/BUILD" "$OUT/BUILD" 2>/dev/null || true
r ""
r "Failures: $FAILED"
echo "ET12 exit=$FAILED"
exit $FAILED
