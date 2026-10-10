#!/bin/bash
# E3: two controllers on ONE shared JENKINS_HOME (the naive "HA" setup). Records concrete failures.
source "$(dirname "$0")/../lib.sh"
A=8081 B=8082 JOBS="$VALIDATION_DIR/scenarios/jobs" OUT="$RAW_DIR/E3"
rm -rf "$OUT"; mkdir -p "$OUT"
reset_env
export HOME_A=home-shared HOME_B=home-shared
dc up -d postgres azurite jenkins-a >/dev/null 2>&1; wait_up $A
# B starts after A has initialised the home (as a second replica would)
dc --profile multi up -d jenkins-b >/dev/null 2>&1; wait_up $B
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
: > "$OUT/results.md"

r "## E3 results"
r "### 1. Job created on A, visible on B?"
create_job $A freestyle "$JOBS/freestyle.xml"; create_job $A cron "$JOBS/cron.xml"
r "- A jobs: $(curl -gsf -u "$AUTH" "http://localhost:$A/api/json?tree=jobs[name]")"
r "- B jobs (no reload): $(curl -gsf -u "$AUTH" "http://localhost:$B/api/json?tree=jobs[name]")"
post $B /reload >/dev/null || true
until curl -gsf -u "$AUTH" "http://localhost:$B/job/freestyle/api/json" >/dev/null 2>&1; do sleep 2; done; sleep 3
r "- B jobs (after full /reload): $(curl -gsf -u "$AUTH" "http://localhost:$B/api/json?tree=jobs[name]")"

r "### 2. Build number collision: trigger freestyle on A and B at the same time"
build $A freestyle >/dev/null & pa=$!; build $B freestyle >/dev/null & pb=$!
wait $pa || r "- trigger on A failed"; wait $pb || r "- trigger on B failed"
idle() { [[ "$(curl -gsf -u "$AUTH" "http://localhost:$1/computer/api/json?tree=busyExecutors" | python3 -c 'import json,sys; print(json.load(sys.stdin)["busyExecutors"])')" == 0 ]]; }
sleep 10; until idle $A && idle $B; do sleep 2; done
r "- A builds: $(curl -gsf -u "$AUTH" "http://localhost:$A/job/freestyle/api/json?tree=builds[number,url]")"
r "- B builds: $(curl -gsf -u "$AUTH" "http://localhost:$B/job/freestyle/api/json?tree=builds[number,url]")"
r "- build dirs on disk: $(dc exec -T jenkins-a ls /var/jenkins_home/jobs/freestyle/builds | tr '\n' ' ')"
r "- nextBuildNumber on disk: $(dc exec -T jenkins-a cat /var/jenkins_home/jobs/freestyle/nextBuildNumber)"
r "- hostnames recorded in log of #1: $(dc exec -T jenkins-a grep -c 'hello from freestyle' /var/jenkins_home/jobs/freestyle/builds/1/log) 'hello' lines"
r '```'; dc exec -T jenkins-a sh -c 'grep -h "Started by\|hello from\|Finished" /var/jenkins_home/jobs/freestyle/builds/*/log' | tee -a "$OUT/results.md" >&2; r '```'

r "### 3. Lost update on global config"
groovy $A 'def j=jenkins.model.Jenkins.get(); j.systemMessage="written by A"; j.save()' >/dev/null
r "- config.xml systemMessage after A saves: $(dc exec -T jenkins-a grep -o '<systemMessage>[^<]*' /var/jenkins_home/config.xml)"
groovy $B 'def j=jenkins.model.Jenkins.get(); j.setNumExecutors(3); j.save()' >/dev/null
r "- config.xml systemMessage after B changes numExecutors: $(dc exec -T jenkins-a grep -o '<systemMessage>[^<]*' /var/jenkins_home/config.xml)"
r "- A in-memory systemMessage: $(groovy $A 'print(jenkins.model.Jenkins.get().systemMessage)'); B: $(groovy $B 'print(jenkins.model.Jenkins.get().systemMessage)')"

r "### 4. Cron (\`* * * * *\`) over ~3 minutes, both controllers have the job loaded"
sleep 185
r "- cron builds seen by A: $(curl -gsf -u "$AUTH" "http://localhost:$A/job/cron/api/json?tree=builds[number]")"
r "- cron builds seen by B: $(curl -gsf -u "$AUTH" "http://localhost:$B/job/cron/api/json?tree=builds[number]")"
r "- cron build dirs on disk: $(dc exec -T jenkins-a ls /var/jenkins_home/jobs/cron/builds | tr '\n' ' ')"
r '```'; dc exec -T jenkins-a sh -c 'for d in /var/jenkins_home/jobs/cron/builds/[0-9]*; do echo "$d: $(grep -o "cron run.*" $d/log | head -1)"; done' | tee -a "$OUT/results.md" >&2; r '```'

r "### 5. Collision detection"
r "- DoubleLaunchChecker SEVERE in logs: A=$(dc logs jenkins-a 2>&1 | grep -c 'Collision detected') B=$(dc logs jenkins-b 2>&1 | grep -c 'Collision detected') (check period is 60–90 min, hudson/util/DoubleLaunchChecker.java:146)"
dc logs -t --no-color jenkins-a > "$OUT/jenkins-a.log" 2>&1; dc logs -t --no-color jenkins-b > "$OUT/jenkins-b.log" 2>&1
r "- SEVERE/WARNING lines: A=$(grep -cE 'SEVERE|WARNING' "$OUT/jenkins-a.log") B=$(grep -cE 'SEVERE|WARNING' "$OUT/jenkins-b.log")"
