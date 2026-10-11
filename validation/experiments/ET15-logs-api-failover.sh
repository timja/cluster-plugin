#!/bin/bash
# ET15: acceptance experiment for Target 14 M2, M4 and M5: logs rolled over into segments (also across a failover and
# a rename), the REST API showing builds running on the other controller, and failover within ~25 s.
# ET setup (two tmpfs controllers, S0, Postgres, Azurite, token auth); segments of 20 blocks (instead of 49,000).
# Exits non-zero if any check fails.
#
# Prerequisite: CORE_REF=cnj/t14-m1-resilience experiments/ET1-build.sh
source "$(dirname "$0")/../lib.sh"
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.fsmonitor GIT_CONFIG_VALUE_0=false
export CNJ_JAVA_OPTS="-Dio.jenkins.plugins.cnj.store.AzureBlobStore.maxSegmentBlocks=20 -Dio.jenkins.plugins.cnj.store.BlobLogStorage.coalesceMillis=500"
A=8081 B=8082 OUT="$RAW_DIR/ET15" ET1="$VALIDATION_DIR/.et1"
et1() { docker compose --project-directory "$VALIDATION_DIR" -f "$VALIDATION_DIR/compose.et1.yaml" "$@"; }
rm -rf "$OUT"; mkdir -p "$OUT" "$OUT_DIR"
: > "$OUT/results.md"
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
FAILED=0
check() { local d=$1; shift; if "$@" >>"$OUT/check.log" 2>&1; then r "| ✅ | $d |"; else r "| ❌ | $d |"; FAILED=$((FAILED+1)); fi; }
info() { r "| ℹ️ | $* |"; }
eventually() { local t=$1 start=$SECONDS; shift; until "$@"; do (( SECONDS - start > t )) && return 1; sleep 1; done; }
json() { curl -gsf -m 20 -u "$AUTH" "http://localhost:$1$2"; }
jq_() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
code() { curl -gs -m 20 -o /dev/null -w '%{http_code}' -u "$AUTH" "http://localhost:$1$2"; }
exists() { [[ "$(code "$1" "$2/api/json")" == 200 ]]; }
result_on() { json "$1" "$2/api/json?tree=result" 2>/dev/null | jq_ 'd["result"]' 2>/dev/null; }
is_result() { [[ "$(result_on "$1" "$2")" == "$3" ]]; }
console_has() { json "$1" "$2/consoleText" 2>/dev/null | grep -q -- "$3"; }
pipeline() { # port name script
  python3 - "$OUT_DIR/$2.xml" "$3" <<'PY'
import sys
from xml.sax.saxutils import escape
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job"><definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>%s</script><sandbox>true</sandbox></definition></flow-definition>""" % escape(sys.argv[2]))
PY
  create_job "$1" "$2" "$OUT_DIR/$2.xml"; eventually 15 exists $B "/job/$2"; eventually 15 exists $A "/job/$2"
}
segments_of() { groovy $A "println jenkins.persistence.PersistenceStores.getBlobStore().list('jobs/$1/builds/$2').findAll { it ==~ /.*\\/log(\\.[0-9]+)?/ }.size()" | tr -d '\r\n '; }
# every "line N" for N in 1..count present, in nondecreasing order (a resumed Pipeline may repeat lines since its save point)
lines_in_order() { # file count
  python3 - "$1" "$2" <<'PY'
import re, sys
nums = [int(m.group(1)) for m in re.finditer(r"^line (\d+)$", open(sys.argv[1]).read(), re.M)]
want = int(sys.argv[2])
assert set(range(1, want + 1)) <= set(nums), ("missing", sorted(set(range(1, want + 1)) - set(nums))[:10])
assert all(a <= b for a, b in zip(nums, nums[1:])) or True
# order: the first occurrence of each line is in order
first = {}
for i, n in enumerate(nums):
    first.setdefault(n, i)
order = [first[n] for n in range(1, want + 1)]
assert order == sorted(order), "out of order"
PY
}
console_whole_on() { json "$1" "$2/consoleText" > "$OUT/console-$3.txt" 2>/dev/null && lines_in_order "$OUT/console-$3.txt" "$4" && grep -q '^done' "$OUT/console-$3.txt"; }
kill_a() { docker kill -s KILL et1-jenkins-a-1 >/dev/null; }
restart_a() { et1 rm -sf jenkins-a >/dev/null 2>&1; et1 up -d jenkins-a >/dev/null 2>&1; wait_up $A; }
CHATTY='for (int i = 1; i <= COUNT; i++) { echo "line ${i}"; sleep time: 200, unit: "MILLISECONDS" }; echo "done"'

[[ -f "$ET1/war/jenkins.war" ]] || { echo "run ET1-build.sh first" >&2; exit 2; }

# ------------------------------------------------------------------ setup (as ET6)
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
r "## ET15 results ($(date -u +%Y-%m-%dT%H:%MZ))"
r ""
r "| | Check |"
r "|---|---|"

# ------------------------------------------------------------------ 1. a log rolled over into segments
log "scenario 1: a chatty Pipeline (300 lines, ~1 minute)"
pipeline $A chatty "${CHATTY//COUNT/300}"
post $A "/job/chatty/build?delay=0sec" >/dev/null
eventually 60 console_has $A /job/chatty/1 "line 100"
check "1 while it runs, B's API shows it as the last build, building" bash -c "[[ \$(curl -gsf -u '$AUTH' 'http://localhost:$B/job/chatty/api/json?tree=lastBuild[number,building]') == *'\"building\":true,\"number\":1'* ]]"
check "1 ... and first in builds[]" bash -c "[[ \$(curl -gsf -u '$AUTH' 'http://localhost:$B/job/chatty/api/json?tree=builds[number,building]') == *'\"builds\":[{'*'\"building\":true,\"number\":1}'* ]]"
check "1 ... and B's /lastBuild/api/json is the running build (redirected and forwarded to A)" bash -c "[[ \$(curl -gsfL -u '$AUTH' 'http://localhost:$B/job/chatty/lastBuild/api/json?tree=number,building') == *'\"building\":true'*'\"number\":1'* ]]"
check "1 completes" eventually 180 is_result $A /job/chatty/1 SUCCESS
n=$(segments_of chatty 1)
info "1 log segments: $n"
check "1 the log was rolled over into several segments" bash -c "[[ '$n' -gt 2 ]]"
check "1 B shows the whole log, in order" eventually 60 console_whole_on $B /job/chatty/1 chatty-b 300
completed_on_b() { json $B '/job/chatty/api/json?tree=lastBuild[number,building,result]' | tee "$OUT/chatty-last-on-b.json" | grep -q '"building":false,"number":1,"result":"SUCCESS"'; }
check "1 after completion, B's API shows the completed build" eventually 30 completed_on_b

# ------------------------------------------------------------------ 2. rolled over, then adopted
log "scenario 2: A killed after its build's log rolled over; B adopts and continues"
pipeline $A adopted "${CHATTY//COUNT/400}"
post $A "/job/adopted/build?delay=0sec" >/dev/null
eventually 90 console_has $A /job/adopted/1 "line 150"
sleep 3 # a save point
kill_a; killed=$SECONDS
check "2 B adopts within 30 s" eventually 30 bash -c "[[ \$(docker logs et1-jenkins-b-1 2>&1 | grep -c 'Adopting adopted #1') -gt 0 ]]"
info "2 adopted $((SECONDS - killed)) s after the kill"
check "2 completes on B" eventually 240 is_result $B /job/adopted/1 SUCCESS
check "2 the whole log, in order, across the segments of both owners" eventually 60 console_whole_on $B /job/adopted/1 adopted-b 400
info "2 log segments: $(groovy $B "println jenkins.persistence.PersistenceStores.getBlobStore().list('jobs/adopted/builds/1').findAll { it ==~ /.*\\/log(\\.[0-9]+)?/ }.size()" | tr -d '\r\n ')"
restart_a

# ------------------------------------------------------------------ 3. no rename while a build runs elsewhere
# (a job, or a folder holding it, cannot be renamed while it builds: also not from the controller that is not running it,
# which would move the running build's blobs underneath its owner)
log "scenario 3: renaming a job and its folder from B while the job builds on A"
groovy $A 'Jenkins.get().createProject(com.cloudbees.hudson.plugins.folder.Folder, "before")' >/dev/null
eventually 15 exists $B /job/before
python3 - "$OUT_DIR/inner.xml" "${CHATTY//COUNT/200}" <<'PY'
import sys
from xml.sax.saxutils import escape
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job"><definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>%s</script><sandbox>true</sandbox></definition></flow-definition>""" % escape(sys.argv[2]))
PY
curl -sf -u "$AUTH" -X POST "http://localhost:$A/job/before/createItem?name=inner" -H 'Content-Type: application/xml' --data-binary @"$OUT_DIR/inner.xml" >/dev/null
eventually 15 exists $B /job/before/job/inner
post $A "/job/before/job/inner/build?delay=0sec" >/dev/null
eventually 60 console_has $A /job/before/job/inner/1 "line 50"
job_rename=$(curl -gs -o /dev/null -w '%{http_code}' -u "$AUTH" -X POST "http://localhost:$B/job/before/job/inner/confirmRename?newName=moved")
folder_rename=$(curl -gs -o /dev/null -w '%{http_code}' -u "$AUTH" -X POST "http://localhost:$B/job/before/confirmRename?newName=after")
check "3 renaming the job from B is refused ($job_rename)" bash -c "[[ '$job_rename' == 400 ]] && ! curl -gsf -u '$AUTH' http://localhost:$A/job/before/job/moved/api/json >/dev/null"
check "3 renaming its folder from B is refused ($folder_rename)" bash -c "[[ '$folder_rename' == 400 ]] && ! curl -gsf -u '$AUTH' http://localhost:$A/job/after/api/json >/dev/null"
check "3 the build completes, with its whole log on B" eventually 180 is_result $A /job/before/job/inner/1 SUCCESS
check "3 ... in order" eventually 60 console_whole_on $B /job/before/job/inner/1 inner-b 200

# ------------------------------------------------------------------ 4. failover time
log "scenario 4: failover time (SIGKILL, three times)"
for i in 1 2 3; do
  pipeline $A fo$i 'for (int i = 1; i <= 40; i++) { echo "line ${i}"; sleep 1 }; echo "done"'
  post $A "/job/fo$i/build?delay=0sec" >/dev/null
  eventually 60 console_has $A /job/fo$i/1 "line 5"
  sleep 3
  kill_a; killed=$SECONDS
  eventually 60 bash -c "[[ \$(docker logs et1-jenkins-b-1 2>&1 | grep -c 'Adopting fo$i #1') -gt 0 ]]" || true
  t=$((SECONDS - killed)); echo "$t" >> "$OUT/failover-seconds.txt"
  info "4 run $i: adopted $t s after the kill"
  eventually 120 is_result $B /job/fo$i/1 SUCCESS || true
  restart_a
done
check "4 every adoption within 30 s (worst case was ~50 s)" bash -c "awk '\$1 > 30 { bad = 1 } END { exit bad }' '$OUT/failover-seconds.txt'"
check "4 every adopted build completed" bash -c "for i in 1 2 3; do [[ \$(curl -gsf -u '$AUTH' http://localhost:$B/job/fo\$i/1/api/json?tree=result) == *SUCCESS* ]] || exit 1; done"

et1 logs jenkins-a > "$OUT/jenkins-a.log" 2>&1; et1 logs jenkins-b > "$OUT/jenkins-b.log" 2>&1
cp "$ET1/BUILD" "$OUT/BUILD" 2>/dev/null || true
r ""
r "Failures: $FAILED"
echo "ET15 exit=$FAILED"
exit $FAILED
