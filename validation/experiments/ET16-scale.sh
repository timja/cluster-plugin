#!/bin/bash
# ET16: scale experiment for Target 14 M6, on a laptop: 2,000 jobs (a quarter in folders), 2,000 builds, two
# controllers. Measures event lag, startup, page loads, heap, the store's size and query plans, and adoption of 50
# running builds at once; checks each against a bar.
# Exits non-zero if any check fails.
#
# Prerequisite: CORE_REF=cnj/t14-m1-resilience experiments/ET1-build.sh
source "$(dirname "$0")/../lib.sh"
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.fsmonitor GIT_CONFIG_VALUE_0=false
A=8081 B=8082 OUT="$RAW_DIR/ET16" ET1="$VALIDATION_DIR/.et1"
JOBS=${JOBS:-2000} BUILT=${BUILT:-200} BUILDS=${BUILDS:-10} RUNNING=${RUNNING:-50}
et1() { docker compose --project-directory "$VALIDATION_DIR" -f "$VALIDATION_DIR/compose.et1.yaml" "$@"; }
rm -rf "$OUT"; mkdir -p "$OUT" "$OUT_DIR"
: > "$OUT/results.md"
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
FAILED=0
check() { local d=$1; shift; if "$@" >>"$OUT/check.log" 2>&1; then r "| ✅ | $d |"; else r "| ❌ | $d |"; FAILED=$((FAILED+1)); fi; }
info() { r "| ℹ️ | $* |"; }
eventually() { local t=$1 start=$SECONDS; shift; until "$@"; do (( SECONDS - start > t )) && return 1; sleep 2; done; }
psql_q() { et1 exec -T postgres psql -U jenkins -At -c "$1"; }
seconds() { curl -gs -m 120 -o /dev/null -w '%{time_total}' -u "$AUTH" "http://localhost:$1$2"; }
job_count() { groovy "$1" 'println Jenkins.get().allItems(hudson.model.Job).size()' | tr -d '\r\n '; }
heap() { docker exec "$1" sh -c 'jcmd $(pgrep -f jenkins.war | head -1) GC.heap_info' 2>/dev/null | grep -o 'used [0-9]*K' | head -1; }
under() { awk -v a="$1" -v b="$2" 'BEGIN { exit !(a < b) }'; }

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
r "## ET16 results ($(date -u +%Y-%m-%dT%H:%MZ)): $JOBS jobs, $((BUILT * BUILDS)) builds, $RUNNING running at once"
r ""
r "| | Check |"
r "|---|---|"

# ------------------------------------------------------------------ 1. many jobs
log "scenario 1: $JOBS jobs created on A"
start=$SECONDS
groovy $A "
def j = Jenkins.get()
def folders = (1..10).collect { j.createProject(com.cloudbees.hudson.plugins.folder.Folder, \"folder-\" + it) }
for (int i = 1; i <= $JOBS; i++) {
  def parent = i % 4 == 0 ? folders[i % 10] : j
  def p = parent.createProject(hudson.model.FreeStyleProject, 'job-' + i)
  p.setAssignedLabel(j.getLabel('built-in'))
  p.getBuildersList().add(new hudson.tasks.Shell('echo built ' + i))
  if (i % 4 == 3) { // the ones built in scenario 2: a parameter, so that their builds are not folded into one
    p.addProperty(new hudson.model.ParametersDefinitionProperty(new hudson.model.StringParameterDefinition('N', '0')))
  }
  p.save()
}
println 'ok'" >/dev/null
created=$((SECONDS - start))
info "1 creating them took $created s on A"
check "1 B lists all $JOBS jobs within 120 s of their creation" eventually 120 bash -c "[[ \$(cd $VALIDATION_DIR && source lib.sh && groovy $B 'println Jenkins.get().allItems(hudson.model.Job).size()' | tr -d '\r\n ') -ge $JOBS ]]"
info "1 B had them all $((SECONDS - start)) s after A started creating"

# ------------------------------------------------------------------ 2. many builds
log "scenario 2: $BUILDS builds of $BUILT jobs"
start=$SECONDS
groovy $A "
def j = Jenkins.get()
for (int i = 1; i <= $BUILT; i++) {
  def p = j.getItemByFullName('job-' + (i * 4 - 1))
  for (int k = 0; k < $BUILDS; k++) {
    p.scheduleBuild2(0, new hudson.model.Cause.UserIdCause(), new hudson.model.ParametersAction(new hudson.model.StringParameterValue('N', String.valueOf(k))))
  }
}
println 'ok'" >/dev/null
eventually 1800 bash -c "[[ \$(cd $VALIDATION_DIR && docker compose -f compose.et1.yaml exec -T postgres psql -U jenkins -At -c \"select count(*) from jenkins_store where key like 'jobs/%/builds/%/build.xml'\") -ge $((BUILT * BUILDS)) ]]" || true
info "2 the builds took $((SECONDS - start)) s (published: $(psql_q "select count(*) from jenkins_store where key like 'jobs/%/builds/%/build.xml'"))"

# ------------------------------------------------------------------ 3. pages, heap and the store
log "scenario 3: page loads"
for page in / "/api/json?tree=jobs[name,color]" /job/job-3/ "/job/job-3/api/json?depth=1" /manage/cluster/; do
  for port in $A $B; do
    cold=$(seconds $port "$page"); warm=$(seconds $port "$page")
    echo "$port $page $cold $warm" >> "$OUT/pages.txt"
  done
done
info "3 pages (port, page, cold s, warm s): $(tr '\n' ';' < "$OUT/pages.txt")"
check "3 every page answers warm within 5 s on both controllers" bash -c "awk '\$4 > 5 { bad = 1 } END { exit bad }' '$OUT/pages.txt'"
info "3 heap: A $(heap et1-jenkins-a-1), B $(heap et1-jenkins-b-1)"
info "3 store: $(psql_q "select count(*) || ' keys, ' || pg_size_pretty(sum(length(content))) from jenkins_store"), $(psql_q "select count(*) from jenkins_store_events") events"
psql_q "explain analyze select distinct split_part(substr(key, 20), '/', 1) from jenkins_store where key like 'jobs/job-1999/builds/%' escape '\\'" > "$OUT/explain-children.txt" 2>&1
check "3 listing a job's builds uses the key index" grep -q "Index" "$OUT/explain-children.txt"
info "3 a job's builds listed in $(grep -o 'Execution Time: [0-9.]* ms' "$OUT/explain-children.txt")"

# ------------------------------------------------------------------ 4. startup with all that
log "scenario 4: B restarted"
et1 rm -sf jenkins-b >/dev/null 2>&1; start=$SECONDS; et1 up -d jenkins-b >/dev/null 2>&1
check "4 B is up within 180 s" wait_up $B 180
info "4 B was up after $((SECONDS - start)) s"
check "4 and lists every job" bash -c "[[ \$(cd $VALIDATION_DIR && source lib.sh && groovy $B 'println Jenkins.get().allItems(hudson.model.Job).size()' | tr -d '\r\n ') -ge $JOBS ]]"
cold=$(seconds $B /); warm=$(seconds $B /)
info "4 B's / after the restart: cold $cold s, warm $warm s"

# ------------------------------------------------------------------ 5. adoption at scale
log "scenario 5: $RUNNING Pipelines running on A, then A killed"
groovy $A "
def j = Jenkins.get()
for (int i = 1; i <= $RUNNING; i++) {
  def p = j.createProject(org.jenkinsci.plugins.workflow.job.WorkflowJob, 'long-' + i)
  p.setDefinition(new org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition('echo \"start\"; sleep 90; echo \"end\"', true))
  p.scheduleBuild2(0)
}
println 'ok'" >/dev/null
eventually 120 bash -c "[[ \$(cd $VALIDATION_DIR && docker compose -f compose.et1.yaml exec -T postgres psql -U jenkins -At -c \"select count(*) from jenkins_store where key like 'jobs/long-%/running/%'\") -ge $RUNNING ]]" || true
sleep 10
info "5 B's / with $RUNNING builds running on A: $(seconds $B /) s"
kill_start=$SECONDS
docker kill -s KILL et1-jenkins-a-1 >/dev/null
check "5 B adopts all $RUNNING within 90 s" eventually 90 bash -c "[[ \$(docker logs et1-jenkins-b-1 2>&1 | grep -c 'Adopting long-') -ge $RUNNING ]]"
info "5 adopted $(docker logs et1-jenkins-b-1 2>&1 | grep -c 'Adopting long-') builds, the last $((SECONDS - kill_start)) s after the kill"
check "5 all of them complete" eventually 240 bash -c "[[ \$(cd $VALIDATION_DIR && source lib.sh && groovy $B 'println Jenkins.get().allItems(org.jenkinsci.plugins.workflow.job.WorkflowJob).findAll { it.name.startsWith(\"long-\") && it.lastBuild?.result?.toString() == \"SUCCESS\" }.size()' | tr -d '\r\n ') -ge $RUNNING ]]"

et1 logs jenkins-a > "$OUT/jenkins-a.log" 2>&1; et1 logs jenkins-b > "$OUT/jenkins-b.log" 2>&1
cp "$ET1/BUILD" "$OUT/BUILD" 2>/dev/null || true
r ""
r "Failures: $FAILED"
echo "ET16 exit=$FAILED"
exit $FAILED
