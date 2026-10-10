#!/bin/bash
# E2: destroy a controller + its JENKINS_HOME, start a fresh one against the same external
# services (Postgres junit-sql, Azurite artifact manager, JCasC). What survives, what collides?
# Precondition: E1 has just run (its state is in postgres + azurite).
source "$(dirname "$0")/../lib.sh"
J=8081 JOBS="$VALIDATION_DIR/scenarios/jobs" OUT="$RAW_DIR/E2"
rm -rf "$OUT"; mkdir -p "$OUT"

q() { dc exec -T postgres psql -U jenkins -At -c "$1"; }
blobs() { dc exec -T jenkins-a curl -s "http://azurite:10000/devstoreaccount1/jenkins?restype=container&comp=list" 2>/dev/null \
  | grep -o '<Name>[^<]*</Name>' | sed 's/<[^>]*>//g'; }

{
echo "## Before: external state left by E1"
echo "### junit-sql rows (job|build|count)"; q "select job, build, count(*) from caseresults group by 1,2 order by 1,2"
echo "### Azurite blobs"; blobs || echo "(container listing needs auth; see below)"
} > "$OUT/before.txt" 2>&1

log "destroy jenkins-a and its home"
dc stop agent jenkins-a >/dev/null 2>&1; dc rm -f agent jenkins-a >/dev/null 2>&1
docker volume rm validation_home-a >/dev/null
dc up -d jenkins-a >/dev/null 2>&1
wait_up $J

{
echo "## After fresh start (empty JENKINS_HOME, same external services)"
echo "jobs: $(curl -gsf -u "$AUTH" "http://localhost:$J/api/json?tree=jobs[name]")"
echo "system message: $(groovy $J 'print(jenkins.model.Jenkins.get().systemMessage)')"
echo "credential harness-secret decrypts: $(groovy $J 'print(com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentialsInItemGroup(org.jenkinsci.plugins.plaincredentials.StringCredentials, jenkins.model.Jenkins.get(), null).find{it.id=="harness-secret"}?.secret?.plainText)')"
echo "admin API token from E1 still valid: $(curl -s -o /dev/null -w '%{http_code}' -u "admin:$(cat "$OUT_DIR/token")" "http://localhost:$J/whoAmI/api/json")"
echo "nodes: $(groovy $J 'print(jenkins.model.Jenkins.get().nodes*.nodeName)')"
} > "$OUT/after-restart.txt" 2>&1
cat "$OUT/after-restart.txt" >&2

log "recreate same job names and build again (numbering restarts at 1)"
AGENT_SECRET=$(agent_secret $J agent1) dc --profile agent up -d agent >/dev/null 2>&1
for i in $(seq 1 60); do [[ "$(groovy $J 'print(jenkins.model.Jenkins.get().getComputer("agent1").isOnline())')" == true ]] && break; sleep 2; done
for j in freestyle pipeline; do create_job $J $j "$JOBS/$j.xml"; done
build $J freestyle >/dev/null; log "freestyle #1: $(wait_build $J freestyle 1)"
build $J pipeline >/dev/null; log "pipeline #1: $(wait_build $J pipeline 1)"

{
echo "## After rebuilding #1 of the same jobs"
echo "### junit-sql rows (job|build|count)"; q "select job, build, count(*) from caseresults group by 1,2 order by 1,2"
echo "### test result as shown by Jenkins for freestyle #1"
curl -gsf -u "$AUTH" "http://localhost:$J/job/freestyle/1/testReport/api/json?tree=totalCount,failCount,skipCount" ; echo
curl -gsf -u "$AUTH" "http://localhost:$J/job/pipeline/1/testReport/api/json?tree=totalCount,failCount,skipCount" ; echo
echo "### artifacts as shown by Jenkins for pipeline #1"
curl -gsf -u "$AUTH" "http://localhost:$J/job/pipeline/1/api/json?tree=artifacts[relativePath]"; echo
echo "### artifact content"
curl -gsf -u "$AUTH" "http://localhost:$J/job/pipeline/1/artifact/out/lib.txt"; echo
echo "### console of pipeline #1 (artifact lines)"
curl -gsf -u "$AUTH" "http://localhost:$J/job/pipeline/1/consoleText" | grep -iE "azure|archiv|stash|overwr|exist" || true
} > "$OUT/after-rebuild.txt" 2>&1
cat "$OUT/after-rebuild.txt" >&2
{
echo "### testReport after rebuild (passCount should be 2 / 1)"
for j in freestyle pipeline; do curl -gsf -u "$AUTH" "http://localhost:$J/job/$j/1/testReport/api/json?tree=passCount,failCount,skipCount,suites[name,cases[name]]"; echo; done
echo "### caseresults rows"; q "select job,build,suite,testname,timestamp from caseresults order by job,timestamp"
} >> "$OUT/after-rebuild.txt" 2>&1
