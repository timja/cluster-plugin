#!/bin/bash
# E8: the short-term workaround for static state. Two controllers with SEPARATE homes, identical
# JCasC, and synced key material. Which "syncing the secret key" is enough?
#   variant M: only master.key, via -Djenkins.security.DefaultConfidentialStore.file (+ .readOnly)
#   variant S: a full key set (secret.key*, secrets/*, identity.key.enc) seeded from one controller
source "$(dirname "$0")/../lib.sh"
A=8081 B=8082 JOBS="$VALIDATION_DIR/scenarios/jobs" OUT="$RAW_DIR/E8"
rm -rf "$OUT"; mkdir -p "$OUT"
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
: > "$OUT/results.md"

checks() {
  local enc tok
  enc=$(groovy $A 'print(hudson.util.Secret.fromString("e8-value").getEncryptedValue())')
  r "| \`Secret\` encrypted on A decrypts on B | $(groovy $B "print(hudson.util.Secret.decrypt('$enc')?.plainText ?: 'NO (null)')") |"
  r "| agent1 inbound secret identical | $([[ "$(agent_secret $A agent1)" == "$(agent_secret $B agent1)" ]] && echo yes || echo NO) |"
  local ida idb
  ida=$(groovy $A 'print(org.jenkinsci.main.modules.instance_identity.InstanceIdentity.get().encodedPublicKey.hashCode())')
  idb=$(groovy $B 'print(org.jenkinsci.main.modules.instance_identity.InstanceIdentity.get().encodedPublicKey.hashCode())')
  r "| instance identity public key identical | $([[ "$ida" == "$idb" ]] && echo yes || echo NO) |"
  r "| legacy \`secret.key\` identical | $([[ "$(dc exec -T jenkins-a cat /var/jenkins_home/secret.key)" == "$(dc exec -T jenkins-b cat /var/jenkins_home/secret.key)" ]] && echo yes || echo NO) |"
  tok=$(groovy $A 'def u=hudson.model.User.getById("admin",false); def t=u.getProperty(jenkins.security.ApiTokenProperty).tokenStore.generateNewToken("e8"); u.save(); print(t.plainValue)')
  r "| API token created on A accepted by B | $(curl -s -o /dev/null -w '%{http_code}' -u "admin:$tok" "http://localhost:$B/whoAmI/api/json") |"
  local ha hb
  ha=$(dc exec -T jenkins-a sh -c 'cd /var/jenkins_home/secrets && sha256sum * | sort -k2')
  hb=$(dc exec -T jenkins-b sh -c 'cd /var/jenkins_home/secrets && sha256sum * | sort -k2')
  r "| \`secrets/*\` files differing between A and B | $(diff <(echo "$ha") <(echo "$hb") | grep '^<' | awk '{print $3}' | tr '\n' ' ') |"
}

# ---------------------------------------------------------------- variant M
reset_env; mkdir -p "$OUT_DIR/sync"
openssl rand -hex 128 > "$OUT_DIR/sync/master.key"
export CNJ_JAVA_OPTS="-Djenkins.security.DefaultConfidentialStore.file=/sync/master.key -Djenkins.security.DefaultConfidentialStore.readOnly=true"
dc --profile multi up -d postgres azurite jenkins-a jenkins-b >/dev/null 2>&1; wait_up $A; wait_up $B
r "## Variant M: shared master.key only (separate homes, identical JCasC)"
r "| Check | Result |"; r "|---|---|"
checks

# ---------------------------------------------------------------- variant S
reset_env; mkdir -p "$OUT_DIR/sync/seed/secrets"
unset CNJ_JAVA_OPTS
log "seed: boot one controller and exercise every ConfidentialKey we know of"
dc up -d postgres azurite jenkins-a >/dev/null 2>&1; wait_up $A
create_job $A freestyle "$JOBS/freestyle.xml"; build $A freestyle >/dev/null; wait_build $A freestyle 1 >/dev/null
agent_secret $A agent1 >/dev/null
groovy $A 'org.jenkinsci.main.modules.instance_identity.InstanceIdentity.get(); hudson.util.Secret.fromString("x").getEncryptedValue()' >/dev/null
c=$(dc ps -q jenkins-a)
for f in secret.key secret.key.not-so-secret identity.key.enc secrets; do docker cp "$c:/var/jenkins_home/$f" "$OUT_DIR/sync/seed/" ; done
r ""; r "Seed key set: \`$(ls "$OUT_DIR/sync/seed/secrets" | tr '\n' ' ')\`"
dc down -v >/dev/null 2>&1; docker volume rm -f validation_home-a >/dev/null 2>&1 || true
for v in home-a home-b; do
  docker run --rm --user root --entrypoint sh -v "validation_$v:/h" -v "$OUT_DIR/sync/seed:/s:ro" cnj/jenkins:dev \
    -c 'cp -a /s/. /h/ && chown -R 1000:1000 /h' >/dev/null
done
dc --profile multi up -d postgres azurite jenkins-a jenkins-b >/dev/null 2>&1; wait_up $A; wait_up $B
r ""; r "## Variant S: full key set seeded into both homes (separate homes, identical JCasC)"
r "| Check | Result |"; r "|---|---|"
checks
log "build on B, then check whether B generated new keys"
create_job $B freestyle "$JOBS/freestyle.xml"; build $B freestyle >/dev/null; wait_build $B freestyle 1 >/dev/null
r "| new keys B generated after a build | $(dc exec -T jenkins-b sh -c 'cd /var/jenkins_home/secrets && ls' | grep -vxF -f <(ls "$OUT_DIR/sync/seed/secrets") | tr '\n' ' ') |"
r "| console note (\`ConsoleNote.MAC\`) from B's log renders on A? | n/a: logs are not shared yet (MAC is synced, so it would) |"
dc logs --no-color jenkins-b > "$OUT/jenkins-b.log" 2>&1
