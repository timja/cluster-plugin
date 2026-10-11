#!/bin/bash
# ET13: acceptance experiment for Target 13 (cluster-wide plugin installs, upgrades and uninstalls), on kind.
# The chart with two controllers in its own namespace; plugins come from the update center (network) and an upload.
# Exits non-zero if any check fails.
#
# Prerequisites: CORE_REF=cnj/t12-m1-remainder experiments/ET1-build.sh; experiments/ET9-build-image.sh
source "$(dirname "$0")/../lib.sh"
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.fsmonitor GIT_CONFIG_VALUE_0=false
CLUSTER=${KIND_CLUSTER:-cnj} NS=cnj-plugins REL=plug OUT="$RAW_DIR/ET13" CHART="$ROOT_DIR/charts/cloud-native-jenkins"
P=build-timeout OLD=1.40 DEP=token-macro
rm -rf "$OUT"; mkdir -p "$OUT" "$OUT_DIR"
: > "$OUT/results.md"
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
FAILED=0
check() { local d=$1; shift; if "$@" >>"$OUT/check.log" 2>&1; then r "| ✅ | $d |"; else r "| ❌ | $d |"; FAILED=$((FAILED+1)); fi; }
info() { r "| ℹ️ | $* |"; }
eventually() { local t=$1 start=$SECONDS; shift; until "$@"; do (( SECONDS - start > t )) && return 1; sleep 3; done; }
k() { kubectl --context "kind-$CLUSTER" -n "$NS" "$@"; }
h() { helm --kube-context "kind-$CLUSTER" -n "$NS" "$@"; }
on() { local pod=$1 path=$2; shift 2; k exec "$pod" -c jenkins -- curl -gsf -m 30 -u "$AUTH" "http://localhost:8080$path" "$@"; }
code_on() { local pod=$1 path=$2; shift 2; k exec "$pod" -c jenkins -- curl -gs -m 30 -o /dev/null -w '%{http_code}' -u "$AUTH" "http://localhost:8080$path" "$@" 2>/dev/null; }
g_on() { on "$1" /scriptText -X POST --data-urlencode "script=$2" | tr -d '\r' | sed -e 's/[[:space:]]*$//'; }
ready() { [[ "$(k get pods -l "app.kubernetes.io/name=$REL-controller" -o jsonpath='{range .items[*]}{.status.containerStatuses[0].ready}{"\n"}{end}' | grep -c true)" == "$1" ]]; }
psql_q() { k exec "$REL-postgres-0" -- psql -U jenkins -At -c "$1"; }
# version of plugin $2 active on pod $1 ("-" if none)
version_on() { g_on "$1" "def p = Jenkins.get().pluginManager.getPlugin('$2'); println(p != null && p.isActive() ? p.version : '-')"; }
has_version() { [[ "$(version_on "$1" "$2")" == "$3" ]]; }
plugins_of() { g_on "$1" 'println Jenkins.get().pluginManager.plugins.findAll { it.isActive() }.collect { it.shortName + ":" + it.version }.sort().join(" ")'; }
has_plugin() { [[ "$(version_on "$1" "$2")" != - ]]; }
lacks_plugin() { [[ "$(g_on "$1" "def p = Jenkins.get().pluginManager.getPlugin('$2'); println(p != null ? p.version : '-')")" == - ]]; }
upgraded_on() { local v; v=$(version_on "$1" "$P"); [[ -n "$v" && "$v" != - && "$v" != "$OLD" ]]; }
upgraded_everywhere() { upgraded_on "$REL-0" && upgraded_on "$REL-1"; }
gone_everywhere() { lacks_plugin "$REL-0" "$P" && lacks_plugin "$REL-1" "$P"; }
neither_has_them() { for pod in "$REL-0" "$REL-1"; do lacks_plugin "$pod" "$P" && lacks_plugin "$pod" "$DEP" || return 1; done; }
dep_everywhere() { has_plugin "$REL-0" "$DEP" && has_plugin "$REL-1" "$DEP"; }
same_plugins() { [[ "$(plugins_of "$REL-0")" == "$(plugins_of "$REL-1")" ]]; }
descriptor_on() { [[ "$(g_on "$1" "println Jenkins.get().getDescriptor('$2') != null")" == true ]]; }
completed_somewhere() { for pod in "$REL-0" "$REL-1"; do on "$pod" /job/during/1/consoleText 2>/dev/null | grep -q survived && return 0; done; return 1; }
refused() { [[ "$(code_on "$REL-0" "$1" -X POST)" == 409 ]]; }
started_of() { psql_q "select started_at from jenkins_members where name = '$1' order by started_at desc limit 1"; }
pipeline_on() { # pod name script
  python3 - "$OUT_DIR/$2.xml" "$3" <<'PY'
import sys
from xml.sax.saxutils import escape
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job"><definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>%s</script><sandbox>true</sandbox></definition></flow-definition>""" % escape(sys.argv[2]))
PY
  k exec -i "$1" -c jenkins -- curl -gsf -u "$AUTH" -X POST "http://localhost:8080/createItem?name=$2" -H 'Content-Type: application/xml' --data-binary @- < "$OUT_DIR/$2.xml" >/dev/null
}
# probe <file>: every 2 s, "epoch up0 up1" (1 when the pod answers), until killed
probe() {
  while true; do
    a=$(code_on "$REL-0" /api/json || true); b=$(code_on "$REL-1" /api/json || true)
    echo "$(date +%s) $([[ $a == 200 ]] && echo 1 || echo 0) $([[ $b == 200 ]] && echo 1 || echo 0)" >> "$1"
    sleep 2
  done
}
never_both_down() { ! awk '$2 == 0 && $3 == 0' "$1" | grep -q .; }
both_were_down() { awk '$2 == 0' "$1" | grep -q . && awk '$3 == 0' "$1" | grep -q .; }
one_at_a_time() { # each controller's down period ends before the other's begins
  python3 - "$1" <<'PY'
import sys
rows = [list(map(int, l.split())) for l in open(sys.argv[1]) if l.strip()]
spans = {}
for t, a, b in rows:
    for i, up in ((0, a), (1, b)):
        if not up:
            s = spans.setdefault(i, [t, t]); s[1] = t
assert len(spans) == 2, spans
(a0, a1), (b0, b1) = spans[0], spans[1]
assert a1 < b0 or b1 < a0, spans
PY
}

kind get clusters 2>/dev/null | grep -qx "$CLUSTER" || { echo "run ET9 first (it creates the kind cluster)" >&2; exit 2; }
curl -sfL -o "$OUT_DIR/$P-$OLD.hpi" "https://updates.jenkins.io/download/plugins/$P/$OLD/$P.hpi" || { echo "cannot download $P $OLD" >&2; exit 2; }

k delete namespace "$NS" --ignore-not-found --wait=true >/dev/null 2>&1
kubectl --context "kind-$CLUSTER" create namespace "$NS" >/dev/null
"$CHART/scripts/make-seed-secret.sh" cnj/jenkins:k8s "$NS" >/dev/null || exit 3
h install "$REL" "$CHART" --set kubernetesCloud.enabled=false >/dev/null || exit 3
eventually 400 ready 2 || { echo "controllers did not come up" >&2; exit 3; }
until on "$REL-0" /api/json >/dev/null 2>&1 && on "$REL-1" /api/json >/dev/null 2>&1; do sleep 3; done
for pod in "$REL-0" "$REL-1"; do on "$pod" /pluginManager/checkUpdatesServer -X POST >/dev/null 2>&1 || true; done # update site metadata
r "## ET13 results ($(date -u +%Y-%m-%dT%H:%MZ))"
r ""
r "| | Check |"
r "|---|---|"
check "neither controller has $P or $DEP to begin with" neither_has_them

# ------------------------------------------------------------------ 1. a live install (an upload, with a dependency from the update center)
log "scenario 1: upload $P $OLD on $REL-0"
k cp "$OUT_DIR/$P-$OLD.hpi" "$REL-0:/tmp/$P.hpi" -c jenkins
s0=$(started_of "$REL-0"); s1=$(started_of "$REL-1")
on "$REL-0" /pluginManager/uploadPlugin -X POST -F "name=@/tmp/$P.hpi" -F "pluginUrl=" >/dev/null || true
check "1 $P $OLD is active on $REL-0" eventually 120 has_version "$REL-0" "$P" "$OLD"
check "1 ... and on $REL-1, live" eventually 120 has_version "$REL-1" "$P" "$OLD"
check "1 its dependency $DEP is active on both" eventually 60 dep_everywhere
check "1 neither controller restarted for it" bash -c "[[ '$(started_of "$REL-0")' == '$s0' && '$(started_of "$REL-1")' == '$s1' ]]"
check "1 its build wrapper's descriptor is there on $REL-1" descriptor_on "$REL-1" hudson.plugins.build_timeout.BuildTimeoutWrapper
info "1 cluster plugin set: $(psql_q "select convert_from(content, 'UTF8') from jenkins_store where key = 'cluster/plugins.json'" | python3 -c 'import json,sys; d=json.load(sys.stdin); print("generation", d["generation"], ", ".join(n + " " + e["state"] + " " + e.get("version", "") for n, e in d["plugins"].items()))')"

# ------------------------------------------------------------------ 2. an upgrade: a rolling restart
log "scenario 2: upgrade $P from the update center on $REL-1, with a Pipeline running on $REL-0"
pipeline_on "$REL-0" during 'for (int i = 1; i <= 40; i++) { echo "tick ${i}"; sleep 3 }; echo "survived"'
on "$REL-0" "/job/during/build?delay=0sec" -X POST >/dev/null
sleep 10
: > "$OUT/probe-upgrade.txt"; probe "$OUT/probe-upgrade.txt" & PROBE=$!
on "$REL-1" /pluginManager/installPlugins -X POST -H 'Content-Type: application/json' --data "{\"dynamicLoad\":true,\"plugins\":[\"$P\"]}" >/dev/null || true
eventually 600 upgraded_everywhere || true
sleep 20; kill $PROBE 2>/dev/null || true; wait $PROBE 2>/dev/null || true
NEW=$(version_on "$REL-0" "$P")
check "2 both controllers run the upgraded $P ($NEW)" bash -c "[[ '$NEW' != '$OLD' && '$NEW' != - && '$(version_on "$REL-1" "$P")' == '$NEW' ]]"
check "2 both restarted for it" both_were_down "$OUT/probe-upgrade.txt"
check "2 one at a time: a controller answered throughout" never_both_down "$OUT/probe-upgrade.txt"
check "2 one at a time: the second went down only after the first was back" one_at_a_time "$OUT/probe-upgrade.txt"
check "2 the Pipeline running meanwhile completed, with its whole console" eventually 300 completed_somewhere
for pod in "$REL-0" "$REL-1"; do
  { echo "== $pod build"; on "$pod" "/job/during/1/api/json?tree=result,building" || true; echo; echo "== $pod console"; on "$pod" /job/during/1/consoleText || true; } > "$OUT/during-$pod.txt" 2>&1
  k logs "$pod" -c jenkins --previous > "$OUT/$pod-before-restart.log" 2>&1 || true
  k logs "$pod" -c jenkins > "$OUT/$pod-after-upgrade.log" 2>&1 || true
done
check "2 it paused for the restart and resumed: every tick once, in order" bash -c "grep -q 'Pausing (Preparing for shutdown)' '$OUT/during-$REL-0.txt' && [[ \$(grep '^tick ' '$OUT/during-$REL-0.txt' | tr -d '\r' | tr '\n' ' ') == '$(seq -f 'tick %g' 1 40 | tr '\n' ' ')' ]]"
check "2 the restart lock was released" bash -c "[[ -z '$(psql_q "select key from jenkins_store where key = 'cluster/plugins.restart'")' ]]"
info "2 members: $(psql_q "select name || ' ' || to_char(started_at, 'HH24:MI:SS') || ' ' || coalesce(plugins, '') from jenkins_members where heartbeat_at > now() - interval '30 seconds' order by name" | tr '\n' ';')"

# ------------------------------------------------------------------ 3. a fresh controller (empty home)
log "scenario 3: replace $REL-1 with a fresh pod"
k delete pod "$REL-1" --wait=true >/dev/null
eventually 400 ready 2 || true
until on "$REL-1" /api/json >/dev/null 2>&1; do sleep 3; done
check "3 the fresh $REL-1 has $P $NEW" eventually 240 has_version "$REL-1" "$P" "$NEW"
check "3 and the same plugins as $REL-0" eventually 120 same_plugins
plugins_of "$REL-0" > "$OUT/plugins-$REL-0.txt"; plugins_of "$REL-1" > "$OUT/plugins-$REL-1.txt"

# ------------------------------------------------------------------ 4. an uninstall
log "scenario 4: uninstall $P on $REL-0"
: > "$OUT/probe-uninstall.txt"; probe "$OUT/probe-uninstall.txt" & PROBE=$!
on "$REL-0" "/manage/pluginManager/plugin/$P/doUninstall" -X POST >/dev/null || true
check "4 $P is gone from both controllers" eventually 600 gone_everywhere
sleep 20; kill $PROBE 2>/dev/null || true; wait $PROBE 2>/dev/null || true
check "4 one at a time again: a controller answered throughout" never_both_down "$OUT/probe-uninstall.txt"

# ------------------------------------------------------------------ 5. guards
log "scenario 5: guards"
check "5 a downgrade is refused" refused /updateCenter/downgrade
check "5 changing the store plugin is refused" refused /manage/pluginManager/plugin/persistence-store-postgresql/makeDisabled
# a corrupt entry: an archive whose checksum does not match is never installed, and causes no restart
s0=$(started_of "$REL-0"); s1=$(started_of "$REL-1")
blob=$(psql_q "select convert_from(content, 'UTF8') from jenkins_store where key = 'cluster/plugins.json'" | python3 -c "import json,sys; print(json.load(sys.stdin)['plugins']['$DEP']['blob'])")
g_on "$REL-0" "def s = jenkins.persistence.PersistenceStores.get(); def e = s.read('cluster/plugins.json'); def o = net.sf.json.JSONObject.fromObject(new String(e.content(), 'UTF-8')); o.plugins.put('cnj-broken', [state: 'installed', version: '1.0', sha256: '00', blob: '$blob', by: 'et13', at: 0]); o.generation = o.generation + 1; s.write('cluster/plugins.json', o.toString(2).getBytes('UTF-8'), e.version()); println 'ok'" >/dev/null
sleep 90
check "5 a corrupt archive is not installed" bash -c "[[ '$(version_on "$REL-1" cnj-broken)' == - ]]"
check "5 and causes no restart" bash -c "[[ '$(started_of "$REL-0")' == '$s0' && '$(started_of "$REL-1")' == '$s1' ]]"
check "5 and is logged" bash -c "kubectl --context kind-$CLUSTER -n $NS logs $REL-1 -c jenkins | grep -q 'cnj-broken'"

k logs "$REL-0" -c jenkins > "$OUT/$REL-0.log" 2>&1; k logs "$REL-1" -c jenkins > "$OUT/$REL-1.log" 2>&1
psql_q "select convert_from(content, 'UTF8') from jenkins_store where key = 'cluster/plugins.json'" > "$OUT/cluster-plugins.json" 2>&1
cp "$VALIDATION_DIR/.et1/BUILD" "$OUT/BUILD" 2>/dev/null || true
k delete namespace "$NS" --wait=false >/dev/null 2>&1
r ""
r "Failures: $FAILED"
echo "ET13 exit=$FAILED"
exit $FAILED
