#!/bin/bash
# ET17: acceptance experiment for Target 15 (scale beyond ET16): three controllers; 10 jobs with 20,000 builds each
# (200,000 build records, copied in SQL from a real build); then a branch-indexing storm (multibranch projects scanned
# from all three controllers at once, a controller killed midway).
# ET setup (three tmpfs controllers, S0, Postgres, Azurite, token auth; compose.et17.yaml).
# Exits non-zero if any check fails.
#
# Prerequisite: CORE_REF=cnj/t15-remaining experiments/ET1-build.sh
source "$(dirname "$0")/../lib.sh"
# three controllers in one Docker VM (8 GB, shared with whatever else runs): bounded heaps (it uses about 200 MB)
export CNJ_JAVA_OPTS="${CNJ_JAVA_OPTS:-} -Xmx768m"
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.fsmonitor GIT_CONFIG_VALUE_0=false
A=8081 B=8082 C=8083 OUT="$RAW_DIR/ET17${KILL_AFTER:+-kill$KILL_AFTER}" ET1="$VALIDATION_DIR/.et1"
HJOBS=${HJOBS:-10} PER=${PER:-20000} SNAPSHOTS=${SNAPSHOTS:-100} PROJECTS=${PROJECTS:-30} BRANCHES=${BRANCHES:-10}
et1() { docker compose --project-directory "$VALIDATION_DIR" -f "$VALIDATION_DIR/compose.et1.yaml" -f "$VALIDATION_DIR/compose.et17.yaml" --profile et17 "$@"; }
# the third controller must not outlive this experiment (the others do not know its profile)
trap 'et1 rm -sf jenkins-c >/dev/null 2>&1 || true' EXIT
rm -rf "$OUT"; mkdir -p "$OUT" "$OUT_DIR"
: > "$OUT/results.md"
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
FAILED=0
check() { local d=$1; shift; if "$@" >>"$OUT/check.log" 2>&1; then r "| ✅ | $d |"; else r "| ❌ | $d |"; FAILED=$((FAILED+1)); fi; }
info() { r "| ℹ️ | $* |"; }
eventually() { local t=$1 start=$SECONDS; shift; until "$@"; do (( SECONDS - start > t )) && return 1; sleep 2; done; }
psql_q() { et1 exec -T postgres psql -U jenkins -At -c "$1"; }
seconds() { curl -gs -m 180 -o /dev/null -w '%{time_total}' -u "$AUTH" "http://localhost:$1$2"; }
under() { awk -v a="$1" -v b="$2" 'BEGIN { exit !(a < b) }'; }
json() { curl -gsf -m 60 -u "$AUTH" "http://localhost:$1$2"; }
jq_() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
heap() { docker exec "$1" sh -c 'jcmd $(pgrep -f jenkins.war | head -1) GC.heap_info' 2>/dev/null | grep -o 'used [0-9]*K' | head -1; }

[[ -f "$ET1/war/jenkins.war" ]] || { echo "run ET1-build.sh first" >&2; exit 2; }

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
et1 up -d postgres azurite >/dev/null 2>&1; wait_postgres
et1 up -d jenkins-a jenkins-b jenkins-c >/dev/null 2>&1
wait_up $A; wait_up $B; wait_up $C
psql_q "create extension if not exists pg_stat_statements" >/dev/null
r "## ET17 results ($(date -u +%Y-%m-%dT%H:%MZ)): 3 controllers, $HJOBS jobs x $PER builds, $PROJECTS multibranch projects x $BRANCHES branches"
r ""
r "| | Check |"
r "|---|---|"

# ------------------------------------------------------------------ 1. 200,000 build records
log "scenario 1: $HJOBS jobs with $PER builds each"
groovy $A "
for (int i = 1; i <= $HJOBS; i++) {
  def p = Jenkins.get().createProject(hudson.model.FreeStyleProject, 'h' + i)
  p.setAssignedLabel(Jenkins.get().getLabel('built-in'))
  p.getBuildersList().add(new hudson.tasks.Shell('echo history ' + i))
  p.save()
}" >/dev/null
for i in $(seq 1 $HJOBS); do post $A "/job/h$i/build?delay=0sec" >/dev/null; done
for i in $(seq 1 $HJOBS); do eventually 120 bash -c "[[ \$(curl -gsf -u '$AUTH' 'http://localhost:$A/job/h$i/1/api/json?tree=result') == *SUCCESS* ]]"; done
sleep 5
et1 stop jenkins-a jenkins-b jenkins-c >/dev/null 2>&1
start=$SECONDS
psql_q "insert into jenkins_store (key, content, sha256, version, updated_by, size)
  select replace(s.key, '/builds/1/', '/builds/' || n || '/'), s.content, s.sha256, nextval('jenkins_store_version'), 'synthetic', s.size
  from jenkins_store s, generate_series(2, $PER) n where s.key ~ '^jobs/h[0-9]+/builds/1/build.xml\$'" >/dev/null
psql_q "update jenkins_store set content = convert_to('$((PER + 1))', 'UTF8'), size = length('$((PER + 1))'), version = nextval('jenkins_store_version')
  where key ~ '^jobs/h[0-9]+/nextBuildNumber\$'" >/dev/null
# their permalinks, as the controllers completing them would have stored them (Target 15: StoredPermalinks)
psql_q "insert into jenkins_store (key, content, sha256, version, updated_by, size)
  select k, c, sha256(c), nextval('jenkins_store_version'), 'synthetic', length(c) from (
    select regexp_replace(key, '/nextBuildNumber\$', '/builds/permalinks') k,
      convert_to('lastCompletedBuild $PER' || chr(10) || 'lastFailedBuild -1' || chr(10) || 'lastStableBuild $PER' || chr(10)
        || 'lastSuccessfulBuild $PER' || chr(10) || 'lastUnstableBuild -1' || chr(10) || 'lastUnsuccessfulBuild -1' || chr(10), 'UTF8') c
    from jenkins_store where key ~ '^jobs/h[0-9]+/nextBuildNumber\$') p
  on conflict (key) do update set content = excluded.content, sha256 = excluded.sha256, version = excluded.version, size = excluded.size" >/dev/null
info "1 build records inserted in $((SECONDS - start)) s: $(psql_q "select count(*) from jenkins_store where key like 'jobs/h%/builds/%'") records"
# the newest builds' snapshots (what job pages show), copied in blob storage
et1 start jenkins-a >/dev/null 2>&1; wait_up $A 600
groovy $A "
def blobs = jenkins.persistence.PersistenceStores.getBlobStore()
for (int i = 1; i <= $HJOBS; i++) {
  def src = 'jobs/h' + i + '/builds/1/files.tgz'
  def bytes; blobs.get(src).withCloseable { bytes = it.bytes }
  for (int n = $PER - $SNAPSHOTS + 1; n <= $PER; n++) {
    blobs.put('jobs/h' + i + '/builds/' + n + '/files.tgz', new ByteArrayInputStream(bytes), bytes.length)
  }
}" >/dev/null
et1 stop jenkins-a >/dev/null 2>&1

# ------------------------------------------------------------------ 2. three controllers starting on it
log "scenario 2: the three controllers start on 200,000 build records"
start=$SECONDS
et1 start jenkins-a jenkins-b jenkins-c >/dev/null 2>&1
for p in $A $B $C; do wait_up $p 600; done
info "2 all three up $((SECONDS - start)) s after starting together"
check "2 started within 300 s" test $((SECONDS - start)) -le 300
for p in $A $B $C; do
  cold=$(seconds $p /job/h1/); warm=$(seconds $p /job/h1/)
  api=$(seconds $p "/job/h1/api/json?tree=builds%5Bnumber%5D%7B0,50%7D")
  last=$(seconds $p /job/h1/lastSuccessfulBuild/api/json)
  newest=$(seconds $p /job/h1/$PER/consoleText)
  info "2 controller on $p: job page cold $cold s, warm $warm s; builds{0,50} $api s; lastSuccessfulBuild $last s; newest console $newest s; heap $(heap et1-jenkins-$( [[ $p == $A ]] && echo a || ([[ $p == $B ]] && echo b || echo c))-1)"
  check "2 $p: the warm job page with $PER builds under 2 s" under "$warm" 2
done
check "2 the job lists its newest build" bash -c "[[ \$(curl -gsf -u '$AUTH' 'http://localhost:$C/job/h1/api/json?tree=lastBuild%5Bnumber%5D') == *'\"number\":$PER'* ]]"
start=$SECONDS
curl -gsf -u "$AUTH" -X POST "http://localhost:$A/job/h$HJOBS/doDelete" -o /dev/null
info "2 deleting a job with $PER builds took $((SECONDS - start)) s"
check "2 ... and the others drop it within 60 s" eventually 60 bash -c "! curl -gsf -u '$AUTH' http://localhost:$B/job/h$HJOBS/api/json -o /dev/null && ! curl -gsf -u '$AUTH' http://localhost:$C/job/h$HJOBS/api/json -o /dev/null"
check "2 ... with its records (its build numbers are kept, so a recreated job never reuses one)" bash -c "[[ \$(cd $VALIDATION_DIR && docker compose -f compose.et1.yaml -f compose.et17.yaml exec -T postgres psql -U jenkins -At -c \"select count(*) from jenkins_store where key like 'jobs/h$HJOBS/%' and key <> 'jobs/h$HJOBS/nextBuildNumber'\") == 0 ]]"

# ------------------------------------------------------------------ 3. a branch-indexing storm
log "scenario 3: $PROJECTS multibranch projects of $BRANCHES branches, scanned from all three controllers at once"
# emptied, not replaced: the running controllers have this directory bind-mounted (a new one would not be seen)
mkdir -p "$ET1/repo"; find "$ET1/repo" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
for i in $(seq 1 $PROJECTS); do
  d="$ET1/repo/r$i"; git init -q -b main "$d"
  printf "pipeline { agent none; stages { stage('hello') { steps { echo 'built r$i' } } } }\n" > "$d/Jenkinsfile"
  git -C "$d" add Jenkinsfile; git -C "$d" -c user.email=x@y -c user.name=x commit -qm init
  for b in $(seq 2 $BRANCHES); do git -C "$d" branch "b$b"; done
done
groovy $A "
for (int i = 1; i <= $PROJECTS; i++) {
  def p = Jenkins.get().createProject(org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject, 'mb' + i)
  def src = new jenkins.plugins.git.GitSCMSource('file:///repo/r' + i)
  src.setTraits([new jenkins.plugins.git.traits.BranchDiscoveryTrait()]) // a source made in code discovers nothing without it
  p.getSourcesList().add(new jenkins.branch.BranchSource(src))
  p.save()
}" >/dev/null
sleep 10
c0=$(for p in $A $B $C; do groovy $p 'println jenkins.persistence.PersistenceStores.getConflictCount() + " " + jenkins.persistence.PersistenceStores.getMergeCount()'; done | tr '\n' ';')
start=$SECONDS
for i in $(seq 1 $PROJECTS); do for p in $A $B $C; do curl -gs -u "$AUTH" -X POST "http://localhost:$p/job/mb$i/build?delay=0" -o /dev/null & done; done; wait
sleep "${KILL_AFTER:-20}" # later kills catch more builds in flight
docker kill -s KILL et1-jenkins-c-1 >/dev/null
info "3 C killed $((SECONDS - start)) s into the storm"
want=$((PROJECTS * BRANCHES))
branches_on() { groovy "$1" 'println Jenkins.get().allItems(org.jenkinsci.plugins.workflow.job.WorkflowJob).findAll { it.parent instanceof org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject }.size()' | tr -d '\r\n '; }
all_branches() { [[ $(branches_on $A) == $want && $(branches_on $B) == $want ]]; }
check "3 every branch job ($want) on A and B" eventually 600 all_branches
info "3 storm settled $((SECONDS - start)) s after the scans"
# per branch, its published records; a number C took just before it was killed may never become a build (as after a
# crash on one controller), and the next scan builds the branch then: count builds, not #1
branch_builds="select count(*) from (select substring(key from '^(jobs/mb[0-9]+/branches/[^/]+)/') b, count(*) n,
  count(*) filter (where convert_from(content, 'UTF8') like '%<result>SUCCESS</result>%') ok
  from jenkins_store where key ~ '^jobs/mb[0-9]+/branches/[^/]+/builds/[0-9]+/build.xml\$' group by 1) t"
# every branch has a published build: successful, or (those C ran, killed before their first save point) closed as
# aborted, saying why (JEP-252: what nothing can resume is closed, not lost)
branch_records() { psql_q "$branch_builds where n >= 1"; }
built_once() { [[ $(branch_records) == $want ]]; }
check "3 every branch built (a published record each: none lost)" eventually 600 built_once
lost_branches() { # branch jobs with no published build record, and what the store, the queue and the controllers say of each
  psql_q "select substring(key from '^(jobs/mb[0-9]+/branches/[^/]+)/config.xml\$') from jenkins_store where key ~ '^jobs/mb[0-9]+/branches/[^/]+/config.xml\$'
    except select substring(key from '^(jobs/mb[0-9]+/branches/[^/]+)/') from jenkins_store where key ~ '^jobs/mb[0-9]+/branches/[^/]+/builds/[0-9]+/build.xml\$'"
}
if ! built_once; then
  mkdir -p "$OUT"; f="$OUT/lost-branches.txt"; : > "$f"
  for b in $(lost_branches); do
    m=${b#jobs/}; m=${m%%/*}; br=${b##*/}
    { echo "== $b"
      echo "-- store keys"; psql_q "select key || ' v' || version || ' ' || length(content) from jenkins_store where key like '$b/%' order by key"
      echo "-- queue rows"; psql_q "select id || ' ' || state || ' origin=' || origin || ' claimed_by=' || coalesce(claimed_by, '-') || ' build=' || build_number || ' ' || updated_at from jenkins_queue where task like '%$m%$br%' order by id"
      echo "-- events"; psql_q "select seq || ' ' || kind || ' ' || key || ' ' || origin || ' ' || at from jenkins_store_events where key like '$b/%' order by seq"
      for c in a b c; do echo "-- jenkins-$c log"; docker logs et1-jenkins-$c-1 2>&1 | grep -F "$m » $br" | head -20 || true; done
    } >> "$f"
  done
  for m in $(lost_branches | sed 's#^jobs/##; s#/.*##' | sort -u); do # the projects' scans, from each controller
    { echo "== jobs/$m"
      echo "-- store keys"; psql_q "select key || ' v' || version || ' ' || length(content) from jenkins_store where key like 'jobs/$m/%' and key not like 'jobs/$m/branches/%/builds/%' order by key"
      echo "-- queue rows"; psql_q "select id || ' ' || state || ' origin=' || origin || ' claimed_by=' || coalesce(claimed_by, '-') || ' build=' || coalesce(build_number::text, '-') || ' ' || task || ' ' || updated_at from jenkins_queue where task like '%$m%' order by id"
      for p in $A $B; do echo "-- indexing log on $p"; curl -gsf -m 60 -u "$AUTH" "http://localhost:$p/job/$m/indexing/consoleText" || true; done
      for c in a b c; do echo "-- jenkins-$c log"; docker logs et1-jenkins-$c-1 2>&1 | grep -F "$m" | head -40 || true; done
    } >> "$f"
  done
  info "3 lost branches: $(lost_branches | tr '\n' ' ') (details in $f)"
fi
aborted=$(psql_q "select count(*) from jenkins_store where key ~ '^jobs/mb[0-9]+/branches/[^/]+/builds/[0-9]+/build.xml\$' and convert_from(content, 'UTF8') like '%<result>ABORTED</result>%'")
info "3 successful: $(psql_q "$branch_builds where ok >= 1"); closed as aborted (were running on C when it was killed): $aborted"
closed_by_c() { # up to 5 of the aborted builds: their console says C was gone
  local k
  for k in $(psql_q "select substring(key from '^jobs/(mb[0-9]+)/branches/([^/]+)/builds/([0-9]+)/') || '|' || substring(key from '^jobs/mb[0-9]+/branches/([^/]+)/') || '|' || substring(key from '/builds/([0-9]+)/') from jenkins_store where key ~ '^jobs/mb[0-9]+/branches/[^/]+/builds/[0-9]+/build.xml\$' and convert_from(content, 'UTF8') like '%<result>ABORTED</result>%' limit 5"); do
    IFS='|' read -r mb br n <<<"$k"
    curl -gsf -u "$AUTH" "http://localhost:$A/job/$mb/job/$br/$n/consoleText" | grep -q 'running on jenkins-c, which is gone' || return 1
  done
}
check "3 ... each aborted one closed because C was gone (5 consoles)" closed_by_c
info "3 records by result: $(psql_q "select r || '=' || count(*) from (select coalesce(substring(convert_from(content, 'UTF8') from '<result>([A-Z_]+)</result>'), 'none') r from jenkins_store where key ~ '^jobs/mb[0-9]+/branches/[^/]+/builds/[0-9]+/build.xml\$') t group by r order by r" | tr '\n' ' ')"
info "3 branches with more than one build: $(psql_q "$branch_builds where n > 1")"
twice=$(psql_q "select b from (select substring(key from '^(jobs/mb[0-9]+/branches/[^/]+)/') b, count(*) filter (where convert_from(content, 'UTF8') like '%<result>SUCCESS</result>%') ok from jenkins_store where key ~ '^jobs/mb[0-9]+/branches/[^/]+/builds/[0-9]+/build.xml\$' group by 1) t where ok > 1")
[[ -n $twice ]] && info "3 built successfully more than once: $(echo $twice)"
check "3 ... exactly once: no branch built successfully twice (concurrent scans of a project)" test "$(psql_q "$branch_builds where ok > 1")" = 0
c1=$(for p in $A $B; do groovy $p 'println jenkins.persistence.PersistenceStores.getConflictCount() + " " + jenkins.persistence.PersistenceStores.getMergeCount()'; done | tr '\n' ';')
info "3 conflicts and merges (A;B;C) before: $c0 after (A;B): $c1"
info "3 heap: A $(heap et1-jenkins-a-1), B $(heap et1-jenkins-b-1)"
psql_q "select round(total_exec_time) || ' ms, ' || calls || ' calls: ' || left(regexp_replace(query, '\s+', ' ', 'g'), 120) from pg_stat_statements order by total_exec_time desc limit 8" > "$OUT/top-statements.txt" 2>&1 || true
info "3 top statement: $(head -1 "$OUT/top-statements.txt")"
et1 start jenkins-c >/dev/null 2>&1; wait_up $C 600
check "3 C, restarted, has every branch job" eventually 300 bash -c "[[ \$(cd $VALIDATION_DIR && source lib.sh && groovy $C 'println Jenkins.get().allItems(org.jenkinsci.plugins.workflow.job.WorkflowJob).findAll { it.parent instanceof org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject }.size()' | tr -d '\r\n ') == $want ]]"

for c in a b c; do et1 logs jenkins-$c > "$OUT/jenkins-$c.log" 2>&1; done
cp "$ET1/BUILD" "$OUT/BUILD" 2>/dev/null || true
r ""
r "Failures: $FAILED"
exit $(( FAILED > 0 ))
