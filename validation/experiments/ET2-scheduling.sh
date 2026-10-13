#!/bin/bash
# ET2: acceptance experiment for Target 2 (cluster-wide build numbers, exactly-once triggers).
# Same setup as ET1: two controllers on tmpfs homes, identical JCasC, seeded keys (S0), Postgres store.
# Exits non-zero if any check fails.
#
# Prerequisite: CORE_REF=cnj/t2-m2-trigger-coordination experiments/ET1-build.sh
source "$(dirname "$0")/../lib.sh"
# the scratch repos are throwaway; never start fsmonitor daemons for them (they can hang in the background)
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.fsmonitor GIT_CONFIG_VALUE_0=false
A=8081 B=8082 JOBS="$VALIDATION_DIR/scenarios/jobs" OUT="$RAW_DIR/ET2" ET1="$VALIDATION_DIR/.et1"
et1() { docker compose --project-directory "$VALIDATION_DIR" -f "$VALIDATION_DIR/compose.et1.yaml" "$@"; }
rm -rf "$OUT"; mkdir -p "$OUT" "$OUT_DIR"
: > "$OUT/results.md"; : > "$OUT/builds.jsonl"
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
FAILED=0
check() { local d=$1; shift; if "$@" >>"$OUT/check.log" 2>&1; then r "| ✅ | $d |"; else r "| ❌ | $d |"; FAILED=$((FAILED+1)); fi; }
eventually() { local t=$1 start=$SECONDS; shift; until "$@"; do (( SECONDS - start > t )) && return 1; sleep 2; done; }
http() { curl -gs -o /dev/null -w '%{http_code}' -u "$AUTH" "http://localhost:$1$2"; }
exists() { [[ "$(http "$1" "$2/api/json")" == 200 ]]; }
json() { curl -gsf -u "$AUTH" "http://localhost:$1$2"; }
idle() { [[ "$(json "$1" "/computer/api/json?tree=busyExecutors" | python3 -c 'import json,sys; print(json.load(sys.stdin)["busyExecutors"])')" == 0 && \
          "$(json "$1" "/queue/api/json?tree=items[id]" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["items"]))')" == 0 ]]; }
wait_idle() { sleep 3; eventually 300 idle "$1"; }
psql_q() { et1 exec -T postgres psql -U jenkins -At -c "$1"; }
container_of() { [[ $1 == "$A" ]] && echo jenkins-a || echo jenkins-b; }

# ran_on <job key> <number>: the port of the controller that first wrote the build's record (builds are shared since T3,
# so every controller lists every build; the writer is the one that ran it)
ran_on() {
  local n; n=$(psql_q "select m.name from jenkins_store_events e left join jenkins_members m on m.origin = e.origin where e.key in ('$1/builds/$2/build.xml', '$(echo "$1" | sed -E 's#/jobs/([^/]+)$#/branches/\1#')/builds/$2/build.xml') and e.kind = 'WRITTEN' order by e.seq limit 1" </dev/null)
  case "$n" in jenkins-a) echo $A ;; jenkins-b) echo $B ;; *) echo "?" ;; esac
}
# snap <job path, e.g. /job/x or /job/mb/job/main> <ports...>: record every build the controllers know about, with the controller that ran it
snap() {
  local path=$1 key; shift
  key=$(echo "$path" | sed -e 's#^/job/#jobs/#' -e 's#/job/#/jobs/#g')
  for port in "$@"; do
    json "$port" "$path/api/json?tree=builds[number,timestamp]" 2>/dev/null \
      | python3 -c 'import json,sys
d=json.load(sys.stdin)
for b in d.get("builds", []):
    print(b["number"], b["timestamp"])' | while read -r num ts; do
        grep -q "\"job\": \"$path\", \"number\": $num," "$OUT/builds.jsonl" && continue
        c=$(ran_on "$key" "$num"); [[ $c == "?" ]] && continue # still running: no record yet; the next snap gets it
        echo "{\"job\": \"$path\", \"number\": $num, \"controller\": \"$c\", \"ts\": $ts}" >> "$OUT/builds.jsonl"
      done || true
  done
}
# builds <job path> [since-epoch-ms]: unique builds recorded so far, as "number controller minute" lines
builds() {
  python3 - "$OUT/builds.jsonl" "$1" "${2:-0}" <<'PY'
import json, sys
seen = {}
for line in open(sys.argv[1]):
    b = json.loads(line)
    if b["job"] == sys.argv[2] and b["ts"] >= int(sys.argv[3]):
        seen.setdefault((b["number"], b["controller"]), b)
for (n, c), b in sorted(seen.items()):
    print(n, c, b["ts"] // 60000)
PY
}
count() { builds "$@" | wc -l | tr -d ' '; }
numbers_unique() { [[ -z "$(builds "$1" | awk '{print $1}' | sort | uniq -d)" ]]; }
at_most_one_per_minute() { [[ -z "$(builds "$@" | awk '{print $3}' | sort | uniq -d)" ]]; }
# exactly once per cron tick: every build of the job corresponds to one claimed tick, and vice versa.
# (Trigger.Cron catches up a late minute, so two ticks can legitimately fire within one wall-clock minute.)
claims_of() { psql_q "select count(*) from jenkins_trigger_claims where item='$1'"; }
one_build_per_tick() { snap "/job/$1" "${@:2}"; [[ "$(count "/job/$1")" == "$(claims_of "$1")" ]]; }
tick_info() { r "| ℹ️ | $1: $(count /job/cron1) builds, $(claims_of cron1) claimed ticks; wall-clock minutes with 2 builds (cron catch-up): $(builds /job/cron1 | awk '{print $3}' | sort | uniq -d | wc -l | tr -d ' ') |"; }
now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }

[[ -f "$ET1/war/jenkins.war" && -n "$(ls "$ET1"/plugins/*.hpi 2>/dev/null)" ]] || { echo "run ET1-build.sh first" >&2; exit 2; }

# ------------------------------------------------------------------ setup (as ET1)
et1 down -v --remove-orphans >/dev/null 2>&1 || true
rm -rf "$ET1/seed" "$ET1/repo"; mkdir -p "$ET1/seed" "$ET1/repo"
docker rm -fv et1-seed >/dev/null 2>&1 || true
docker run -d --name et1-seed -p 8089:8080 --tmpfs /var/jenkins_home:uid=1000,gid=1000 \
  -e JAVA_OPTS="-Djenkins.install.runSetupWizard=false" -e CASC_JENKINS_CONFIG=/casc -v "$VALIDATION_DIR/jenkins/casc-et1:/casc:ro" \
  -v "$ET1/war:/war-override:ro" -v "$ET1/plugins:/extra-plugins:ro" cnj/jenkins:dev >/dev/null
wait_up 8089
groovy 8089 'hudson.util.Secret.fromString("x").getEncryptedValue(); org.jenkinsci.main.modules.instance_identity.InstanceIdentity.get()' >/dev/null
docker exec et1-seed tar -C /var/jenkins_home -cf - secret.key secret.key.not-so-secret identity.key.enc secrets | tar -C "$ET1/seed" -xf -
docker rm -fv et1-seed >/dev/null
for repo in mb poll; do
  ( mkdir -p "$ET1/repo/$repo" && cd "$ET1/repo/$repo" && git init -q -b main && git config user.email et2@example.com && git config user.name et2
    echo 'echo "branch ${env.BRANCH_NAME}"' > Jenkinsfile && git add Jenkinsfile && git commit -qm init )
done
(cd "$ET1/repo/mb" && git branch feature)
commit() { (cd "$ET1/repo/$1" && date +%s%N > stamp && git add stamp && git commit -qm "change $(date +%s)"); }

et1 up -d postgres >/dev/null 2>&1; wait_postgres
et1 up -d jenkins-a jenkins-b >/dev/null 2>&1
wait_up $A; wait_up $B
r "## ET2 results ($(date -u +%Y-%m-%dT%H:%MZ))"
r ""
r "| | Check |"
r "|---|---|"
check "both controllers are live members" eventually 30 bash -c "[[ \$(cd $VALIDATION_DIR && docker compose -f compose.et1.yaml exec -T postgres psql -U jenkins -At -c \"select count(*) from jenkins_members where heartbeat_at > now() - interval '20 seconds'\") == 2 ]]"

# ------------------------------------------------------------------ 2. manual builds on both controllers
log "scenario 2: 20 + 20 concurrent manual builds"
python3 - "$OUT_DIR/manual.xml" <<'PY'
import sys
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job"><properties><hudson.model.ParametersDefinitionProperty><parameterDefinitions>
<hudson.model.StringParameterDefinition><name>ID</name><defaultValue>x</defaultValue></hudson.model.StringParameterDefinition>
</parameterDefinitions></hudson.model.ParametersDefinitionProperty></properties>
<definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>echo "manual ${params.ID}"</script><sandbox>true</sandbox></definition>
</flow-definition>""")
PY
create_job $A manual "$OUT_DIR/manual.xml"
eventually 15 exists $B /job/manual
for i in $(seq 1 20); do
  post $A "/job/manual/buildWithParameters?ID=a$i&delay=0sec" >/dev/null 2>&1 &
  post $B "/job/manual/buildWithParameters?ID=b$i&delay=0sec" >/dev/null 2>&1 &
done
wait; wait_idle $A; wait_idle $B
snap /job/manual $A $B
manual_numbers=$(builds /job/manual | awk '{print $1}' | sort -n | tr '\n' ' ')
check "2 forty builds across A and B are numbered exactly 1..40 (got: $manual_numbers)" test "$manual_numbers" = "$(seq 1 40 | tr '\n' ' ')"
check "2 no build number was used on both controllers" numbers_unique /job/manual
r "| ℹ️ | 2 builds per controller: A=$(builds /job/manual | grep -c " $A ") B=$(builds /job/manual | grep -c " $B ") |"

# ------------------------------------------------------------------ 1. cron exactly once
log "scenario 1: cron * * * * * on two controllers for ~4.5 minutes"
cron_start=$(now_ms)
create_job $A cron1 "$JOBS/cron.xml"
eventually 15 exists $B /job/cron1
sleep 270
snap /job/cron1 $A $B
r "| ℹ️ | 1 cron1 builds: $(builds /job/cron1 | awk '{print "#"$1"@"($2=="'$A'"?"A":"B")}' | tr '\n' ' ') |"
check "1 cron fired (≥3 builds in ~4.5 min: $(count /job/cron1))" test "$(count /job/cron1)" -ge 3
check "1 exactly one cron build per claimed tick across controllers" eventually 30 one_build_per_tick cron1 $A $B
tick_info "1"
check "1 cron build numbers are unique across controllers" numbers_unique /job/cron1
owner_port=$(builds /job/cron1 | awk '{print $2}' | sort | uniq -c | sort -rn | head -1 | awk '{print $2}')
check "1 all cron builds ran on one controller (affinity)" test "$(builds /job/cron1 | awk '{print $2}' | sort -u | wc -l | tr -d ' ')" = 1

# ------------------------------------------------------------------ 3. SCM polling exactly once
log "scenario 3: SCM polling every minute"
cat > "$OUT_DIR/poll.xml" <<'XML'
<?xml version='1.1' encoding='UTF-8'?>
<project>
  <scm class="hudson.plugins.git.GitSCM" plugin="git">
    <configVersion>2</configVersion>
    <userRemoteConfigs><hudson.plugins.git.UserRemoteConfig><url>file:///repo/poll</url></hudson.plugins.git.UserRemoteConfig></userRemoteConfigs>
    <branches><hudson.plugins.git.BranchSpec><name>*/main</name></hudson.plugins.git.BranchSpec></branches>
  </scm>
  <assignedNode>built-in</assignedNode>
  <triggers><hudson.triggers.SCMTrigger><spec>* * * * *</spec><ignorePostCommitHooks>false</ignorePostCommitHooks></hudson.triggers.SCMTrigger></triggers>
  <builders><hudson.tasks.Shell><command>git log -1 --format=%s</command></hudson.tasks.Shell></builders>
</project>
XML
create_job $A poll "$OUT_DIR/poll.xml"
eventually 15 exists $B /job/poll
pollcount() { snap /job/poll $A $B; [[ "$(count /job/poll)" == "$1" ]]; }
check "3 first poll (no previous build) builds once" eventually 180 pollcount 1
commit poll
check "3 one commit → exactly one more build" eventually 180 pollcount 2
sleep 90; snap /job/poll $A $B
check "3 no further builds without commits (still 2)" test "$(count /job/poll)" = 2
commit poll
check "3 second commit → exactly one more build" eventually 180 pollcount 3
# Target 15: the polling log is where polling ran (one controller per tick); both controllers show it
pollog() { # the log part of the job's polling log page (the page itself has per-request values such as crumbs)
  curl -gsf -u "$AUTH" "http://localhost:$1/job/poll/scmPollLog/" 2>/dev/null | python3 -c "import re,sys; m=re.search(r'<pre[^>]*>(.*?)</pre>', sys.stdin.read(), re.S); print(m.group(1) if m else '')"; }
pollog $A > "$OUT/polling-log-a.txt" || true; pollog $B > "$OUT/polling-log-b.txt" || true
check "3 the polling log reads the same on both controllers (served by the one that polled)" bash -c "grep -q 'Started on' '$OUT/polling-log-a.txt' && cmp -s '$OUT/polling-log-a.txt' '$OUT/polling-log-b.txt'"
sleep 90; snap /job/poll $A $B
check "3 polling never built the same change twice (3 builds total: $(count /job/poll))" test "$(count /job/poll)" = 3
check "3 polling stayed on one controller (affinity)" test "$(builds /job/poll | awk '{print $2}' | sort -u | wc -l | tr -d ' ')" = 1

# ------------------------------------------------------------------ 4. periodic branch indexing exactly once
log "scenario 4: multibranch with 1-minute periodic indexing"
sed -e 's#file:///tmp/repo#file:///repo/mb#' \
    -e 's#<triggers/>#<triggers><com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger plugin="cloudbees-folder"><spec>* * * * *</spec><interval>60000</interval></com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger></triggers>#' \
    "$JOBS/multibranch.xml" > "$OUT_DIR/mbp.xml"
create_job $A mbp "$OUT_DIR/mbp.xml"
post $A /job/mbp/build >/dev/null; wait_idle $A
eventually 30 exists $B /job/mbp/job/feature
sleep 150   # two or three periodic indexings, wherever they run
snap /job/mbp/job/main $A $B; snap /job/mbp/job/feature $A $B
check "4 periodic indexing did not rebuild existing branches (main: $(count /job/mbp/job/main), feature: $(count /job/mbp/job/feature))" \
  test "$(count /job/mbp/job/main)" = 1 -a "$(count /job/mbp/job/feature)" = 1
(cd "$ET1/repo/mb" && git branch feature2)
f2() { snap /job/mbp/job/feature2 $A $B; [[ "$(count /job/mbp/job/feature2)" -ge 1 ]]; }
check "4 a new branch is built by periodic indexing" eventually 240 f2
sleep 150; snap /job/mbp/job/feature2 $A $B; snap /job/mbp/job/main $A $B
check "4 the new branch was built exactly once cluster-wide ($(count /job/mbp/job/feature2))" test "$(count /job/mbp/job/feature2)" = 1
check "4 existing branches still built once" test "$(count /job/mbp/job/main)" = 1
r "| ℹ️ | 4 periodic indexing owner: $(groovy $A 'print(io.jenkins.plugins.cnj.store.PostgresTriggerCoordinator.currentOwnerOf("mbp") == jenkins.persistence.PersistenceStores.get().getOrigin() ? "A" : "B")') |"

# ------------------------------------------------------------------ 5. failover
survivor=$([[ $owner_port == "$A" ]] && echo $B || echo $A)
owner_name=$(container_of "$owner_port")
log "scenario 5: SIGKILL the cron owner ($owner_name)"
snap /job/cron1 $A $B
kill_ms=$(now_ms)
docker kill -s KILL "et1-$owner_name-1" >/dev/null
sleep 150
snap /job/cron1 $survivor
check "5 survivor took over cron within 2.5 min ($(count /job/cron1 "$kill_ms") builds after the kill)" test "$(count /job/cron1 "$kill_ms")" -ge 1
check "5 still exactly one cron build per claimed tick" eventually 30 one_build_per_tick cron1 $survivor
tick_info "5"
check "5 cron build numbers still unique" numbers_unique /job/cron1

# ------------------------------------------------------------------ 6. rejoin
log "scenario 6: start a fresh $owner_name (empty home)"
et1 rm -sf "$owner_name" >/dev/null 2>&1
rejoin_ms=$(now_ms)
et1 up -d "$owner_name" >/dev/null 2>&1; wait_up "$owner_port"
sleep 180
snap /job/cron1 $A $B
check "6 cron kept firing across the rejoin ($(count /job/cron1 "$rejoin_ms") builds in 3 min)" test "$(count /job/cron1 "$rejoin_ms")" -ge 2
check "6 exactly one cron build per claimed tick across failover and rejoin" eventually 30 one_build_per_tick cron1 $A $B
tick_info "6"
check "6 cron build numbers unique across all incarnations" numbers_unique /job/cron1

# ------------------------------------------------------------------ 7. continuity
log "scenario 7: numbering continues on a fresh controller and after a rename"
post "$owner_port" "/job/manual/buildWithParameters?ID=fresh&delay=0sec" >/dev/null; wait_idle "$owner_port"
last() { json "$1" "$2/api/json?tree=lastBuild[number]" | python3 -c 'import json,sys; print((json.load(sys.stdin).get("lastBuild") or {}).get("number"))'; }
check "7 fresh controller continues the sequence (#$(last "$owner_port" /job/manual), expected 41)" test "$(last "$owner_port" /job/manual)" = 41
post "$survivor" "/job/manual/confirmRename?newName=manual2" >/dev/null || true
eventually 15 exists "$owner_port" /job/manual2
post "$owner_port" "/job/manual2/buildWithParameters?ID=renamed&delay=0sec" >/dev/null; wait_idle "$owner_port"
check "7 renamed job continues the sequence (#$(last "$owner_port" /job/manual2), expected 42)" test "$(last "$owner_port" /job/manual2)" = 42

# ------------------------------------------------------------------ evidence
psql_q "select item, trigger, count(*), count(distinct origin) from jenkins_trigger_claims group by 1, 2 order by 1, 2" > "$OUT/claims.txt"
psql_q "select key, convert_from(content, 'UTF8') from jenkins_store where key like '%nextBuildNumber' order by key" > "$OUT/build-numbers.txt"
et1 logs -t --no-color jenkins-a > "$OUT/jenkins-a.log" 2>&1
et1 logs -t --no-color jenkins-b > "$OUT/jenkins-b.log" 2>&1
cp "$ET1/BUILD" "$OUT/BUILD" 2>/dev/null || true
r ""
r "Failures: $FAILED"
exit $FAILED
