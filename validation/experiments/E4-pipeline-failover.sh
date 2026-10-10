#!/bin/bash
# E4: cold failover. A runs pipelines (one mid-`sh` on an agent, one paused on `input`), A is
# SIGKILLed, B starts on the same JENKINS_HOME and the agent reconnects to B. Do the builds resume?
source "$(dirname "$0")/../lib.sh"
A=8081 B=8082 JOBS="$VALIDATION_DIR/scenarios/jobs" OUT="$RAW_DIR/E4"
rm -rf "$OUT"; mkdir -p "$OUT"
reset_env
export HOME_A=home-shared HOME_B=home-shared
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
: > "$OUT/results.md"

dc up -d postgres azurite jenkins-a >/dev/null 2>&1; wait_up $A
SECRET=$(agent_secret $A agent1)
# Agents address the controller via a stable alias "jenkins" (a k8s Service in real life),
# which we move from A to B at failover.
docker network disconnect validation_default validation-jenkins-a-1
docker network connect --alias jenkins-a --alias jenkins validation_default validation-jenkins-a-1
AGENT_SECRET=$SECRET AGENT_JENKINS_URL=http://jenkins:8080/ dc --profile agent up -d agent >/dev/null 2>&1
until [[ "$(groovy $A 'print(jenkins.model.Jenkins.get().getComputer("agent1").isOnline())')" == true ]]; do sleep 2; done
create_job $A sleep "$JOBS/sleep.xml"; create_job $A input "$JOBS/input.xml"
build $A sleep >/dev/null; build $A input >/dev/null
until curl -gsf -u "$AUTH" "http://localhost:$A/job/sleep/1/consoleText" | grep -q 'tick 20'; do sleep 2; done
until curl -gsf -u "$AUTH" "http://localhost:$A/job/input/1/consoleText" | grep -q 'Proceed?'; do sleep 2; done
r "## E4 results"
r "- sleep #1 at: $(curl -gsf -u "$AUTH" "http://localhost:$A/job/sleep/1/consoleText" | grep tick | tail -1); input #1 paused"
r "- program.dat present: $(dc exec -T jenkins-a sh -c 'ls /var/jenkins_home/jobs/*/builds/1/program.dat' | tr '\n' ' ')"

log "SIGKILL A"
docker kill -s KILL validation-jenkins-a-1 >/dev/null
t0=$SECONDS
dc --profile multi up -d --no-deps jenkins-b >/dev/null 2>&1; wait_up $B
r "- B up $((SECONDS - t0))s after A was killed"
docker network disconnect -f validation_default validation-jenkins-a-1 2>/dev/null || true
docker network disconnect validation_default validation-jenkins-b-1
docker network connect --alias jenkins-b --alias jenkins validation_default validation-jenkins-b-1
for i in $(seq 1 60); do [[ "$(groovy $B 'print(jenkins.model.Jenkins.get().getComputer("agent1").isOnline())')" == true ]] && break; sleep 2; done
r "- agent online on B (same secret, derived from shared master.key): $(groovy $B 'print(jenkins.model.Jenkins.get().getComputer("agent1").isOnline())')"
r "- B sees running builds: $(curl -gsf -u "$AUTH" "http://localhost:$B/computer/api/json?tree=busyExecutors")"
post $B "/job/input/1/input/Go/proceedEmpty" >/dev/null && r "- input approved on B" || r "- input approval on B FAILED"
r "- input #1 result: $(wait_build $B input 1 300)"
r "- sleep #1 result: $(wait_build $B sleep 1 400)"
r "- B ran sleep #1 to completion: last tick $(curl -gsf -u "$AUTH" "http://localhost:$B/job/sleep/1/consoleText" | grep -o 'tick [0-9]*' | tail -1)"
r '### sleep #1 console (resume section)'
r '```'; curl -gsf -u "$AUTH" "http://localhost:$B/job/sleep/1/consoleText" | grep -vE '^tick|^\+ (echo|sleep)' | tail -15 | tee -a "$OUT/results.md" >&2; r '```'
r '### input #1 console (tail)'
r '```'; curl -gsf -u "$AUTH" "http://localhost:$B/job/input/1/consoleText" | tail -12 | tee -a "$OUT/results.md" >&2; r '```'
dc logs -t --no-color jenkins-b > "$OUT/jenkins-b.log" 2>&1
