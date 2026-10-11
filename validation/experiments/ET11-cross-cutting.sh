#!/bin/bash
# ET11: acceptance experiment for Target 11 (concurrent edits merged; nothing unshared written under the homes).
# ET setup (two tmpfs controllers, S0, Postgres, Azurite, token auth). Exits non-zero if any check fails.
#
# Prerequisite: CORE_REF=cnj/t10-m1-import-api experiments/ET1-build.sh
source "$(dirname "$0")/../lib.sh"
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.fsmonitor GIT_CONFIG_VALUE_0=false
A=8081 B=8082 OUT="$RAW_DIR/ET11" ET1="$VALIDATION_DIR/.et1"
et1() { docker compose --project-directory "$VALIDATION_DIR" -f "$VALIDATION_DIR/compose.et1.yaml" "$@"; }
rm -rf "$OUT"; mkdir -p "$OUT" "$OUT_DIR"
: > "$OUT/results.md"
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
FAILED=0
check() { local d=$1; shift; if "$@" >>"$OUT/check.log" 2>&1; then r "| ✅ | $d |"; else r "| ❌ | $d |"; FAILED=$((FAILED+1)); fi; }
info() { r "| ℹ️ | $* |"; }
eventually() { local t=$1 start=$SECONDS; shift; until "$@"; do (( SECONDS - start > t )) && return 1; sleep 1; done; }
json() { curl -gsf -u "$AUTH" "http://localhost:$1$2"; }
jq_() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
g() { groovy "$1" "$2" | tr -d '\r' | sed -e 's/[[:space:]]*$//'; }
g_is() { [[ "$(g "$1" "$2")" == "$3" ]]; }
exists() { [[ "$(curl -gs -o /dev/null -w '%{http_code}' -u "$AUTH" "http://localhost:$1$2/api/json")" == 200 ]]; }
both_are() { g_is $A "$1" "$2" && g_is $B "$1" "$2"; }
counter() { g "$1" "println jenkins.persistence.PersistenceStores.$2()"; }
findings() { json "$1" '/manage/cluster/compatibility/api/json?depth=1' | jq_ '"; ".join("%s (%d, e.g. %s)" % (f["pattern"], f["count"], f["example"]) for f in d["findings"])'; }

[[ -f "$ET1/war/jenkins.war" ]] || { echo "run ET1-build.sh first" >&2; exit 2; }

# ------------------------------------------------------------------ setup (as ET6, JCasC with nodes for agent1 unused)
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
r "## ET11 results ($(date -u +%Y-%m-%dT%H:%MZ))"
r ""
r "| | Check |"
r "|---|---|"

# ------------------------------------------------------------------ 1. concurrent edits of different settings, 10 rounds
log "scenario 1: A and B save different global settings at the same moment, 10 times"
merges_before=$(( $(counter $A getMergeCount) + $(counter $B getMergeCount) ))
ok=0
for round in $(seq 1 10); do
  g $A "Jenkins.get().setQuietPeriod($round)" >/dev/null & g $B "Jenkins.get().setScmCheckoutRetryCount($round)" >/dev/null & wait
  if eventually 10 both_are 'println Jenkins.get().quietPeriod + "/" + Jenkins.get().scmCheckoutRetryCount' "$round/$round"; then ok=$((ok+1)); else
    info "1 round $round: A $(g $A 'println Jenkins.get().quietPeriod + "/" + Jenkins.get().scmCheckoutRetryCount'), B $(g $B 'println Jenkins.get().quietPeriod + "/" + Jenkins.get().scmCheckoutRetryCount')"; fi
done
merges=$(( $(counter $A getMergeCount) + $(counter $B getMergeCount) - merges_before ))
info "1 rounds where both changes survived on both controllers: $ok/10; writes merged: $merges"
check "1 both changes survive every round, on both controllers ($ok/10)" test "$ok" = 10
check "1 stored config.xml has both (round 10)" bash -c "cd $VALIDATION_DIR && docker compose -f compose.et1.yaml exec -T postgres psql -U jenkins -At -c \"select convert_from(content, 'UTF8') from jenkins_store where key = 'config.xml'\" | grep -q '<quietPeriod>10</quietPeriod>' && cd $VALIDATION_DIR && docker compose -f compose.et1.yaml exec -T postgres psql -U jenkins -At -c \"select convert_from(content, 'UTF8') from jenkins_store where key = 'config.xml'\" | grep -q '<scmCheckoutRetryCount>10</scmCheckoutRetryCount>'"

# ------------------------------------------------------------------ 2. the same setting on both sides
log "scenario 2: A and B set the system message at the same moment"
conflicts_before=$(( $(counter $A getConflictCount) + $(counter $B getConflictCount) ))
g $A 'Jenkins.get().setSystemMessage("from A")' >/dev/null & g $B 'Jenkins.get().setSystemMessage("from B")' >/dev/null & wait
sleep 3
ma=$(g $A 'println Jenkins.get().systemMessage'); mb=$(g $B 'println Jenkins.get().systemMessage')
conflicts=$(( $(counter $A getConflictCount) + $(counter $B getConflictCount) - conflicts_before ))
info "2 A says '$ma', B says '$mb'; conflicting writes: $conflicts"
check "2 A and B agree on one of the two values" bash -c "[[ '$ma' == '$mb' && ( '$ma' == 'from A' || '$ma' == 'from B' ) ]]"

# ------------------------------------------------------------------ 3. job configuration
log "scenario 3: A changes a job's description while B adds a trigger"
cat > "$OUT_DIR/j11.xml" <<'XML'
<?xml version='1.1' encoding='UTF-8'?>
<project><description>original</description><builders/><triggers/></project>
XML
create_job $A j11 "$OUT_DIR/j11.xml"; eventually 15 exists $B /job/j11
ok3=0
for round in 1 2 3; do
  g $A "Jenkins.get().getItem('j11').setDescription('round $round from A')" >/dev/null &
  g $B "def j = Jenkins.get().getItem('j11'); j.addTrigger(new hudson.triggers.TimerTrigger('H H $round * *')); j.save()" >/dev/null &
  wait
  if eventually 10 both_are "def j = Jenkins.get().getItem('j11'); println j.description + '|' + j.getTrigger(hudson.triggers.TimerTrigger)?.spec" "round $round from A|H H $round * *"; then ok3=$((ok3+1)); else
    info "3 round $round: A $(g $A "def j = Jenkins.get().getItem('j11'); println j.description + '|' + j.getTrigger(hudson.triggers.TimerTrigger)?.spec"), B $(g $B "def j = Jenkins.get().getItem('j11'); println j.description + '|' + j.getTrigger(hudson.triggers.TimerTrigger)?.spec")"; fi
done
check "3 description (A) and trigger (B) both survive, on both controllers ($ok3/3)" test "$ok3" = 3

# ------------------------------------------------------------------ 4. inventory: nothing unshared written under the homes
log "scenario 4: a standard scenario, then what each home holds that the cluster does not share"
python3 - "$OUT_DIR/fs11.xml" "$OUT_DIR/p11.xml" <<'PY'
import sys
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<project><builders><hudson.tasks.Shell><command>echo x > r.txt; cat > r.xml &lt;&lt;EOF
&lt;testsuite name="s" tests="1"&gt;&lt;testcase classname="c" name="t"/&gt;&lt;/testsuite&gt;
EOF</command></hudson.tasks.Shell></builders>
<publishers><hudson.tasks.ArtifactArchiver><artifacts>r.txt</artifacts></hudson.tasks.ArtifactArchiver><hudson.tasks.Fingerprinter><targets>r.txt</targets></hudson.tasks.Fingerprinter><hudson.tasks.junit.JUnitResultArchiver plugin="junit"><testResults>r.xml</testResults></hudson.tasks.junit.JUnitResultArchiver></publishers></project>""")
open(sys.argv[2], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job"><definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>stage('one') { echo 'pipeline' }</script><sandbox>true</sandbox></definition></flow-definition>""")
PY
create_job $A fs11 "$OUT_DIR/fs11.xml"; create_job $B p11 "$OUT_DIR/p11.xml"
eventually 15 exists $B /job/fs11; eventually 15 exists $A /job/p11
for p in $A $B; do post $p "/job/fs11/build?delay=0sec" >/dev/null; post $p "/job/p11/build?delay=0sec" >/dev/null; done
sleep 30
g $A 'Jenkins.get().securityRealm.createAccount("u11", "u11-password-1")' >/dev/null
g $B 'def p = com.cloudbees.plugins.credentials.SystemCredentialsProvider.getInstance(); p.getCredentials().add(new org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl(com.cloudbees.plugins.credentials.CredentialsScope.GLOBAL, "s11", "et11", hudson.util.Secret.fromString("v"))); p.save()' >/dev/null
g $A 'Jenkins.get().addNode(new hudson.slaves.DumbSlave("n11", "/tmp/n11", new hudson.slaves.JNLPLauncher()))' >/dev/null
sleep 35 # past the report's cache
fa=$(findings $A); fb=$(findings $B)
info "4 A writes, unshared: ${fa:-nothing}"
info "4 B writes, unshared: ${fb:-nothing}"
check "4 A's home holds nothing the cluster does not share" test -z "$fa"
check "4 B's home holds nothing the cluster does not share" test -z "$fb"

# ------------------------------------------------------------------ 5. a deliberately bad write is reported
log "scenario 5: something writes unshared state under A's home"
g $A 'new hudson.XmlFile(new File(Jenkins.get().rootDir, "foo-bad/state.xml")).write([answer: 42])' >/dev/null
sleep 35
fa=$(findings $A)
info "5 A writes, unshared: ${fa:-nothing}"
check "5 the report names it (foo-bad/state.xml)" bash -c "[[ '$fa' == *'foo-bad/state.xml'* ]]"
check "5 and it is the only finding" bash -c "[[ \$(python3 -c \"print(len('''$fa'''.split('; ')))\") == 1 ]]"

et1 logs jenkins-a > "$OUT/jenkins-a.log" 2>&1; et1 logs jenkins-b > "$OUT/jenkins-b.log" 2>&1
cp "$ET1/BUILD" "$OUT/BUILD" 2>/dev/null || true
r ""
r "Failures: $FAILED"
echo "ET11 exit=$FAILED"
exit $FAILED
