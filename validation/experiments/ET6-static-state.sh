#!/bin/bash
# ET6: acceptance experiment for Target 6 (static state in the store, stateless browser sessions).
# ET setup (two tmpfs controllers, S0 seed of pre-plugin keys, Postgres, Azurite, token auth) plus `rr`,
# a round-robin load balancer which does not pin browsers. Exits non-zero if any check fails.
#
# Prerequisite: CORE_REF=cnj/t6-m6-stateless-sessions experiments/ET1-build.sh
source "$(dirname "$0")/../lib.sh"
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.fsmonitor GIT_CONFIG_VALUE_0=false
A=8081 B=8082 RR=8090 OUT="$RAW_DIR/ET6" ET1="$VALIDATION_DIR/.et1"
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
psql_q() { et1 exec -T postgres psql -U jenkins -At -c "$1"; }
g() { groovy "$1" "$2" | tr -d '\r' | sed -e 's/[[:space:]]*$//'; }   # script console output, trimmed
g_is() { [[ "$(g "$1" "$2")" == "$3" ]]; }
as_user() { curl -gs -o /dev/null -w '%{http_code}' -u "$2" "http://localhost:$1/whoAmI/api/json"; }
status_is() { [[ "$(as_user "$1" "$2")" == "$3" ]]; }
create_both() { create_job $A "$1" "$OUT_DIR/$1.xml"; eventually 15 bash -c "[[ \$(curl -gs -o /dev/null -w '%{http_code}' -u '$AUTH' http://localhost:$B/job/$1/api/json) == 200 ]]"; }

[[ -f "$ET1/war/jenkins.war" && -f "$ET1/plugins/credentials.hpi" ]] || { echo "run ET1-build.sh first (with the credentials and Stapler worktrees)" >&2; exit 2; }

# ------------------------------------------------------------------ setup: S0 seeds only pre-plugin keys
et1 down -v --remove-orphans >/dev/null 2>&1 || true
rm -rf "$ET1/seed" "$ET1/repo" "$ET1/casc-et6"; mkdir -p "$ET1/seed" "$ET1/repo" "$ET1/casc-et6"
# the controllers' JCasC declares no nodes (its `jenkins.nodes` list is replace-all): base.yaml only
cp "$VALIDATION_DIR/jenkins/casc-et1/base.yaml" "$ET1/casc-et6/"
export CNJ_CASC_DIR="$ET1/casc-et6"
docker rm -f et1-seed >/dev/null 2>&1 || true
docker run -d --name et1-seed -p 8089:8080 --tmpfs /var/jenkins_home:uid=1000,gid=1000 \
  -e JAVA_OPTS="-Djenkins.install.runSetupWizard=false" -e CASC_JENKINS_CONFIG=/casc -v "$VALIDATION_DIR/jenkins/casc-et1:/casc:ro" \
  -v "$ET1/war:/war-override:ro" -v "$ET1/plugins:/extra-plugins:ro" cnj/jenkins:dev >/dev/null
wait_up 8089
groovy 8089 'hudson.util.Secret.fromString("x").getEncryptedValue(); org.jenkinsci.main.modules.instance_identity.InstanceIdentity.get()' >/dev/null
docker exec et1-seed tar -C /var/jenkins_home -cf - secret.key secret.key.not-so-secret identity.key.enc secrets | tar -C "$ET1/seed" -xf -
docker rm -f et1-seed >/dev/null
info "seeded keys: $(ls "$ET1/seed/secrets" | tr '\n' ' ')"
et1 up -d postgres azurite >/dev/null 2>&1; sleep 3
et1 up -d jenkins-a jenkins-b >/dev/null 2>&1
wait_up $A; wait_up $B
et1 up -d rr >/dev/null 2>&1
r "## ET6 results ($(date -u +%Y-%m-%dT%H:%MZ))"
r ""
r "| | Check |"
r "|---|---|"
check "both controllers use the cluster crumb issuer" bash -c "[[ \$(cd $VALIDATION_DIR && source lib.sh && groovy $A 'println Jenkins.get().crumbIssuer.class.simpleName') == ClusterCrumbIssuer* ]]"

# ------------------------------------------------------------------ 1. API token
log "scenario 1: API token created on A"
g $A 'Jenkins.get().securityRealm.createAccount("u1", "u1-password-1")' >/dev/null
eventually 15 g_is $B 'println hudson.model.User.getById("u1", false) != null' true
# as u1 itself (an administrator cannot create another user's tokens), with a password and the cluster crumb
JAR="$OUT/u1.cookies"
u1_post() {
  local crumb
  rm -f "$JAR"
  crumb=$(curl -gsf -c "$JAR" -u u1:u1-password-1 "http://localhost:$A/crumbIssuer/api/json" | jq_ 'd["crumb"]')
  grep -v 'remember-me' "$JAR" > "$JAR.nonce" || true # the crumb is bound to the nonce cookie: send only that
  curl -gsf -b "$JAR.nonce" -u u1:u1-password-1 -H "Jenkins-Crumb: $crumb" -X POST "http://localhost:$A$1"
}
tok=$(u1_post "/user/u1/descriptorByName/jenkins.security.ApiTokenProperty/generateNewToken?newTokenName=et6" | jq_ 'd["data"]["tokenValue"] + " " + d["data"]["tokenUuid"]')
token=${tok% *}; uuid=${tok#* }
check "1 the token works on B" eventually 15 status_is $B "u1:$token" 200
rr_ok=0; for i in $(seq 1 10); do [[ $(as_user $RR "u1:$token") == 200 ]] && rr_ok=$((rr_ok+1)); done
check "1 the token works through the round-robin LB ($rr_ok/10)" test $rr_ok = 10
u1_post "/user/u1/descriptorByName/jenkins.security.ApiTokenProperty/revoke?tokenUuid=$uuid" >/dev/null
check "1 revoked on A, it is refused on B" eventually 15 status_is $B "u1:$token" 401

# ------------------------------------------------------------------ 2. users and passwords
log "scenario 2: user created on A, password changed on A"
g $A 'Jenkins.get().securityRealm.createAccount("u2", "u2-password-1")' >/dev/null
check "2 the user logs in on B" eventually 15 status_is $B "u2:u2-password-1" 200
g $A 'hudson.model.User.getById("u2", false).addProperty(hudson.security.HudsonPrivateSecurityRealm.Details.fromPlainPassword("u2-password-2"))' >/dev/null
check "2 the old password fails on B" eventually 15 status_is $B "u2:u2-password-1" 401
check "2 the new password works on B" status_is $B "u2:u2-password-2" 200

# ------------------------------------------------------------------ 3. global configuration
log "scenario 3: global settings changed on A at runtime"
g $A 'Jenkins.get().setQuietPeriod(7); def l = jenkins.model.JenkinsLocationConfiguration.get(); l.setAdminAddress("ops@example.com"); l.save()' >/dev/null
check "3 quiet period visible on B without a restart" eventually 5 g_is $B 'println Jenkins.get().quietPeriod' 7
check "3 admin address (a descriptor) visible on B" eventually 5 g_is $B 'println jenkins.model.JenkinsLocationConfiguration.get().adminAddress' ops@example.com
g $B 'Jenkins.get().setScmCheckoutRetryCount(3)' >/dev/null
check "3 B's next save does not revert A's change" eventually 5 g_is $A 'println Jenkins.get().quietPeriod + "/" + Jenkins.get().scmCheckoutRetryCount' 7/3

# ------------------------------------------------------------------ 4. system credential
log "scenario 4: secret text credential created on A"
g $A 'def p = com.cloudbees.plugins.credentials.SystemCredentialsProvider.getInstance(); p.getCredentials().add(new org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl(com.cloudbees.plugins.credentials.CredentialsScope.GLOBAL, "s1", "et6", hudson.util.Secret.fromString("top-secret"))); p.save()' >/dev/null
check "4 listed on B" eventually 10 g_is $B 'println com.cloudbees.plugins.credentials.SystemCredentialsProvider.getInstance().getCredentials().collect { it.id }.contains("s1")' true
python3 - "$OUT_DIR/creds.xml" <<'PY'
import sys
from xml.sax.saxutils import escape
script = 'withCredentials([string(credentialsId: "s1", variable: "S")]) { echo "secret length ${S.length()}" }'
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job"><definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>%s</script><sandbox>true</sandbox></definition></flow-definition>""" % escape(script))
PY
create_job $B creds "$OUT_DIR/creds.xml"
post $B "/job/creds/build?delay=0sec" >/dev/null
check "4 a build on B reads its value" eventually 60 bash -c "cd $VALIDATION_DIR && source lib.sh && curl -gsf -u \"\$AUTH\" http://localhost:$B/job/creds/1/consoleText | grep -q 'secret length 10'"

# ------------------------------------------------------------------ 5. node
log "scenario 5: permanent agent added and deleted on A"
g $A 'Jenkins.get().addNode(new hudson.slaves.DumbSlave("n6", "/tmp/n6", new hudson.slaves.JNLPLauncher()))' >/dev/null
check "5 the node appears on B" eventually 10 g_is $B 'println Jenkins.get().getNode("n6") != null && Jenkins.get().getComputer("n6") != null' true
secret_a=$(agent_secret $A n6); secret_b=$(agent_secret $B n6)
check "5 its agent secret is the same on A and B" test -n "$secret_a" -a "$secret_a" = "$secret_b"
docker rm -f et6-n6 >/dev/null 2>&1 || true
docker run -d --name et6-n6 --network et1_default jenkins/inbound-agent:latest-jdk21 -url http://jenkins-b:8080/ -secret "$secret_b" -name n6 -webSocket -workDir /home/jenkins/agent >/dev/null
check "5 the agent connects through B" eventually 60 g_is $B 'println Jenkins.get().getComputer("n6")?.isOnline()' true
g $A 'Jenkins.get().removeNode(Jenkins.get().getNode("n6"))' >/dev/null
check "5 deleted on A, it is gone on B" eventually 10 g_is $B 'println Jenkins.get().getNode("n6") == null' true
check "5 and B dropped its connection" eventually 30 bash -c "docker logs et6-n6 2>&1 | grep -qiE 'terminated|closed|disconnected|reconnect'"
docker rm -f et6-n6 >/dev/null 2>&1 || true

# ------------------------------------------------------------------ 6. keys created at runtime
log "scenario 6: a confidential key first used at runtime"
mac_a=$(g $A 'println new jenkins.security.HMACConfidentialKey("et6.runtime.key").mac("x")')
mac_b=$(g $B 'println new jenkins.security.HMACConfidentialKey("et6.runtime.key").mac("x")')
check "6 the key has the same value on B" test -n "$mac_a" -a "$mac_a" = "$mac_b"
enc=$(g $A 'println new jenkins.security.CryptoConfidentialKey("et6.runtime.cipher").encrypt().doFinal("hello".bytes).encodeBase64()')
dec=$(g $B "println new String(new jenkins.security.CryptoConfidentialKey('et6.runtime.cipher').decrypt().doFinal('$enc'.decodeBase64()))")
check "6 data encrypted with a runtime key on A decrypts on B" test "$dec" = hello

# ------------------------------------------------------------------ 9. JCasC re-applied on boot
# JCasC wins for what it declares: settings it does not declare (quiet period, users, nodes) keep their runtime values,
# and what it declares is rewritten only if different. (Its `jenkins.nodes` list is replace-all, so it declares none here.)
log "scenario 9: restarting B re-applies JCasC"
g $A 'Jenkins.get().securityRealm.createAccount("u9", "u9-password-1")' >/dev/null
g $A 'Jenkins.get().addNode(new hudson.slaves.DumbSlave("n7", "/tmp/n7", new hudson.slaves.JNLPLauncher()))' >/dev/null
sleep 2
versions() { psql_q "select key || '@' || version from jenkins_store where key in ('config.xml', 'jenkins.model.JenkinsLocationConfiguration.xml', 'nodes/n7/config.xml') or key like 'users/u9%' order by key" | tr '\n' ' '; }
before=$(versions)
et1 restart jenkins-b >/dev/null 2>&1; wait_up $B
after=$(versions)
info "9 versions before: $before; after: $after"
check "9 B's boot (JCasC) bumped no versions of unchanged config" test "$before" = "$after"
check "9 a setting JCasC does not declare keeps its runtime value" g_is $B 'println Jenkins.get().quietPeriod' 7
check "9 a user created at runtime survives B's boot" status_is $B "u9:u9-password-1" 200
check "9 JCasC's declared setting is still JCasC's" g_is $B 'println Jenkins.get().systemMessage' "cloud-native-jenkins ET1"
check "9 the node added in the UI survives B's boot" g_is $B 'println Jenkins.get().getNode("n7") != null' true

# ------------------------------------------------------------------ 10. concurrent edits
log "scenario 10: A and B save different settings at once"
g $A 'Jenkins.get().setQuietPeriod(9)' >/dev/null & g $B 'Jenkins.get().setScmCheckoutRetryCount(4)' >/dev/null & wait
sleep 3
final=$(g $A 'println Jenkins.get().quietPeriod + "/" + Jenkins.get().scmCheckoutRetryCount')
conflicts=$(( $(g $A 'println jenkins.persistence.PersistenceStores.getConflictCount()') + $(g $B 'println jenkins.persistence.PersistenceStores.getConflictCount()') ))
info "10 result $final, conflicting writes detected: $conflicts"
check "10 both changes survive, or the overwrite was detected" bash -c "[[ '$final' == 9/4 || $conflicts -gt 0 ]]"
check "10 A and B agree on the result" eventually 5 bash -c "[[ \$(cd $VALIDATION_DIR && source lib.sh && groovy $B 'println Jenkins.get().quietPeriod + \"/\" + Jenkins.get().scmCheckoutRetryCount' | tr -d '\r' | sed 's/[[:space:]]*\$//') == \$(cd $VALIDATION_DIR && source lib.sh && groovy $A 'println Jenkins.get().quietPeriod + \"/\" + Jenkins.get().scmCheckoutRetryCount' | tr -d '\r' | sed 's/[[:space:]]*\$//') ]]"

# ------------------------------------------------------------------ 8. stateless browser journey through the round-robin LB
log "scenario 8: a browser through the round-robin LB, then losing A"
g $A 'Jenkins.get().securityRealm.createAccount("u3", "u3-password-1")' >/dev/null
python3 - "$OUT_DIR/fs6.xml" <<'PY'
import sys
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<project><builders><hudson.tasks.Shell><command>echo journey</command></hudson.tasks.Shell></builders></project>""")
PY
create_both fs6
post $A "/job/fs6/build?delay=0sec" >/dev/null; sleep 8; post $B "/job/fs6/build?delay=0sec" >/dev/null; sleep 8
(cd "$VALIDATION_DIR/journey" && mvn -B -ntp -q compile exec:java -Dexec.args="http://localhost:$RR/ u3 u3-password-1 u3-password-2 fs6") > "$OUT/journey.txt" 2>&1 || true
fwd=$(( $(g $A 'println io.jenkins.plugins.cnj.store.BoundRequestForwarder.getForwardedCount()') + $(g $B 'println io.jenkins.plugins.cnj.store.BoundRequestForwarder.getForwardedCount()') ))
while read -r kind ok rest; do
  case "$kind" in
    RESULT) check "8 $rest" test "$ok" = ok ;;
    INFO) info "8 $ok $rest" ;;
  esac
done < <(grep -E '^(RESULT|INFO) ' "$OUT/journey.txt")
info "8 bound-object requests forwarded between controllers: $fwd"
check "8 some bound-object requests were forwarded" test "$fwd" -gt 0
# the same journey's browser, after A dies (fresh password change: back to the first one)
(cd "$VALIDATION_DIR/journey" && mvn -B -ntp -q exec:java -Dexec.args="http://localhost:$RR/ u3 u3-password-2 u3-password-1 fs6 'docker kill -s KILL et1-jenkins-a-1'") > "$OUT/journey-failover.txt" 2>&1 || true
check "8 after losing A the browser is still signed in" grep -q '^RESULT ok still signed in after losing a controller' "$OUT/journey-failover.txt"
et1 up -d jenkins-a >/dev/null 2>&1; wait_up $A

# ------------------------------------------------------------------ 7. disposable cluster
log "scenario 7: destroy both controllers, start fresh ones"
et1 rm -sf jenkins-a jenkins-b >/dev/null 2>&1
et1 up -d jenkins-a jenkins-b >/dev/null 2>&1; wait_up $A; wait_up $B
check "7 users and passwords survive (u2 new password on B)" status_is $B "u2:u2-password-2" 200
check "7 global settings survive (A)" g_is $A 'println Jenkins.get().quietPeriod' "$(echo "$final" | cut -d/ -f1)"
check "7 the descriptor setting survives (B)" g_is $B 'println jenkins.model.JenkinsLocationConfiguration.get().adminAddress' ops@example.com
check "7 the credential survives (B)" g_is $B 'println com.cloudbees.plugins.credentials.SystemCredentialsProvider.getInstance().getCredentials().collect { it.id }.contains("s1")' true
check "7 the UI-added node survives, the deleted one stays deleted" g_is $A 'println (Jenkins.get().getNode("n7") != null && Jenkins.get().getNode("n6") == null)' true
check "7 the runtime key survives" g_is $B 'println new jenkins.security.HMACConfidentialKey("et6.runtime.key").mac("x")' "$mac_a"

et1 logs jenkins-a > "$OUT/jenkins-a.log" 2>&1; et1 logs jenkins-b > "$OUT/jenkins-b.log" 2>&1
psql_q "select key, version, length(content) from jenkins_store where key not like 'jobs/%' order by key" > "$OUT/static-keys.txt"
cp "$ET1/BUILD" "$OUT/BUILD" 2>/dev/null || true
r ""
r "Failures: $FAILED"
echo "ET6 exit=$FAILED"
exit $FAILED
