#!/bin/bash
# ET19: acceptance experiment for Target 15 M1 (the plugin manager in a cluster): the install progress page through a
# round-robin load balancer, the cluster notice on the plugin manager's pages, CLI installs from a file applied to
# every controller (and refused for the store plugin), the progress page's single-controller restart refused, and
# executors and the shared queue on the cluster page.
# ET setup (two tmpfs controllers, S0, Postgres, Azurite, token auth) plus `rr`; needs updates.jenkins.io.
# Exits non-zero if any check fails.
#
# Prerequisite: CORE_REF=cnj/t15-remaining experiments/ET1-build.sh
source "$(dirname "$0")/../lib.sh"
A=8081 B=8082 RR=8090 OUT="$RAW_DIR/ET19" ET1="$VALIDATION_DIR/.et1"
et1() { docker compose --project-directory "$VALIDATION_DIR" -f "$VALIDATION_DIR/compose.et1.yaml" "$@"; }
rm -rf "$OUT"; mkdir -p "$OUT" "$OUT_DIR"
: > "$OUT/results.md"
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
FAILED=0
check() { local d=$1; shift; if "$@" >>"$OUT/check.log" 2>&1; then r "| ✅ | $d |"; else r "| ❌ | $d |"; FAILED=$((FAILED+1)); fi; }
info() { r "| ℹ️ | $* |"; }
eventually() { local t=$1 start=$SECONDS; shift; until "$@"; do (( SECONDS - start > t )) && return 1; sleep 2; done; }
json() { curl -gsf -u "$AUTH" "http://localhost:$1$2"; }
jq_() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
active_on() { [[ "$(json "$1" "/pluginManager/api/json?tree=plugins[shortName,active]" | jq_ "any(p['shortName'] == '$2' and p['active'] for p in d['plugins'])")" == True ]]; }

[[ -f "$ET1/war/jenkins.war" ]] || { echo "run ET1-build.sh first" >&2; exit 2; }

# ------------------------------------------------------------------ setup (as ET6, plus rr)
et1 down -v --remove-orphans >/dev/null 2>&1 || true
rm -rf "$ET1/seed"; mkdir -p "$ET1/seed"
docker rm -fv et1-seed >/dev/null 2>&1 || true
docker run -d --name et1-seed -p 8089:8080 --tmpfs /var/jenkins_home:uid=1000,gid=1000 \
  -e JAVA_OPTS="-Djenkins.install.runSetupWizard=false" -e CASC_JENKINS_CONFIG=/casc -v "$VALIDATION_DIR/jenkins/casc-et1:/casc:ro" \
  -v "$ET1/war:/war-override:ro" -v "$ET1/plugins:/extra-plugins:ro" cnj/jenkins:dev >/dev/null
wait_up 8089
groovy 8089 'hudson.util.Secret.fromString("x").getEncryptedValue(); org.jenkinsci.main.modules.instance_identity.InstanceIdentity.get()' >/dev/null
docker exec et1-seed tar -C /var/jenkins_home -cf - secret.key secret.key.not-so-secret identity.key.enc secrets | tar -C "$ET1/seed" -xf -
docker rm -fv et1-seed >/dev/null
et1 up -d postgres azurite >/dev/null 2>&1; wait_postgres
et1 up -d jenkins-a jenkins-b >/dev/null 2>&1
wait_up $A; wait_up $B
et1 up -d rr >/dev/null 2>&1
r "## ET19 results ($(date -u +%Y-%m-%dT%H:%MZ))"
r ""
r "| | Check |"
r "|---|---|"

# ------------------------------------------------------------------ 1. the notice on the plugin manager's pages
log "scenario 1: the cluster notice"
for port in $A $B; do
  for page in /manage/pluginManager/ /manage/pluginManager/available /manage/pluginManager/advanced /manage/pluginManager/updates/; do
    check "1 $page on $port says changes apply to every controller" bash -c "curl -gsf -u '$AUTH' 'http://localhost:$port$page' | grep -q 'apply to <strong>all of them</strong>'"
  done
done
check "1 the status fragment lists both controllers" eventually 30 bash -c "s=\$(curl -gsf -u '$AUTH' http://localhost:$A/manage/cluster/pluginStatus); [[ \$s == *jenkins-a* && \$s == *jenkins-b* && \$s == *'Up to date'* ]]"

# ------------------------------------------------------------------ 2. install progress through the round-robin LB
log "scenario 2: an install through the round-robin load balancer, and its progress page"
for port in $A $B; do curl -gs -u "$AUTH" -X POST -o /dev/null "http://localhost:$port/pluginManager/checkUpdatesServer"; done
# a small plugin whose required dependencies are all installed, so that it loads live
INSTALLED=$(json $A "/pluginManager/api/json?tree=plugins[shortName]" | jq_ "' '.join(p['shortName'] for p in d['plugins'])")
pick() { # first candidate not installed whose required dependencies are installed
  et1 exec -T jenkins-a cat /var/jenkins_home/updates/default.json | python3 -c "
import json, sys
uc = json.load(sys.stdin)['plugins']
installed = set(sys.argv[1].split())
for p in sys.argv[2:]:
    if p in uc and p not in installed and all(d.get('optional') or d['name'] in installed for d in uc[p].get('dependencies', [])):
        print(p); break
" "$INSTALLED" "$@"
}
P=$(pick next-build-number rebuild build-name-setter ansicolor description-setter timestamper extra-columns)
P2=$(INSTALLED="$INSTALLED $P" pick $(echo next-build-number rebuild build-name-setter ansicolor description-setter timestamper extra-columns | tr ' ' '\n' | grep -vx "$P"))
info "2 plugins chosen: $P (update center, through rr), $P2 (CLI, from a file)"
JAR="$OUT_DIR/et19-cookies"; rm -f "$JAR"
curl -gs -u "$AUTH" -c "$JAR" -b "$JAR" -D "$OUT/install.headers" -o /dev/null -X POST "http://localhost:$RR/manage/pluginManager/install" \
  --data "plugin.$P.default=on&dynamicLoad=Install"
INSTALLER=$(grep -i '^X-CNJ-Controller' "$OUT/install.headers" | tr -d '\r' | awk '{print $2}')
info "2 the install was served by $INSTALLER"
check "2 the install set the routing cookie" grep -q "CNJ-UC" "$JAR"
: > "$OUT/progress.txt"
for i in $(seq 1 12); do
  curl -gs -u "$AUTH" -c "$JAR" -b "$JAR" -D - -o "$OUT/progress-$i.html" -X POST "http://localhost:$RR/manage/pluginManager/updates/body" \
    | grep -i '^X-CNJ-Controller' | tr -d '\r' | awk '{print $2}' >> "$OUT/progress.txt"
done
info "2 progress polls answered by: $(sort "$OUT/progress.txt" | uniq -c | tr '\n' ' ')"
check "2 every progress poll through rr was answered by the installing controller" bash -c "[[ \$(sort -u '$OUT/progress.txt') == '$INSTALLER' ]]"
TITLE=$(et1 exec -T jenkins-a cat /var/jenkins_home/updates/default.json | python3 -c "import json,sys; print(json.load(sys.stdin)['plugins']['$P']['title'])")
check "2 ... and shows the install ($TITLE)" bash -c "for i in \$(seq 1 12); do grep -qF '$TITLE' '$OUT/progress-'\$i.html || exit 1; done"
on_both() { active_on $A "$1" && active_on $B "$1"; }
check "2 the plugin is active on both controllers" eventually 120 on_both "$P"
curl -gs -u "$AUTH" -c "$JAR" -b "$JAR" -o /dev/null -w '%{http_code}' -X POST "http://localhost:$RR/manage/pluginManager/updates/safeRestart" > "$OUT/restart.code"
check "2 the progress page's restart of one controller is refused (409)" bash -c "[[ \$(cat '$OUT/restart.code') == 409 ]]"

# ------------------------------------------------------------------ 3. CLI install-plugin from a file
log "scenario 3: install-plugin from a file on B"
curl -gsfL -o "$OUT_DIR/$P2.hpi" "https://updates.jenkins.io/latest/$P2.hpi" || { echo "cannot download $P2" >&2; exit 2; }
curl -gsf -o "$OUT_DIR/jenkins-cli.jar" "http://localhost:$B/jnlpJars/jenkins-cli.jar"
cli() { java -jar "$OUT_DIR/jenkins-cli.jar" -s "http://localhost:$1/" -auth "$AUTH" -http "${@:2}"; }
cli $B install-plugin = -deploy < "$OUT_DIR/$P2.hpi" > "$OUT/cli-install.txt" 2>&1 || true
check "3 the CLI install succeeded on B" active_on $B "$P2"
check "3 ... and A has it too, live" eventually 120 active_on $A "$P2"
rc=0; cli $A install-plugin = < "$ET1/plugins/persistence-store-postgresql.hpi" > "$OUT/cli-protected.txt" 2>&1 || rc=$?; echo "exit $rc" >> "$OUT/cli-protected.txt"
check "3 installing the store plugin with the CLI is refused" bash -c "grep -q 'cannot be changed at runtime' '$OUT/cli-protected.txt' && ! grep -q '^exit 0' '$OUT/cli-protected.txt'"

# ------------------------------------------------------------------ 4. executors and the queue on the cluster page
log "scenario 4: executors and the shared queue on the cluster page"
cat > "$OUT_DIR/sleeper.xml" <<'XML'
<?xml version='1.1' encoding='UTF-8'?>
<project><assignedNode>built-in</assignedNode><canRoam>false</canRoam><builders><hudson.tasks.Shell><command>sleep 30</command></hudson.tasks.Shell></builders></project>
XML
create_job $A sleeper "$OUT_DIR/sleeper.xml"
post $A "/job/sleeper/build?delay=0sec" >/dev/null
a_executors() { json $B "/manage/cluster/api/json?tree=members[name,busyExecutors,totalExecutors]" | jq_ "[m for m in d['members'] if m['name'] == 'jenkins-a'][0]['$1']"; }
a_busy() { [[ $(a_executors busyExecutors) -ge 1 ]]; }
check "4 B's cluster page shows A's executor busy" eventually 30 a_busy
a_total_is() { [[ $(a_executors totalExecutors) == "$1" ]]; }
check "4 ... out of A's 4" a_total_is 4
check "4 the page shows executors and the queue" bash -c "p=\$(curl -gsf -u '$AUTH' http://localhost:$B/manage/cluster/); [[ \$p == *' busy'* && \$p == *'Shared queue:'* ]]"

docker compose --project-directory "$VALIDATION_DIR" -f "$VALIDATION_DIR/compose.et1.yaml" logs jenkins-a > "$OUT/jenkins-a.log" 2>&1
docker compose --project-directory "$VALIDATION_DIR" -f "$VALIDATION_DIR/compose.et1.yaml" logs jenkins-b > "$OUT/jenkins-b.log" 2>&1
cp "$ET1/BUILD" "$OUT/BUILD" 2>/dev/null || true
r ""
r "Failures: $FAILED"
exit $(( FAILED > 0 ))
