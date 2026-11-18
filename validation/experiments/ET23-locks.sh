#!/bin/bash
# ET23: the Pipeline `lock` step across the controllers of a cluster (lockable-resources with the cluster plugin's
# LockCoordinator), end to end: real Pipeline builds on two controllers sharing a store, including a controller killed
# while a build holds or waits for a lock, a controller restarted while a build waits, lock timeouts, reservations, label
# pools and several resources taken in opposite orders. Exits non-zero if any check fails.
#
# Every lock body prints "HOLD <resource> <epoch ms> <build>" when it starts and "FREE <resource> <epoch ms> <build>"
# when it ends; the controllers share the host's clock, so the holds of each resource, read from the consoles of every
# build, must never overlap. The store's events for _locks/<resource> are checked too.
#
# The shared queue gives a build to whichever controller claims it first, so a scenario finds where each build runs and
# kills that controller, recording the placement.
#
# Prerequisite: experiments/ET1-build.sh (with lockable-resources)
source "$(dirname "$0")/../lib.sh"
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.fsmonitor GIT_CONFIG_VALUE_0=false
A=$PORT_A B=$PORT_B OUT="$RAW_DIR/ET23" ET1="$ET1_DIR"
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
json() { curl -gsf -u "$AUTH" "http://localhost:$1$2"; }
text() { curl -gsf -u "$AUTH" "http://localhost:$1$2"; }
jq_() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
psql_q() { et1 exec -T postgres psql -U jenkins -At -c "$1"; }
up() { curl -gsf -m 5 -u "$AUTH" "http://localhost:$1/api/json" >/dev/null 2>&1; }
# a controller that answers: B if it is up, else A
any() { if up $B; then echo $B; else echo $A; fi; }
console() { local p; for p in $A $B; do up $p && text $p "/job/$1/$2/consoleText" 2>/dev/null && return 0; done; return 1; }
console_has() { console "$1" "$2" | grep -q -- "$3"; }
result_of() { json "$(any)" "/job/$1/$2/api/json?tree=result" 2>/dev/null | jq_ 'd["result"]'; }
result_is() { [[ "$(result_of "$1" "$2")" == "$3" ]]; }
finished() { local x; x=$(result_of "$1" "$2"); [[ -n "$x" && "$x" != None ]]; }
kill_ctl() { docker kill -s KILL "${P}jenkins-$1-1" >/dev/null; }
# a controller started again has a new home (tmpfs): it declares its resources again, as configuration as code would
start_ctl() { et1 up -d "jenkins-$1" >/dev/null 2>&1; wait_up "$(port_of "$1")"; declare_all "$(port_of "$1")"; }
port_of() { [[ $1 == a ]] && echo $A || echo $B; }
other() { [[ $1 == a ]] && echo b || echo a; }
# owner <job> <n>: the controller (a or b) whose JVM runs the build, empty if none does (yet)
owner() {
  local c
  for c in a b; do
    up "$(port_of $c)" || continue
    [[ "$(groovy "$(port_of $c)" "def b = jenkins.model.Jenkins.get().getItemByFullName('$1')?.getBuildByNumber($2); print(b != null && b.isBuilding())" 2>/dev/null)" == true ]] && { echo $c; return 0; }
  done
  return 1
}
has_owner() { owner "$1" "$2" >/dev/null; }
# lock_events <resource>: the store's events for the resource's lock key, "WRITTEN"/"DELETED", oldest first
lock_events() { psql_q "select kind from jenkins_store_events where key = '_locks/$1' order by seq" | tr '\n' ' '; }
holds_alternate() { # resource count: the key was written and deleted, strictly alternately, count times
  local want="" i; for i in $(seq 1 "$2"); do want+="WRITTEN DELETED "; done
  [[ "$(lock_events "$1")" == "$want" ]]
}
# the lock keys left in the store
lock_keys() { psql_q "select key from jenkins_store where key like '\_locks/%'" 2>/dev/null; }
# the holds of every resource in the consoles of the given builds ("job#n ..."), checked for overlaps; prints the holds
holds() {
  local b
  for b in "$@"; do console "${b%#*}" "${b#*#}" 2>/dev/null | grep -E '^(HOLD|FREE) ' | sed "s|\$| $b|"; done
}
no_overlap() { # builds...: every HOLD has its FREE, and no two holds of a resource overlap
  holds "$@" > "$OUT/holds-$SCEN.txt"
  python3 - "$OUT/holds-$SCEN.txt" "$#" <<'PY'
import sys, collections
lines = [l.split() for l in open(sys.argv[1]) if l.strip()]
opened, spans = {}, collections.defaultdict(list)
for kind, res, ms, *rest in lines:
    who = rest[-1]
    if kind == "HOLD":
        opened[(res, who)] = int(ms)
    else:
        spans[res].append((opened.pop((res, who)), int(ms), who))
if opened:
    sys.exit(f"held but never freed: {sorted(opened)}")
for res, s in spans.items():
    s.sort()
    for (a0, a1, aw), (b0, b1, bw) in zip(s, s[1:]):
        if b0 < a1:
            sys.exit(f"{res}: {bw} took it at {b0} while {aw} held it until {a1}")
print({res: len(s) for res, s in spans.items()})
PY
}
# max_concurrent <label-pool resources...>: across the holds file of the scenario, how many resources were held at once
max_concurrent() {
  python3 - "$OUT/holds-$SCEN.txt" <<'PY'
import sys
ev = []
opened = {}
for l in open(sys.argv[1]):
    kind, res, ms, *rest = l.split()
    ev.append((int(ms), 1 if kind == "HOLD" else -1))
n = m = 0
for _, d in sorted(ev, key=lambda e: (e[0], e[1])):
    n += d; m = max(m, n)
print(m)
PY
}
pipeline_job() { # name script
  python3 - "$OUT_DIR/$1.xml" "$2" <<'PY'
import sys
from xml.sax.saxutils import escape
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job"><properties/>
<definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>%s</script><sandbox>true</sandbox></definition>
</flow-definition>""" % escape(sys.argv[2]))
PY
}
create_both() { create_job $A "$1" "$OUT_DIR/$1.xml"; eventually 15 exists $B "/job/$1"; }
# a lock body: HOLD, ticks, FREE (RES is the resource the body reports; a label lock sets it from its variable)
body() { # resource-expression ticks seconds-per-tick
  echo "echo \"HOLD \${$1} \${System.currentTimeMillis()}\"; for (int i = 1; i <= $2; i++) { echo \"tick \${i}\"; sleep $3 }; echo \"FREE \${$1} \${System.currentTimeMillis()}\""
}
go() { post "$1" "/job/$2/build?delay=0sec" >/dev/null; }
# declare <port> <name> [labels]: a resource defined on the controller, as configuration as code would
declare() {
  groovy "$1" "def m = org.jenkins.plugins.lockableresources.LockableResourcesManager.get(); if (m.fromName('$2') == null) { def r = new org.jenkins.plugins.lockableresources.LockableResource('$2'); r.setLabels('${3:-}'); m.addResource(r) }" >/dev/null
}
declare_both() { declare $A "$@"; declare $B "$@"; }
declare_all() { local x; for x in res res2 res3 res4 res5 ra rb; do declare "$1" $x; done; declare "$1" p1 pool; declare "$1" p2 pool; }
# all_free <port>: the controller sees every resource free (not locked, reserved or held elsewhere) and nothing waiting
all_free() {
  [[ "$(groovy "$1" 'def m = org.jenkins.plugins.lockableresources.LockableResourcesManager.get(); print(m.resources.every { !it.isLocked() && !it.isReserved() && it.getHeldElsewhereBy() == null } && m.getCurrentQueuedContext().isEmpty())' 2>/dev/null)" == true ]]
}
state() { groovy "$1" 'def m = org.jenkins.plugins.lockableresources.LockableResourcesManager.get(); m.resources.each { println("${it.name} locked=${it.isLocked()} build=${it.buildExternalizableId} reserved=${it.getReservedBy()} elsewhere=${it.getHeldElsewhereBy()}") }; println("queued=${m.getCurrentQueuedContext().size()}")' 2>/dev/null; }
succeeded() { local b; for b in "$@"; do result_is "${b%#*}" "${b#*#}" SUCCESS || return 1; done; }
all_finished() { local b; for b in "$@"; do finished "${b%#*}" "${b#*#}" || return 1; done; }
not_in_console() { ! console_has "$@"; }
owner_is() { [[ "$(owner "$1" "$2")" == "$3" ]]; }
free_on_both() { all_free $A && all_free $B; }
not_succeeded() { [[ "$(result_of "$1" "$2")" != SUCCESS ]]; }
# held_on <port> <resource>: the controller shows the resource reserved, locked or held elsewhere
held_on() { [[ "$(groovy "$1" "def r = org.jenkins.plugins.lockableresources.LockableResourcesManager.get().fromName('$2'); print(r.isReserved() || r.isLocked() || r.getHeldElsewhereBy() != null)" 2>/dev/null)" == true ]]; }
coordinator_on_both() { local p; for p in $A $B; do [[ "$(groovy $p 'print(org.jenkins.plugins.lockableresources.LockCoordinator.getMethod("get").invoke(null)?.getClass()?.getSimpleName())')" == ClusterLocks ]] || return 1; done; }
dump_state() { { echo "== A"; state $A; echo "== B"; state $B; echo "== lock keys"; lock_keys; } > "$OUT/state-$SCEN.txt" 2>&1; }

[[ -f "$ET1/war/jenkins.war" && -f "$ET1/plugins/lockable-resources.hpi" ]] || { echo "run ET1-build.sh first (with lockable-resources)" >&2; exit 2; }

# ------------------------------------------------------------------ setup (as ET5, without the agent)
et1 down -v --remove-orphans >/dev/null 2>&1 || true
rm -rf "$ET1/seed" "$ET1/repo"; mkdir -p "$ET1/seed" "$ET1/repo"
docker rm -fv ${P}seed >/dev/null 2>&1 || true
docker run -d --name ${P}seed -p $PORT_SEED:8080 --tmpfs /var/jenkins_home:uid=1000,gid=1000 \
  -e JAVA_OPTS="-Djenkins.install.runSetupWizard=false" -e CASC_JENKINS_CONFIG=/casc -v "$VALIDATION_DIR/jenkins/casc-et1:/casc:ro" \
  -v "$ET1/war:/war-override:ro" -v "$ET1/plugins:/extra-plugins:ro" "$DEV_IMAGE" >/dev/null
wait_up $PORT_SEED
groovy $PORT_SEED 'hudson.util.Secret.fromString("x").getEncryptedValue(); org.jenkinsci.main.modules.instance_identity.InstanceIdentity.get()' >/dev/null
docker exec ${P}seed tar -C /var/jenkins_home -cf - secret.key secret.key.not-so-secret identity.key.enc secrets | tar -C "$ET1/seed" -xf -
docker rm -fv ${P}seed >/dev/null
et1 up -d postgres $BLOB_SERVICE >/dev/null 2>&1; wait_postgres
et1 up -d jenkins-a >/dev/null 2>&1; wait_up $A
et1 up -d jenkins-b >/dev/null 2>&1; wait_up $B
r "## ET23 results ($(date -u +%Y-%m-%dT%H:%MZ))"
r ""
r "| | Check |"
r "|---|---|"
declare_all $A; declare_all $B
check "both controllers have the cluster's lock coordinator" coordinator_on_both

# ------------------------------------------------------------------ 1. contention: 8 builds, one resource
SCEN=1; log "scenario 1: 8 builds of 4 jobs lock res, scheduled alternately on A and B"
for j in 1 2 3 4; do
  python3 - "$OUT_DIR/c$j.xml" "lock('res') { $(body '"res"' 2 1) }" <<'PY'
import sys
from xml.sax.saxutils import escape
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job"><properties/>
<definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>%s</script><sandbox>true</sandbox></definition>
</flow-definition>""" % escape(sys.argv[2]))
PY
  create_both c$j
done
for round in 1 2; do for j in 1 2 3 4; do if (( j % 2 )); then go $A c$j; else go $B c$j; fi; done; done
C1="c1#1 c1#2 c2#1 c2#2 c3#1 c3#2 c4#1 c4#2"
check "1 all 8 builds finish" eventually 300 all_finished $C1
check "1 all 8 succeed" succeeded $C1
check "1 no two builds held res at once (consoles)" no_overlap $C1
check "1 the store saw 8 holds of res, one at a time" holds_alternate res 8
info "1 res events: $(lock_events res)"
for j in 1 2 3 4; do for n in 1 2; do console_has c$j $n "Resuming" && info "1 c$j#$n resumed?"; done; done
check "1 everything is free afterwards on both" eventually 30 free_on_both
dump_state

# ------------------------------------------------------------------ 2. label pool: 6 builds, 2 resources
SCEN=2; log "scenario 2: 6 builds lock one of the pool (p1, p2)"
for j in 1 2 3; do
  python3 - "$OUT_DIR/q$j.xml" "lock(label: 'pool', quantity: 1, variable: 'R') { $(body 'env.R' 3 1) }" <<'PY'
import sys
from xml.sax.saxutils import escape
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job"><properties/>
<definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>%s</script><sandbox>true</sandbox></definition>
</flow-definition>""" % escape(sys.argv[2]))
PY
  create_both q$j
done
for round in 1 2; do go $A q1; go $B q2; go $A q3; done
C2="q1#1 q1#2 q2#1 q2#2 q3#1 q3#2"
check "2 all 6 builds finish" eventually 300 all_finished $C2
check "2 all 6 succeed" succeeded $C2
check "2 no resource of the pool held twice at once" no_overlap $C2
check "2 never more than 2 held at once" test "$(max_concurrent)" -le 2
info "2 most held at once: $(max_concurrent); p1 events: $(lock_events p1); p2 events: $(lock_events p2)"
dump_state

# ------------------------------------------------------------------ 3. two resources in opposite orders
SCEN=3; log "scenario 3: lock ra then rb, and rb then ra, 3 builds each, from both controllers"
mk2() { # job first second
  python3 - "$OUT_DIR/$1.xml" "lock(resource: '$2', extra: [[resource: '$3']]) { echo \"HOLD ra \${System.currentTimeMillis()}\"; echo \"HOLD rb \${System.currentTimeMillis()}\"; sleep 2; echo \"FREE ra \${System.currentTimeMillis()}\"; echo \"FREE rb \${System.currentTimeMillis()}\" }" <<'PY'
import sys
from xml.sax.saxutils import escape
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job"><properties/>
<definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>%s</script><sandbox>true</sandbox></definition>
</flow-definition>""" % escape(sys.argv[2]))
PY
  create_both $1
}
mk2 ab ra rb; mk2 ba rb ra
for n in 1 2 3; do go $A ab; go $B ba; done
C3="ab#1 ab#2 ab#3 ba#1 ba#2 ba#3"
check "3 all 6 builds finish (no deadlock)" eventually 300 all_finished $C3
check "3 all 6 succeed" succeeded $C3
check "3 ra and rb were each held by one build at a time" no_overlap $C3
dump_state

# ------------------------------------------------------------------ 4. holder's controller killed while it holds
SCEN=4; log "scenario 4: the controller running the holder is killed; the build is adopted and keeps the lock"
pipeline_job h4 "lock('res2') { $(body '"res2"' 15 2) }"
pipeline_job w4 "lock('res2') { $(body '"res2"' 2 1) }"
create_both h4; create_both w4
go $A h4
check "4 the holder takes res2" eventually 120 console_has h4 1 "tick 3"
H=$(owner h4 1); info "4 holder h4#1 runs on $H"
go "$(port_of "$(other "$H")")" w4
check "4 the waiter waits" eventually 60 console_has w4 1 "is not free, waiting"
sleep 5 # let the waiting build's save point ship (as ET4): a build killed before its first one is closed, not adopted
W=$(owner w4 1); info "4 waiter w4#1 runs on $W"
kill_ctl "$H"; killed=$SECONDS
check "4 the other controller adopts the holder" eventually 180 owner_is h4 1 "$(other "$H")"
info "4 adopted $((SECONDS - killed)) s after the kill"
check "4 the waiter has not got res2 while the adopted holder runs" not_in_console w4 1 HOLD
check "4 the holder finishes with SUCCESS" eventually 240 result_is h4 1 SUCCESS
check "4 the holder's ticks were not restarted (tick 1 once)" test "$(console h4 1 | grep -c -x 'tick 1')" = 1
check "4 the waiter then gets res2 and succeeds" eventually 180 result_is w4 1 SUCCESS
check "4 no overlap on res2" no_overlap h4#1 w4#1
info "4 res2 events: $(lock_events res2)"
start_ctl "$H"
check "4 everything is free afterwards on both" eventually 60 free_on_both
check "4 no lock key is left" test -z "$(lock_keys)"
dump_state

# ------------------------------------------------------------------ 5. waiter's controller killed while it waits
SCEN=5; log "scenario 5: the controller running a waiter is killed; the waiter is adopted and still gets the lock once"
pipeline_job h5 "lock('res3') { $(body '"res3"' 12 2) }"
pipeline_job w5 "lock('res3') { $(body '"res3"' 2 1) }"
create_both h5; create_both w5
go $A h5
check "5 the holder takes res3" eventually 120 console_has h5 1 "tick 2"
H=$(owner h5 1); info "5 holder h5#1 runs on $H"
# a waiter on the other controller: schedule there, and again until one lands there (each build waits for res3)
go "$(port_of "$(other "$H")")" w5
check "5 the waiter waits" eventually 60 console_has w5 1 "is not free, waiting"
sleep 5 # let the waiting build's save point ship (as ET4): a build killed before its first one is closed, not adopted
W=$(owner w5 1); info "5 waiter w5#1 runs on $W"
if [[ "$W" == "$H" ]]; then
  info "5 the waiter landed on the holder's controller: killing it adopts both (as scenario 4)"
fi
kill_ctl "$W"; killed=$SECONDS
check "5 the waiter is adopted" eventually 180 owner_is w5 1 "$(other "$W")"
info "5 adopted $((SECONDS - killed)) s after the kill"
check "5 the holder finishes with SUCCESS" eventually 240 result_is h5 1 SUCCESS
check "5 the waiter gets res3 and succeeds" eventually 180 result_is w5 1 SUCCESS
check "5 the waiter held res3 once" test "$(console w5 1 | grep -c '^HOLD ')" = 1
check "5 no overlap on res3" no_overlap h5#1 w5#1
info "5 res3 events: $(lock_events res3)"
start_ctl "$W"
check "5 everything is free afterwards on both" eventually 60 free_on_both
check "5 no lock key is left" test -z "$(lock_keys)"
dump_state

# ------------------------------------------------------------------ 6. lock timeout across controllers
SCEN=6; log "scenario 6: a lock with a timeout gives up while another controller's build holds the resource"
pipeline_job h6 "lock('res4') { $(body '"res4"' 15 2) }"
pipeline_job t6 "lock(resource: 'res4', timeoutForAllocateResource: 10, timeoutUnit: 'SECONDS') { echo 'GOT res4' }"
create_both h6; create_both t6
go $A h6
check "6 the holder takes res4" eventually 120 console_has h6 1 "tick 2"
H=$(owner h6 1)
go "$(port_of "$(other "$H")")" t6
eventually 60 has_owner t6 1 || true; T=$(owner t6 1 || echo "?"); info "6 holder on $H, timing-out build on $T"
check "6 the timing-out build ends" eventually 120 finished t6 1
info "6 t6#1 result: $(result_of t6 1)"
check "6 it did not run its body" not_in_console t6 1 "GOT res4"
check "6 it did not succeed" not_succeeded t6 1
check "6 the holder still finishes with SUCCESS" eventually 120 result_is h6 1 SUCCESS
go $B t6
check "6 a later lock of res4 succeeds at once" eventually 120 result_is t6 2 SUCCESS
check "6 no overlap on res4" no_overlap h6#1 t6#2
check "6 everything is free afterwards on both" eventually 60 free_on_both
dump_state

# ------------------------------------------------------------------ 7. reservation on one controller, lock on the other
SCEN=7; log "scenario 7: res5 reserved on A; a build locking it waits until it is unreserved on A"
post $A "/lockable-resources/reserve?resource=res5" -o /dev/null
check "7 B shows res5 held" eventually 30 held_on $B res5
pipeline_job w7 "lock('res5') { echo 'GOT res5' }"
create_both w7
go $B w7
check "7 the build waits" eventually 60 console_has w7 1 "is not free, waiting"
sleep 10
check "7 it has not got res5 10 s later" not_in_console w7 1 "GOT res5"
post $A "/lockable-resources/unreserve?resource=res5" -o /dev/null
check "7 unreserved on A: the build gets res5 and succeeds" eventually 120 result_is w7 1 SUCCESS
check "7 everything is free afterwards on both" eventually 60 free_on_both
dump_state

# ------------------------------------------------------------------ 8. the waiter's controller restarted (SIGTERM)
SCEN=8; log "scenario 8: the controller running a waiter is restarted cleanly"
pipeline_job h8 "lock('res') { $(body '"res"' 20 2) }"
pipeline_job w8 "lock('res') { $(body '"res"' 2 1) }"
create_both h8; create_both w8
go $A h8
check "8 the holder takes res" eventually 120 console_has h8 1 "tick 2"
H=$(owner h8 1)
go "$(port_of "$(other "$H")")" w8
check "8 the waiter waits" eventually 60 console_has w8 1 "is not free, waiting"
sleep 5 # let the waiting build's save point ship (as ET4): a build killed before its first one is closed, not adopted
W=$(owner w8 1); info "8 holder on $H, waiter on $W"
docker restart -t 60 "${P}jenkins-$W-1" >/dev/null; wait_up "$(port_of "$W")"; declare_all "$(port_of "$W")"
check "8 the holder finishes with SUCCESS" eventually 300 result_is h8 1 SUCCESS
check "8 the waiter gets res and succeeds" eventually 240 result_is w8 1 SUCCESS
check "8 the waiter held res once" test "$(console w8 1 | grep -c '^HOLD ')" = 1
check "8 no overlap on res" no_overlap h8#1 w8#1
check "8 everything is free afterwards on both" eventually 60 free_on_both
check "8 no lock key is left" test -z "$(lock_keys)"
dump_state

# ------------------------------------------------------------------ 9. freestyle builds needing one resource, started together
SCEN=9; log "scenario 9: freestyle builds needing fres, started on A and B at the same moment, 4 rounds"
declare_all $A; declare_all $B; declare $A fres; declare $B fres
for j in f1 f2; do
  python3 - "$OUT_DIR/$j.xml" <<'PY'
import sys
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<project><properties><org.jenkins.plugins.lockableresources.RequiredResourcesProperty plugin="lockable-resources"><resourceNames>fres</resourceNames></org.jenkins.plugins.lockableresources.RequiredResourcesProperty><hudson.model.ParametersDefinitionProperty><parameterDefinitions><hudson.model.StringParameterDefinition><name>ROUND</name><defaultValue>0</defaultValue></hudson.model.StringParameterDefinition></parameterDefinitions></hudson.model.ParametersDefinitionProperty></properties>
<concurrentBuild>true</concurrentBuild>
<builders><hudson.tasks.Shell><command>echo "HOLD fres $(date +%s%3N)"; sleep 6; echo "FREE fres $(date +%s%3N)"</command></hudson.tasks.Shell></builders></project>""")
PY
  create_both $j
done
# a parameter per round: identical requests for a job already queued would be folded into one
for round in 1 2 3 4; do
  post $A "/job/f1/buildWithParameters?ROUND=$round&delay=0sec" >/dev/null & post $B "/job/f2/buildWithParameters?ROUND=$round&delay=0sec" >/dev/null & wait
  sleep 1
done
C9="f1#1 f1#2 f1#3 f1#4 f2#1 f2#2 f2#3 f2#4"
check "9 all 8 freestyle builds finish" eventually 300 all_finished $C9
check "9 all 8 succeed" succeeded $C9
check "9 no two freestyle builds held fres at once" no_overlap $C9
for b in $C9; do console "${b%#*}" "${b#*#}" 2>/dev/null | grep -q 'failed to lock' && info "9 $b ran although it failed to lock fres"; done
for b in $C9; do console "${b%#*}" "${b#*#}" 2>/dev/null | grep -q 'taken by another controller' && info "9 $b waited for fres taken by another controller"; done
info "9 fres events: $(lock_events fres)"
check "9 everything is free afterwards on both" eventually 60 free_on_both
dump_state

# ------------------------------------------------------------------ evidence
for c in a b; do et1 logs "jenkins-$c" > "$OUT/jenkins-$c.log" 2>&1 || true; done
grep -h -E 'ClusterLocks|lockableresources' "$OUT"/jenkins-*.log | grep -E 'WARN|SEVERE|Exception' > "$OUT/lock-warnings.txt" || true
info "lock warnings in the controller logs: $(wc -l < "$OUT/lock-warnings.txt" | tr -d ' ')"
r ""
r "$FAILED failed"
exit $(( FAILED > 0 ))
