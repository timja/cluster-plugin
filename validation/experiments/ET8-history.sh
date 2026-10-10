#!/bin/bash
# ET8: acceptance experiment for Target 8 (fingerprints, test results, discarders and a cluster view at cluster scale).
# ET setup (two tmpfs controllers, S0, Postgres, Azurite, token auth) plus agent1 through the failover URL (on A).
# Exits non-zero if any check fails.
#
# Prerequisite: CORE_REF=cnj/t6-m6-stateless-sessions experiments/ET1-build.sh
source "$(dirname "$0")/../lib.sh"
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.fsmonitor GIT_CONFIG_VALUE_0=false
A=8081 B=8082 OUT="$RAW_DIR/ET8" ET1="$VALIDATION_DIR/.et1"
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
jq_() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
g() { groovy "$1" "$2" | tr -d '\r' | sed -e 's/[[:space:]]*$//'; }
exists() { [[ "$(http "$1" "$2/api/json")" == 200 ]]; }
result_is() { [[ "$(json "$1" "$2/api/json?tree=result" 2>/dev/null | jq_ 'd["result"]')" == "$3" ]]; }
agent_online_on() { [[ "$(g "$1" 'print(jenkins.model.Jenkins.get().getComputer("agent1")?.isOnline())' 2>/dev/null)" == true ]]; }
create_both() { create_job $A "$1" "$OUT_DIR/$1.xml"; eventually 15 exists $B "/job/$1"; }
freestyle() { # name command [publishers] [properties]
  python3 - "$OUT_DIR/$1.xml" "$2" "${3:-}" "${4:-}" <<'PY'
import sys
from xml.sax.saxutils import escape
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<project><properties>%s</properties><assignedNode>built-in</assignedNode><canRoam>false</canRoam><concurrentBuild>true</concurrentBuild>
<builders><hudson.tasks.Shell><command>%s</command></hudson.tasks.Shell></builders><publishers>%s</publishers></project>""" % (sys.argv[4], escape(sys.argv[2]), sys.argv[3]))
PY
}
build_on() { post "$1" "/job/$2/build?delay=0sec" >/dev/null; }
idle() { [[ "$(json $A /queue/api/json | jq_ 'len(d["items"])')" == 0 && "$(json $B /queue/api/json | jq_ 'len(d["items"])')" == 0 ]] \
  && [[ "$(json $A '/computer/api/json?tree=busyExecutors' | jq_ 'd["busyExecutors"]')" == 0 ]] \
  && [[ "$(json $B '/computer/api/json?tree=busyExecutors' | jq_ 'd["busyExecutors"]')" == 0 ]]; }
builds_of() { json "$1" "/job/$2/api/json?tree=builds[number]" | jq_ '" ".join(str(b["number"]) for b in sorted(d["builds"], key=lambda b: b["number"]))'; }
usages_are() { [[ "$(usages "$1" "$2")" == "$3" ]]; }
usages() { json "$1" "/fingerprint/$2/api/json?tree=usage[name,ranges[ranges[start,end]]]" 2>/dev/null \
  | jq_ '" ".join(sorted(u["name"] + ":" + ",".join("%d-%d" % (r["start"], r["end"] - 1) for r in u["ranges"]["ranges"]) for u in d["usage"]))'; }

[[ -f "$ET1/war/jenkins.war" && -f "$ET1/plugins/workflow-cps.hpi" ]] || { echo "run ET1-build.sh first" >&2; exit 2; }

# ------------------------------------------------------------------ setup (as ET7)
et1 down -v --remove-orphans >/dev/null 2>&1 || true
rm -rf "$ET1/seed" "$ET1/repo"; mkdir -p "$ET1/seed" "$ET1/repo"
docker rm -f et1-seed >/dev/null 2>&1 || true
docker run -d --name et1-seed -p 8089:8080 --tmpfs /var/jenkins_home:uid=1000,gid=1000 \
  -e JAVA_OPTS="-Djenkins.install.runSetupWizard=false" -e CASC_JENKINS_CONFIG=/casc -v "$VALIDATION_DIR/jenkins/casc-et1:/casc:ro" \
  -v "$ET1/war:/war-override:ro" -v "$ET1/plugins:/extra-plugins:ro" cnj/jenkins:dev >/dev/null
wait_up 8089
groovy 8089 'hudson.util.Secret.fromString("x").getEncryptedValue(); org.jenkinsci.main.modules.instance_identity.InstanceIdentity.get()' >/dev/null
agent_secret 8089 agent1 > "$ET1/agent1.secret"
docker exec et1-seed tar -C /var/jenkins_home -cf - secret.key secret.key.not-so-secret identity.key.enc secrets | tar -C "$ET1/seed" -xf -
docker rm -f et1-seed >/dev/null
et1 up -d postgres azurite >/dev/null 2>&1; sleep 3
et1 up -d jenkins-a jenkins-b >/dev/null 2>&1
wait_up $A; wait_up $B
et1 up -d lb agent1 >/dev/null 2>&1
r "## ET8 results ($(date -u +%Y-%m-%dT%H:%MZ))"
r ""
r "| | Check |"
r "|---|---|"
check "both controllers use the store for fingerprints" bash -c "for p in $A $B; do [[ \$(cd $VALIDATION_DIR && source lib.sh && groovy \$p 'println jenkins.fingerprints.FingerprintStorage.get().class.simpleName') == StoreFingerprintStorage* ]] || exit 1; done"
check "agent1 is connected to A" eventually 60 agent_online_on $A

# ------------------------------------------------------------------ 1. fingerprints from both controllers
log "scenario 1: the same file fingerprinted by a job on A and a job on B"
FP='<hudson.tasks.Fingerprinter><targets>f.txt</targets></hudson.tasks.Fingerprinter>'
freestyle fpa 'echo shared-content > f.txt' "$FP"; freestyle fpb 'echo shared-content > f.txt' "$FP"
create_both fpa; create_both fpb
build_on $A fpa; eventually 60 result_is $A /job/fpa/1 SUCCESS
build_on $B fpb; eventually 60 result_is $B /job/fpb/1 SUCCESS
md5=$(printf 'shared-content\n' | md5 -q 2>/dev/null || printf 'shared-content\n' | md5sum | cut -d' ' -f1)
info "1 fingerprint $md5: A sees [$(usages $A $md5)], B sees [$(usages $B $md5)]"
check "1 A lists both usages" eventually 10 usages_are $A $md5 'fpa:1-1 fpb:1-1'
check "1 B lists both usages" eventually 10 usages_are $B $md5 'fpa:1-1 fpb:1-1'
check "1 nothing under fingerprints/ in either home" bash -c "for c in et1-jenkins-a-1 et1-jenkins-b-1; do [[ -z \$(docker exec \$c sh -c 'ls -A /var/jenkins_home/fingerprints 2>/dev/null') ]] || exit 1; done"

# ------------------------------------------------------------------ 2. concurrent updates of one fingerprint
log "scenario 2: 10 builds on A and 10 on B record the same file at once"
for i in $(seq 1 10); do build_on $A fpa & build_on $B fpb & done; wait
eventually 300 idle
u=$(usages $A $md5)
info "2 usages after: $u"
check "2 all 20 new usages are recorded (fpa:1-11 fpb:1-11)" test "$u" = "fpa:1-11 fpb:1-11"
check "2 B agrees" test "$(usages $B $md5)" = "$u"

# ------------------------------------------------------------------ 3. test results
log "scenario 3: JUnit results on the other controller"
JU='<hudson.tasks.junit.JUnitResultArchiver plugin="junit"><testResults>r.xml</testResults></hudson.tasks.junit.JUnitResultArchiver>'
freestyle ju "cat > r.xml <<EOF
<testsuite name=\"s\" tests=\"3\" failures=\"1\"><testcase classname=\"c\" name=\"ok1\"/><testcase classname=\"c\" name=\"ok2\"/><testcase classname=\"c\" name=\"bad\"><failure message=\"no\"/></testcase></testsuite>
EOF" "$JU"
create_both ju
build_on $A ju
eventually 60 bash -c "[[ \$(cd $VALIDATION_DIR && source lib.sh && curl -gsf -u \"\$AUTH\" http://localhost:$A/job/ju/1/api/json?tree=building) == *'\"building\":false'* ]]"
done_at=$(python3 -c 'import time; print(time.time())')
check "3 B shows the report within 2 s of completion (3 tests, 1 failed)" eventually 2 bash -c "[[ \$(cd $VALIDATION_DIR && source lib.sh && curl -gsf -u \"\$AUTH\" 'http://localhost:$B/job/ju/1/testReport/api/json?tree=failCount,passCount') == *'\"failCount\":1'*'\"passCount\":2'* ]]"
info "3 report on B after $(python3 -c "import time; print(round(time.time() - $done_at, 1))") s"
build_on $A ju; eventually 60 result_is $A /job/ju/2 UNSTABLE
post $B /job/ju/2/doDelete >/dev/null
check "3 deleted on B, its results are gone on A" eventually 10 bash -c "[[ \$(curl -gs -o /dev/null -w '%{http_code}' -u '$AUTH' http://localhost:$A/job/ju/2/testReport/api/json) == 404 ]]"
check "3 and on B" bash -c "[[ \$(curl -gs -o /dev/null -w '%{http_code}' -u '$AUTH' http://localhost:$B/job/ju/2/testReport/api/json) == 404 ]]"

# ------------------------------------------------------------------ 4. discarders
log "scenario 4: numToKeep=3 with builds alternating between A and B"
KEEP3='<jenkins.model.BuildDiscarderProperty><strategy class="hudson.tasks.LogRotator"><daysToKeep>-1</daysToKeep><numToKeep>3</numToKeep><artifactDaysToKeep>-1</artifactDaysToKeep><artifactNumToKeep>-1</artifactNumToKeep></strategy></jenkins.model.BuildDiscarderProperty>'
freestyle keep 'echo kept' '' "$KEEP3"
create_both keep
for i in $(seq 1 8); do if (( i % 2 )); then p=$A; else p=$B; fi; build_on $p keep; eventually 60 result_is $p /job/keep/$i SUCCESS; done
eventually 120 idle
check "4 A keeps exactly the last 3 (6 7 8)" eventually 20 bash -c "[[ \$(cd $VALIDATION_DIR && source lib.sh && curl -gsf -u \"\$AUTH\" 'http://localhost:$A/job/keep/api/json?tree=builds[number]' | python3 -c 'import json,sys; print(sorted(b[\"number\"] for b in json.load(sys.stdin)[\"builds\"]))') == '[6, 7, 8]' ]]"
check "4 B keeps exactly the last 3 (6 7 8)" eventually 20 bash -c "[[ \$(cd $VALIDATION_DIR && source lib.sh && curl -gsf -u \"\$AUTH\" 'http://localhost:$B/job/keep/api/json?tree=builds[number]' | python3 -c 'import json,sys; print(sorted(b[\"number\"] for b in json.load(sys.stdin)[\"builds\"]))') == '[6, 7, 8]' ]]"
check "4 no discarder errors in the logs" bash -c "! (cd $VALIDATION_DIR && docker compose -f compose.et1.yaml logs jenkins-a jenkins-b 2>&1 | grep -iE 'Failed to (rotate|delete)|discard.*(failed|exception)')"
log "scenario 4b: numToKeep=1; a long build on A, a short one on B"
KEEP1=${KEEP3/<numToKeep>3</<numToKeep>1<}
freestyle dk 'if [ "$BUILD_NUMBER" = 1 ]; then sleep 40; fi; echo done' '' "$KEEP1"
create_both dk
build_on $A dk
eventually 30 bash -c "[[ \$(cd $VALIDATION_DIR && source lib.sh && curl -gsf -u \"\$AUTH\" http://localhost:$A/job/dk/1/api/json?tree=building) == *true* ]]"
build_on $B dk; eventually 60 result_is $B /job/dk/2 SUCCESS

# ------------------------------------------------------------------ 5. cluster view (while dk#1 runs on A)
log "scenario 5: the cluster view"
for p in $A $B; do json $p '/manage/cluster/api/json?depth=1' > "$OUT/cluster-$p.json" || true; done
check "5 B's view lists both controllers alive" bash -c "python3 -c 'import json; d=json.load(open(\"$OUT/cluster-$B.json\")); exit(0 if sorted(m[\"name\"] for m in d[\"members\"] if m[\"alive\"]) == [\"jenkins-a\", \"jenkins-b\"] else 1)'"
check "5 B's view lists dk #1 running on A" bash -c "python3 -c 'import json; d=json.load(open(\"$OUT/cluster-$B.json\")); exit(0 if any(b[\"job\"] == \"dk\" and b[\"number\"] == 1 and b[\"controller\"] == \"jenkins-a\" for b in d[\"runningBuilds\"]) else 1)'"
check "5 B's view says agent1 is connected to A" bash -c "python3 -c 'import json; d=json.load(open(\"$OUT/cluster-$B.json\")); exit(0 if {\"agent\": \"agent1\", \"controller\": \"jenkins-a\"} in [{k: c[k] for k in (\"agent\", \"controller\")} for c in d[\"channels\"]] else 1)'"
check "5 A's view is the same apart from 'self'" bash -c "python3 -c '
import json
a, b = (json.load(open(\"$OUT/cluster-%s.json\" % p)) for p in ($A, $B))
strip = lambda d: {\"members\": sorted(m[\"name\"] for m in d[\"members\"]), \"runningBuilds\": d[\"runningBuilds\"], \"channels\": d[\"channels\"]}
exit(0 if strip(a) == strip(b) else 1)'"
check "4b B's discarder did not touch dk #1 running on A: it completes" eventually 90 result_is $A /job/dk/1 SUCCESS
sleep 5; info "4b dk builds after: A [$(builds_of $A dk)] B [$(builds_of $B dk)] (LogRotator also keeps the last successful build)"
docker kill -s KILL et1-jenkins-a-1 >/dev/null
alive_only_b() { [[ "$(json $B /manage/cluster/api/json?depth=1 | jq_ '",".join(sorted(m["name"] for m in d["members"] if m["alive"]))')" == jenkins-b ]]; }
check "5 after A dies, B's view shows only B alive (within the membership timeout)" eventually 40 alive_only_b

# ------------------------------------------------------------------ 1b. fingerprints survive the controllers
log "scenario 1b: destroy both controllers"
et1 rm -sf jenkins-a jenkins-b >/dev/null 2>&1
et1 up -d jenkins-a jenkins-b >/dev/null 2>&1; wait_up $A; wait_up $B
check "1 the fingerprint and its usages survive (fresh B)" eventually 30 bash -c "[[ \$(cd $VALIDATION_DIR && source lib.sh && curl -gsf -u \"\$AUTH\" 'http://localhost:$B/fingerprint/$md5/api/json?tree=usage[name]' | python3 -c 'import json,sys; print(sorted(u[\"name\"] for u in json.load(sys.stdin)[\"usage\"]))') == \"['fpa', 'fpb']\" ]]"

et1 logs jenkins-a > "$OUT/jenkins-a.log" 2>&1; et1 logs jenkins-b > "$OUT/jenkins-b.log" 2>&1
cp "$ET1/BUILD" "$OUT/BUILD" 2>/dev/null || true
r ""
r "Failures: $FAILED"
echo "ET8 exit=$FAILED"
exit $FAILED
