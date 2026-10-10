#!/bin/bash
# E1: inventory every JENKINS_HOME path Jenkins reads/writes across a realistic scenario.
# Output: findings/raw/E1/{inotify.log,markers.log,jenkins.log,home-files.txt,...}
source "$(dirname "$0")/../lib.sh"
J=8081 JOBS="$VALIDATION_DIR/scenarios/jobs" OUT="$RAW_DIR/E1"

reset_env
mark start
CNJ_INOTIFY=1 dc up -d --build postgres azurite jenkins-a >/dev/null 2>&1
wait_up $J
mark booted

log "API token for admin"
groovy $J 'def u=hudson.model.User.getById("admin",false); def t=u.getProperty(jenkins.security.ApiTokenProperty).tokenStore.generateNewToken("e1"); u.save(); println(t.plainValue)' > "$OUT_DIR/token"
mark token-created
curl -sf -u "admin:$(cat "$OUT_DIR/token")" "http://localhost:$J/whoAmI/api/json" >/dev/null
mark token-used

log "global config change"
groovy $J 'def j=jenkins.model.Jenkins.get(); j.systemMessage="changed by E1"; j.save()' >/dev/null
mark global-config-saved

log "agent"
AGENT_SECRET=$(agent_secret $J agent1) dc --profile agent up -d agent >/dev/null 2>&1
for i in $(seq 1 60); do
  [[ "$(groovy $J 'print(jenkins.model.Jenkins.get().getComputer("agent1").isOnline())')" == true ]] && break; sleep 2
done
mark agent-online

log "jobs"
for j in freestyle pipeline input cron; do create_job $J $j "$JOBS/$j.xml"; done
mark jobs-created

log "builds"
build $J freestyle >/dev/null; build $J pipeline >/dev/null
log "freestyle #1: $(wait_build $J freestyle 1)"; log "pipeline #1: $(wait_build $J pipeline 1)"
mark builds-done
build $J input >/dev/null
for i in $(seq 1 60); do curl -sf -u "$AUTH" "http://localhost:$J/job/input/1/consoleText" | grep -q 'Proceed?' && break; sleep 2; done
mark input-paused

log "waiting for a cron tick"
for i in $(seq 1 75); do curl -sf -u "$AUTH" "http://localhost:$J/job/cron/1/api/json" >/dev/null 2>&1 && break; sleep 2; done
mark cron-fired

log "restart (input build must resume)"
dc stop jenkins-a >/dev/null 2>&1
mark stopped
CNJ_INOTIFY=1 dc start jenkins-a >/dev/null 2>&1
wait_up $J
mark restarted
sleep 5
post $J "/job/input/1/input/Go/proceedEmpty" >/dev/null || log "input proceed failed"
log "input #1: $(wait_build $J input 1)"
mark input-done

log "collecting"
rm -rf "$OUT"; mkdir -p "$OUT"
cp "$OUT_DIR/markers.log" "$OUT/"
cp "$OUT_DIR/jenkins-a.inotify.log" "$OUT/inotify.log"
dc logs -t --no-color jenkins-a > "$OUT/jenkins.log" 2>&1
dc exec -T jenkins-a sh -c 'cd $JENKINS_HOME && find . -path ./war -prune -o -path ./plugins -prune -o -type f -printf "%s %p\n"' | sort -k2 > "$OUT/home-files.txt"
dc exec -T jenkins-a sh -c 'cd $JENKINS_HOME && du -sk war plugins . 2>/dev/null' > "$OUT/home-du.txt"
dc exec -T postgres psql -U jenkins -c '\dt' > "$OUT/postgres-tables.txt" 2>&1 || true
dc exec -T postgres psql -U jenkins -c 'select job, build, count(*) from caseresults group by 1,2' >> "$OUT/postgres-tables.txt" 2>&1 || true
for j in freestyle pipeline input cron; do
  curl -sf -u "$AUTH" "http://localhost:$J/job/$j/1/consoleText" > "$OUT/console-$j-1.txt" || true
done
log "done: $OUT"
