#!/bin/bash
# ET7: acceptance experiment for Target 7 (requests reach the controller owning a build or an agent).
# ET setup (two tmpfs controllers, S0, Postgres, Azurite, token auth), agent1 through the failover URL (connects to A),
# and the round-robin LB `rr`. Builds start on A; every request about them goes to B, or through rr.
# Exits non-zero if any check fails.
#
# Prerequisite: CORE_REF=cnj/t6-m6-stateless-sessions experiments/ET1-build.sh
source "$(dirname "$0")/../lib.sh"
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.fsmonitor GIT_CONFIG_VALUE_0=false
# notifyCommit without a token, as a webhook relay would call it
export CNJ_JAVA_OPTS="-Dhudson.plugins.git.GitStatus.NOTIFY_COMMIT_ACCESS_CONTROL=disabled"
A=$PORT_A B=$PORT_B RR=$PORT_RR OUT="$RAW_DIR/ET7" ET1="$ET1_DIR" JOBS="$VALIDATION_DIR/scenarios/jobs"
et1() { docker compose --project-directory "$VALIDATION_DIR" -f "$VALIDATION_DIR/compose.et1.yaml" --profile agents "$@"; }
rm -rf "$OUT"; mkdir -p "$OUT" "$OUT_DIR"
: > "$OUT/results.md"
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
FAILED=0
check() { local d=$1; shift; if "$@" >>"$OUT/check.log" 2>&1; then r "| ✅ | $d |"; else r "| ❌ | $d |"; FAILED=$((FAILED+1)); fi; }
info() { r "| ℹ️ | $* |"; }
eventually() { local t=$1 start=$SECONDS; shift; until "$@"; do (( SECONDS - start > t )) && return 1; sleep 1; done; }
http() { curl -gs -o /dev/null -w '%{http_code}' -u "$AUTH" "http://localhost:$1$2"; }
json() { curl -gsf -u "$AUTH" "http://localhost:$1$2"; }
text() { curl -gsf -u "$AUTH" "http://localhost:$1$2"; }
header() { curl -gs -o /dev/null -D - -u "$AUTH" "http://localhost:$1$2" | tr -d '\r' | awk -v h="$3" 'tolower($1) == tolower(h)":" {print $2}'; }
jq_() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
psql_q() { et1 exec -T postgres psql -U jenkins -At -c "$1"; }
g() { groovy "$1" "$2" | tr -d '\r' | sed -e 's/[[:space:]]*$//'; }
exists() { [[ "$(http "$1" "$2/api/json")" == 200 ]]; }
building() { [[ "$(json "$1" "$2/api/json?tree=building" 2>/dev/null | jq_ 'd["building"]')" == True ]]; }
result_is() { [[ "$(json "$1" "$2/api/json?tree=result" 2>/dev/null | jq_ 'd["result"]')" == "$3" ]]; }
console_has() { text "$1" "$2/consoleText" 2>/dev/null | grep -q -- "$3"; }
owner_is() { [[ "$(json $B "$1/api/json?tree=actions[builds[number,controller]]" 2>/dev/null \
  | jq_ '" ".join(str(b["number"]) + "@" + b["controller"] for a in d["actions"] if a and "builds" in a for b in a["builds"])')" == *"$2@$3"* ]]; }
agent_online_on() { [[ "$(g "$1" 'print(jenkins.model.Jenkins.get().getComputer("agent1")?.isOnline())' 2>/dev/null)" == true ]]; }
counter() { g "$1" "println io.jenkins.plugins.cluster.OwnerRoutingFilter.$2()"; }
pipeline_job() { # name script
  python3 - "$OUT_DIR/$1.xml" "$2" <<'PY'
import sys
from xml.sax.saxutils import escape
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job"><definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>%s</script><sandbox>true</sandbox></definition>
</flow-definition>""" % escape(sys.argv[2]))
PY
}
create_both() { create_job $A "$1" "$OUT_DIR/$1.xml"; eventually 15 exists $B "/job/$1"; }
start_on_a() { post $A "/job/$1/build?delay=0sec" >/dev/null; eventually 30 owner_is "/job/$1" 1 jenkins-a; }

[[ -f "$ET1/war/jenkins.war" && -f "$ET1/plugins/workflow-cps.hpi" ]] || { echo "run ET1-build.sh first" >&2; exit 2; }

# ------------------------------------------------------------------ setup (as ET4, plus rr and a git repo)
et1 down -v --remove-orphans >/dev/null 2>&1 || true
rm -rf "$ET1/seed" "$ET1/repo"; mkdir -p "$ET1/seed" "$ET1/repo"
docker rm -fv ${P}seed >/dev/null 2>&1 || true
docker run -d --name ${P}seed -p $PORT_SEED:8080 --tmpfs /var/jenkins_home:uid=1000,gid=1000 \
  -e JAVA_OPTS="-Djenkins.install.runSetupWizard=false" -e CASC_JENKINS_CONFIG=/casc -v "$VALIDATION_DIR/jenkins/casc-et1:/casc:ro" \
  -v "$ET1/war:/war-override:ro" -v "$ET1/plugins:/extra-plugins:ro" cnj/jenkins:dev >/dev/null
wait_up $PORT_SEED
groovy $PORT_SEED 'hudson.util.Secret.fromString("x").getEncryptedValue(); org.jenkinsci.main.modules.instance_identity.InstanceIdentity.get()' >/dev/null
agent_secret $PORT_SEED agent1 > "$ET1/agent1.secret"
docker exec ${P}seed tar -C /var/jenkins_home -cf - secret.key secret.key.not-so-secret identity.key.enc secrets | tar -C "$ET1/seed" -xf -
docker rm -fv ${P}seed >/dev/null
for repo in poll mb; do
  ( mkdir -p "$ET1/repo/$repo" && cd "$ET1/repo/$repo" && git init -q -b main && git config user.email et7@example.com && git config user.name et7
    echo 'echo "branch ${env.BRANCH_NAME}"' > Jenkinsfile && git add Jenkinsfile && git commit -qm init )
done
commit() { (cd "$ET1/repo/$1" && date +%s%N > stamp && git add stamp && git commit -qm "change $(date +%s%N)"); }
et1 up -d postgres $BLOB_SERVICE >/dev/null 2>&1; wait_postgres
et1 up -d jenkins-a jenkins-b >/dev/null 2>&1
wait_up $A; wait_up $B
et1 up -d lb agent1 rr >/dev/null 2>&1
r "## ET7 results ($(date -u +%Y-%m-%dT%H:%MZ))"
r ""
r "| | Check |"
r "|---|---|"
check "agent1 is connected to A" eventually 60 agent_online_on $A

# ------------------------------------------------------------------ 1-2. live console and build page of a build on A, from B
log "scenarios 1-2: a Pipeline running on A, followed from B"
pipeline_job tick 'for (int i = 1; i <= 40; i++) { echo "tick ${i}"; sleep 1 }'
create_both tick
start_on_a tick
check "2 the build page answers on B" eventually 10 bash -c "[[ \$(curl -gs -o /dev/null -w '%{http_code}' -u '$AUTH' http://localhost:$B/job/tick/1/) == 200 ]]"
check "2 its REST API on B says it is building" building $B /job/tick/1
check "2 answered by A (forwarded)" test "$(header $B /job/tick/1/api/json X-Jenkins-Cluster-Forwarded-From)" = jenkins-a
check "2 through rr too" bash -c "for i in 1 2 3 4; do [[ \$(curl -gs -o /dev/null -w '%{http_code}' -u '$AUTH' http://localhost:$RR/job/tick/1/api/json) == 200 ]] || exit 1; done"
# progressive console from B, as the console page polls it: each poll from the last offset
offset=0; seen=""; lag_ok=0; polls=0
for i in $(seq 1 10); do
  curl -gs -D "$OUT/pt.headers" -u "$AUTH" "http://localhost:$B/job/tick/1/logText/progressiveText?start=$offset" > "$OUT/pt.body"
  offset=$(tr -d '\r' < "$OUT/pt.headers" | awk 'tolower($1) == "x-text-size:" {print $2}')
  seen="$seen$(cat "$OUT/pt.body")"
  b_last=$(grep -o 'tick [0-9]*' <<<"$seen" | tail -1 | cut -d' ' -f2)
  a_last=$(text $A /job/tick/1/consoleText | grep -o 'tick [0-9]*' | tail -1 | cut -d' ' -f2)
  polls=$((polls+1)); (( ${a_last:-0} - ${b_last:-0} <= 2 )) && lag_ok=$((lag_ok+1))
  sleep 1
done
info "1 last tick seen on B $b_last, on A $a_last; polls within 2 ticks of A: $lag_ok/$polls"
check "1 B's live console keeps up with A (within 2 s on every poll)" test "$lag_ok" = "$polls"
check "1 B's live console has every line so far, in order" bash -c "grep -o 'tick [0-9]*' <<<'$seen' | awk '{ if (\$2 != NR) exit 1 }'"

# ------------------------------------------------------------------ 3. input approved and aborted from B
log "scenario 3: input on A, answered from B"
pipeline_job ask 'def r = input(message: "go?", id: "Go", parameters: [string(name: "WHO", defaultValue: "x")]); echo "approved by form: ${r}"'
pipeline_job ask2 'input message: "go?", id: "Go"; echo "approved 2"'
create_both ask; create_both ask2
start_on_a ask; start_on_a ask2
check "3 ask is waiting for input (seen from B)" eventually 30 console_has $B /job/ask/1 'Input requested'
# the input form's submit, form-encoded, as the browser posts it (with a cluster crumb through rr)
crumb_json=$(curl -gsf -c "$OUT/rr.cookies" -u "$AUTH" "http://localhost:$RR/crumbIssuer/api/json")
crumb=$(jq_ 'd["crumb"]' <<<"$crumb_json")
code=$(curl -gs -o /dev/null -w '%{http_code}' -b "$OUT/rr.cookies" -u "$AUTH" -H "Jenkins-Crumb: $crumb" \
  --data-urlencode 'json={"parameter": [{"name": "WHO", "value": "from-b"}]}' --data-urlencode 'proceed=Proceed' \
  "http://localhost:$RR/job/ask/1/input/Go/submit")
info "3 form submit through rr answered $code"
check "3 approved through the form, the build continues on A with the value" eventually 30 console_has $A /job/ask/1 'approved by form: from-b'
check "3 and completes" eventually 30 result_is $A /job/ask/1 SUCCESS
check "3 ask2 is waiting for input" eventually 30 console_has $B /job/ask2/1 'go?'
post $B "/job/ask2/1/input/Go/abort" >/dev/null
check "3 aborted from B (REST)" eventually 30 result_is $A /job/ask2/1 ABORTED

# ------------------------------------------------------------------ 4. stop
log "scenario 4: stop from B"
pipeline_job long 'echo "long started"; sleep 300'
create_both long
start_on_a long
eventually 30 console_has $A /job/long/1 'long started'
post $B "/job/long/1/stop" >/dev/null
check "4 stopped from B" eventually 30 result_is $A /job/long/1 ABORTED

# ------------------------------------------------------------------ 5. replay
log "scenario 5: replay from B of a build running on A"
pipeline_job rep 'echo "original"; sleep 30'
create_both rep
start_on_a rep
eventually 30 console_has $A /job/rep/1 original
code=$(curl -gs -o /dev/null -w '%{http_code}' -u "$AUTH" --data-urlencode 'json={"mainScript": "echo \"replayed\""}' "http://localhost:$B/job/rep/1/replay/run")
info "5 replay from B answered $code"
check "5 a new build ran the edited script" eventually 60 bash -c "for p in $A $B; do curl -gsf -u '$AUTH' http://localhost:\$p/job/rep/2/consoleText 2>/dev/null | grep -q replayed && exit 0; done; exit 1"

# ------------------------------------------------------------------ 6. freestyle console
log "scenario 6: a freestyle build on A, followed from B"
python3 - "$OUT_DIR/fstick.xml" <<'PY'
import sys
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<project><assignedNode>built-in</assignedNode><canRoam>false</canRoam>
<builders><hudson.tasks.Shell><command>for i in $(seq 1 30); do echo "fs tick $i"; sleep 1; done</command></hudson.tasks.Shell></builders></project>""")
PY
create_both fstick
start_on_a fstick
eventually 20 console_has $A /job/fstick/1 'fs tick 3'
check "6 the live freestyle console answers on B" console_has $B /job/fstick/1 'fs tick 3'
sleep 4
check "6 and grows" eventually 10 console_has $B /job/fstick/1 'fs tick 7'

# ------------------------------------------------------------------ 7a. agent pages from B
log "scenario 7: agent1 (on A) from B"
check "7 on B, agent1's page says online (answered by A)" bash -c "[[ \$(curl -gsf -u '$AUTH' 'http://localhost:$B/computer/agent1/api/json?tree=offline') == *'\"offline\":false'* ]]"
check "7 forwarded to A" test "$(header $B /computer/agent1/api/json X-Jenkins-Cluster-Forwarded-From)" = jenkins-a
check "7 its log answers on B" bash -c "curl -gsf -u '$AUTH' 'http://localhost:$B/computer/agent1/logText/progressiveText?start=0' | grep -qi 'connected\|remoting'"
check "7 its script console works from B" bash -c "[[ \$(curl -gsf -u '$AUTH' --data-urlencode 'script=println(\"agent says \" + hudson.remoting.Channel.current().getName())' http://localhost:$B/computer/agent1/scriptText) == *'agent says'* ]]"
check "7 the channel table names A" bash -c "[[ \$(cd $VALIDATION_DIR && docker compose -f compose.et1.yaml exec -T postgres psql -U jenkins -At -c \"select m.name from jenkins_channels c join jenkins_members m on m.origin = c.origin where c.agent = 'agent1'\") == jenkins-a ]]"

# ------------------------------------------------------------------ 8 + 7b. input pending when A dies; the agent moves to B
log "scenario 8: input pending on A, A killed"
pipeline_job late 'echo "before input"; input message: "late?", id: "Late"; echo "approved after failover"'
create_both late
start_on_a late
check "8 late is waiting for input" eventually 30 console_has $B /job/late/1 'late?'
sleep 3 # the save point
killed_at=$SECONDS
docker kill -s KILL ${P}jenkins-a-1 >/dev/null
check "8 B adopted the build" eventually 120 bash -c "cd $VALIDATION_DIR && source lib.sh && curl -gsf -u \"\$AUTH\" http://localhost:$B/job/late/1/api/json?tree=building | grep -q '\"building\":true' && [[ \$(curl -gs -o /dev/null -D - -u \"\$AUTH\" http://localhost:$B/job/late/1/api/json | grep -ci x-cnj-forwarded-from) == 0 ]]"
post $B "/job/late/1/input/Late/proceedEmpty" >/dev/null
check "8 approved on B after adoption, the build completes" eventually 60 result_is $B /job/late/1 SUCCESS
check "8 with the line after the input" console_has $B /job/late/1 'approved after failover'
check "7 agent1 reconnected to B" eventually 120 agent_online_on $B
info "7 agent1 online on B $((SECONDS - killed_at)) s after A was killed"
check "7 the channel table names B" eventually 30 bash -c "[[ \$(cd $VALIDATION_DIR && docker compose -f compose.et1.yaml exec -T postgres psql -U jenkins -At -c \"select m.name from jenkins_channels c join jenkins_members m on m.origin = c.origin where c.agent = 'agent1'\") == jenkins-b ]]"
# 10c. a request for a build whose owner is dead is answered locally, not 502
check "10 no 502 for a dead owner's build (answered locally)" bash -c "[[ \$(curl -gs -o /dev/null -w '%{http_code}' -u '$AUTH' http://localhost:$B/job/late/1/) != 502 ]]"
et1 up -d jenkins-a >/dev/null 2>&1; wait_up $A

# ------------------------------------------------------------------ 9. webhook fan-in
log "scenario 9: notifyCommit to A, B, and both"
cat > "$OUT_DIR/poll.xml" <<'XML'
<?xml version='1.1' encoding='UTF-8'?>
<project>
  <scm class="hudson.plugins.git.GitSCM" plugin="git">
    <configVersion>2</configVersion>
    <userRemoteConfigs><hudson.plugins.git.UserRemoteConfig><url>file:///repo/poll</url></hudson.plugins.git.UserRemoteConfig></userRemoteConfigs>
    <branches><hudson.plugins.git.BranchSpec><name>*/main</name></hudson.plugins.git.BranchSpec></branches>
  </scm>
  <assignedNode>built-in</assignedNode>
  <triggers><hudson.triggers.SCMTrigger><spec></spec><ignorePostCommitHooks>false</ignorePostCommitHooks></hudson.triggers.SCMTrigger></triggers>
  <builders><hudson.tasks.Shell><command>git log -1 --format=%s</command></hudson.tasks.Shell></builders>
</project>
XML
create_both poll
post $A "/job/poll/build?delay=0sec" >/dev/null
eventually 60 result_is $A /job/poll/1 SUCCESS || eventually 60 result_is $B /job/poll/1 SUCCESS
builds_of() { json $A "/job/$1/api/json?tree=builds[number]" | jq_ 'len(d["builds"])'; }
hook() { curl -gs -o /dev/null -w '%{http_code}' "http://localhost:$1/git/notifyCommit?url=file:///repo/poll"; }
idle() { [[ "$(json $A /queue/api/json | jq_ 'len(d["items"])')" == 0 && "$(json $B /queue/api/json | jq_ 'len(d["items"])')" == 0 ]] \
  && ! json $A "/job/$1/lastBuild/api/json?tree=building" | grep -q '"building":true'; }
for where in A B both; do
  before=$(builds_of poll); commit poll
  case $where in A) hook $A >/dev/null ;; B) hook $B >/dev/null ;; both) hook $A >/dev/null & hook $B >/dev/null & wait ;; esac
  eventually 60 bash -c "[[ \$(cd $VALIDATION_DIR && source lib.sh && curl -gsf -u \"\$AUTH\" 'http://localhost:$A/job/poll/api/json?tree=builds[number]' | python3 -c 'import json,sys; print(len(json.load(sys.stdin)[\"builds\"]))') -gt $before ]]"
  sleep 20; eventually 60 idle poll
  after=$(builds_of poll)
  check "9 notifyCommit to $where: exactly one build for the commit ($before -> $after)" test "$after" = $((before + 1))
done
sed -e 's#file:///tmp/repo#file:///repo/mb#' "$JOBS/multibranch.xml" > "$OUT_DIR/mbp.xml"
create_both mbp
post $A /job/mbp/build >/dev/null
eventually 60 exists $B /job/mbp/job/main
eventually 60 result_is $A /job/mbp/job/main/1 SUCCESS || eventually 60 result_is $B /job/mbp/job/main/1 SUCCESS
mb_builds() { json $A "/job/mbp/job/main/api/json?tree=builds[number]" | jq_ 'len(d["builds"])'; }
before=$(mb_builds); commit mb
curl -gs -o /dev/null "http://localhost:$B/git/notifyCommit?url=file:///repo/mb"
eventually 90 bash -c "[[ \$(cd $VALIDATION_DIR && source lib.sh && curl -gsf -u \"\$AUTH\" 'http://localhost:$A/job/mbp/job/main/api/json?tree=builds[number]' | python3 -c 'import json,sys; print(len(json.load(sys.stdin)[\"builds\"]))') -gt $before ]]"
sleep 20
check "9 a multibranch push event to B: exactly one branch build ($before -> $(mb_builds))" test "$(mb_builds)" = $((before + 1))

# ------------------------------------------------------------------ 10. no loops, no needless forwarding
log "scenario 10: routing hygiene"
fwd_before=$(( $(counter $A getForwardedBuildRequests) + $(counter $B getForwardedBuildRequests) ))
for p in $A $B; do http $p /job/tick/1/ >/dev/null; http $p /job/tick/1/consoleText >/dev/null; done
fwd_after=$(( $(counter $A getForwardedBuildRequests) + $(counter $B getForwardedBuildRequests) ))
check "10 requests for a completed build are not forwarded ($fwd_before -> $fwd_after)" test "$fwd_before" = "$fwd_after"
pipeline_job loop 'echo "loop started"; sleep 60'
create_both loop
start_on_a loop
code=$(curl -gs -o /dev/null -w '%{http_code}' -u "$AUTH" -H 'X-Jenkins-Cluster-Forwarded-From: jenkins-a' "http://localhost:$B/job/loop/1/api/json")
check "10 an already forwarded request is not forwarded again (answered by B itself: $code)" test "$code" = 404
post $B "/job/loop/1/stop" >/dev/null
info "forwarded build requests: A=$(counter $A getForwardedBuildRequests) B=$(counter $B getForwardedBuildRequests); computer requests: A=$(counter $A getForwardedComputerRequests) B=$(counter $B getForwardedComputerRequests)"
check "10 no routing warnings in the logs" bash -c "! (cd $VALIDATION_DIR && docker compose -f compose.et1.yaml --profile agents logs jenkins-a jenkins-b 2>&1 | grep -q 'Could not route')"

et1 logs jenkins-a > "$OUT/jenkins-a.log" 2>&1; et1 logs jenkins-b > "$OUT/jenkins-b.log" 2>&1; et1 logs agent1 > "$OUT/agent1.log" 2>&1
cp "$ET1/BUILD" "$OUT/BUILD" 2>/dev/null || true
r ""
r "Failures: $FAILED"
echo "ET7 exit=$FAILED"
exit $FAILED
