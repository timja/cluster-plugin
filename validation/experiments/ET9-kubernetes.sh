#!/bin/bash
# ET9: acceptance experiment for Target 9 (the helm chart on kind; Kubernetes agents across controllers).
# A kind cluster `cnj` (kindest/node:v1.37.0, no image pulls: everything is loaded with `kind load`), the chart with
# 2 controllers, in-chart Postgres and Azurite, and the kubernetes cloud with a container cap of 4.
# The Service is a NodePort mapped to localhost:8095. Exits non-zero if any check fails.
#
# Prerequisites: CORE_REF=cnj/t10-m1-import-api experiments/ET1-build.sh (the top of the core stack, with the T11 merge:
# controllers booting at once all write the shared admin user); experiments/ET9-build-image.sh
source "$(dirname "$0")/../lib.sh"
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.fsmonitor GIT_CONFIG_VALUE_0=false
CLUSTER=${KIND_CLUSTER:-cnj} NS=cnj REL=cnj SVC=8095 OUT="$RAW_DIR/ET9" CHART="$ROOT_DIR/charts/cloud-native-jenkins"
rm -rf "$OUT"; mkdir -p "$OUT" "$OUT_DIR"
: > "$OUT/results.md"
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
FAILED=0
check() { local d=$1; shift; if "$@" >>"$OUT/check.log" 2>&1; then r "| ✅ | $d |"; else r "| ❌ | $d |"; FAILED=$((FAILED+1)); fi; }
info() { r "| ℹ️ | $* |"; }
eventually() { local t=$1 start=$SECONDS; shift; until "$@"; do (( SECONDS - start > t )) && return 1; sleep 2; done; }
k() { kubectl --context "kind-$CLUSTER" -n "$NS" "$@"; }
jq_() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
svc() { curl -gsf -u "$AUTH" "http://localhost:$SVC$1"; }                       # through the Service
svc_post() { curl -gsf -u "$AUTH" -X POST "http://localhost:$SVC$1" "${@:2}"; }
on() { local pod=$1 path=$2; shift 2; k exec "$pod" -c jenkins -- curl -gsf -u "$AUTH" "http://localhost:8080$path" "$@"; }  # one controller
g_on() { on "$1" /scriptText -X POST --data-urlencode "script=$2" | tr -d '\r' | sed -e 's/[[:space:]]*$//'; }
psql_q() { k exec "$REL-postgres-0" -- psql -U jenkins -At -c "$1"; }
controllers() { k get pods -l "app.kubernetes.io/name=$REL-controller" -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'; }
ready_count() { k get pods -l "app.kubernetes.io/name=$REL-controller" -o jsonpath='{range .items[*]}{.status.containerStatuses[0].ready}{"\n"}{end}' | grep -c true; }
alive_members() { svc /manage/cluster/api/json?depth=1 | jq_ '",".join(sorted(m["name"] for m in d["members"] if m["alive"]))'; }
# which controller wrote a completed build's record (the store's events, by origin → member name)
ran_on() { psql_q "select m.name from jenkins_store_events e join jenkins_members m on m.origin = e.origin where e.key = 'jobs/$1/builds/$2/build.xml' and e.kind = 'WRITTEN' order by e.seq desc limit 1"; }
result_is() { [[ "$(svc "/job/$1/$2/api/json?tree=result" 2>/dev/null | jq_ 'd["result"]')" == "$3" ]]; }
pipeline() { # name script [string parameter]
  python3 - "$OUT_DIR/$1.xml" "$2" "${3:-}" <<'PY'
import sys
from xml.sax.saxutils import escape
param = sys.argv[3]
props = ("<properties><hudson.model.ParametersDefinitionProperty><parameterDefinitions><hudson.model.StringParameterDefinition>"
         "<name>%s</name><defaultValue>0</defaultValue></hudson.model.StringParameterDefinition></parameterDefinitions>"
         "</hudson.model.ParametersDefinitionProperty></properties>" % param) if param else "<properties/>"
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job">""" + props + """
<definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>%s</script><sandbox>true</sandbox></definition></flow-definition>""" % escape(sys.argv[2]))
PY
  svc_post "/createItem?name=$1" -H 'Content-Type: application/xml' --data-binary "@$OUT_DIR/$1.xml" >/dev/null
}

command -v kind >/dev/null && command -v helm >/dev/null || { echo "kind and helm are needed" >&2; exit 2; }
docker image inspect cnj/jenkins:k8s >/dev/null 2>&1 || { echo "run ET9-build-image.sh first" >&2; exit 2; }

# ------------------------------------------------------------------ setup: a fresh namespace in the kind cluster
if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  cat > "$OUT_DIR/kind.yaml" <<EOF
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    image: kindest/node:v1.37.0
    extraPortMappings: [{containerPort: 30080, hostPort: $SVC}]
EOF
  kind create cluster --name "$CLUSTER" --config "$OUT_DIR/kind.yaml" --wait 120s >/dev/null || exit 3
  "$VALIDATION_DIR/experiments/ET9-build-image.sh" >/dev/null || exit 3
fi
claim_ns "kind-$CLUSTER" "$NS" || exit 2
"$CHART/scripts/make-seed-secret.sh" cnj/jenkins:k8s "$NS" >/dev/null || exit 3
helm --kube-context "kind-$CLUSTER" -n "$NS" install "$REL" "$CHART" --set service.type=NodePort --set service.nodePort=30080 >/dev/null || exit 3
r "## ET9 results ($(date -u +%Y-%m-%dT%H:%MZ))"
r ""
r "| | Check |"
r "|---|---|"

# ------------------------------------------------------------------ 1. install
log "scenario 1: install"
check "1 both controllers become ready" eventually 400 bash -c "[[ \$(kubectl --context kind-$CLUSTER -n $NS get pods -l app.kubernetes.io/name=$REL-controller -o jsonpath='{range .items[*]}{.status.containerStatuses[0].ready}{\"\\n\"}{end}' | grep -c true) == 2 ]]"
check "1 both are live members (cluster view through the Service)" eventually 60 bash -c "[[ \$(curl -gsf -u '$AUTH' 'http://localhost:$SVC/manage/cluster/api/json?depth=1' | python3 -c 'import json,sys; d=json.load(sys.stdin); print(\",\".join(sorted(m[\"name\"] for m in d[\"members\"] if m[\"alive\"])))') == $REL-0,$REL-1 ]]"

# ------------------------------------------------------------------ 2. one Service
log "scenario 2: builds and a browser through the Service"
pipeline flyweight 'echo "flyweight"'
for i in $(seq 1 10); do svc_post "/job/flyweight/build?delay=0sec" >/dev/null; sleep 0.3; done
eventually 120 result_is flyweight 10 SUCCESS
for i in $(seq 1 10); do eventually 60 result_is flyweight "$i" SUCCESS; done
spread=$(for i in $(seq 1 10); do ran_on flyweight "$i"; done | sort | uniq -c | awk '{print $2 "=" $1}' | tr '\n' ' ')
info "2 10 builds through the Service ran on: $spread"
check "2 builds through the Service ran on both controllers" test "$(wc -w <<<"$spread" | tr -d ' ')" = 2
g_on "$REL-0" 'Jenkins.get().securityRealm.createAccount("u9", "u9-password-1")' >/dev/null
# the journey's job: freestyle (HtmlUnit cannot submit a Pipeline's configure form), built once, on a Kubernetes agent
cat > "$OUT_DIR/fs9.xml" <<'XML'
<?xml version='1.1' encoding='UTF-8'?>
<project><assignedNode>k8s</assignedNode><canRoam>false</canRoam><builders><hudson.tasks.Shell><command>echo journey</command></hudson.tasks.Shell></builders></project>
XML
svc_post "/createItem?name=fs9" -H 'Content-Type: application/xml' --data-binary "@$OUT_DIR/fs9.xml" >/dev/null
svc_post "/job/fs9/build?delay=0sec" >/dev/null
check "2 a freestyle build on a Kubernetes agent completes" eventually 300 result_is fs9 1 SUCCESS
(cd "$VALIDATION_DIR/journey" && mvn -B -ntp -q compile exec:java -Dexec.args="http://localhost:$SVC/ u9 u9-password-1 u9-password-2 fs9") > "$OUT/journey.txt" 2>&1 || true
while read -r kind ok rest; do
  case "$kind" in
    RESULT) if [[ "$rest" == "requests reached both controllers"* ]]; then info "2 journey: $ok $rest (kube-proxy balances connections, and HtmlUnit keeps them alive)"; else check "2 journey: $rest" test "$ok" = ok; fi ;;
    INFO) info "2 journey: $ok $rest" ;;
  esac
done < <(grep -E '^(RESULT|INFO) ' "$OUT/journey.txt")

# ------------------------------------------------------------------ 3. Kubernetes agents, cap 4, 6 builds at once
log "scenario 3: 6 Pipelines on Kubernetes agents at once, container cap 4"
pipeline kagent 'node("k8s") { sh '"'"'echo on-pod-$(hostname); sleep 25'"'"' }' N
sampler() { while [[ ! -f "$OUT/stop-sampling" ]]; do
  k get pods -l jenkins/label=k8s -o jsonpath='{range .items[*]}{.metadata.name} {.metadata.labels.kubernetes\.jenkins\.io/controller} {.status.phase}{"\n"}{end}' 2>/dev/null \
    | awk -v t="$(date +%s)" 'NF {print t, $0}' >> "$OUT/pods.txt"; sleep 2; done; }
rm -f "$OUT/stop-sampling"; : > "$OUT/pods.txt"; sampler & sampler_pid=$!
posts=(); for i in $(seq 1 6); do svc_post "/job/kagent/buildWithParameters?N=$i&delay=0sec" >/dev/null & posts+=($!); done; wait "${posts[@]}"
check "3 all 6 builds complete" eventually 400 bash -c "for i in 1 2 3 4 5 6; do [[ \$(curl -gsf -u '$AUTH' http://localhost:$SVC/job/kagent/\$i/api/json?tree=result) == *SUCCESS* ]] || exit 1; done"
touch "$OUT/stop-sampling"; wait $sampler_pid 2>/dev/null
max=$(awk '$4 != "Succeeded" && $4 != "Failed" {n[$1]++} END {m = 0; for (t in n) if (n[t] > m) m = n[t]; print m}' "$OUT/pods.txt")
info "3 agent pods seen: $(awk '{print $2}' "$OUT/pods.txt" | sort -u | wc -l | tr -d ' '), at most $max at once"
check "3 at most 4 agent pods at any time (the cap holds for the cluster)" test "$max" -le 4
pods_used=$(for i in $(seq 1 6); do svc "/job/kagent/$i/consoleText" | grep -o 'on-pod-[a-z0-9-]*' | head -1; done | sort)
check "3 each build ran on its own pod" test "$(uniq <<<"$pods_used" | wc -l | tr -d ' ')" = 6
mismatch=0
for i in $(seq 1 6); do
  pod=$(svc "/job/kagent/$i/consoleText" | grep -o 'on-pod-[a-z0-9-]*' | head -1 | sed 's/^on-pod-//')
  owner=$(awk -v p="$pod" '$2 == p {print $3}' "$OUT/pods.txt" | tail -1)
  controller=$(ran_on kagent "$i")
  [[ "$owner" == *"$controller"* ]] || { mismatch=$((mismatch+1)); info "3 build $i ran on $controller, its pod $pod belongs to $owner"; }
done
check "3 each pod belonged to the controller that ran its build" test "$mismatch" = 0
created=$(awk '{print $2}' "$OUT/pods.txt" | sort -u | wc -l | tr -d ' ')
check "3 no pod was created for an item another controller ran (6 builds, $created pods)" test "$created" = 6

# ------------------------------------------------------------------ 4. losing a controller with a build on a Kubernetes agent
log "scenario 4: delete a controller pod while it runs a Pipeline on a Kubernetes agent"
pipeline survive 'retry(count: 2, conditions: [kubernetesAgent(), nonresumable()]) { node("k8s") { sh '"'"'echo attempt-on-$(hostname); sleep 45'"'"' } }; echo "survived"'
on "$REL-0" "/job/survive/build?delay=0sec" -X POST >/dev/null
eventually 120 bash -c "curl -gsf -u '$AUTH' http://localhost:$SVC/job/survive/1/consoleText 2>/dev/null | grep -q attempt-on" || true
first_pod=$(svc /job/survive/1/consoleText | grep -o 'attempt-on-[a-z0-9-]*' | head -1 | sed 's/^attempt-on-//')
owner_before=$(psql_q "select convert_from(content, 'UTF8') from jenkins_store where key = 'jobs/survive/running/1.xml'" | sed -n 's/^controller=//p')
info "4 survive #1 running on $owner_before, agent pod $first_pod"
k delete pod "$owner_before" --grace-period=0 --force >/dev/null 2>&1
check "4 the build completes after its controller's pod was deleted" eventually 400 result_is survive 1 SUCCESS
check "4 it was retried on a new agent pod" bash -c "[[ \$(curl -gsf -u '$AUTH' http://localhost:$SVC/job/survive/1/consoleText | grep -c 'attempt-on-') -ge 2 ]]"
check "4 the old agent pod is garbage collected" eventually 400 bash -c "! kubectl --context kind-$CLUSTER -n $NS get pod '$first_pod' >/dev/null 2>&1"
eventually 400 bash -c "[[ \$(kubectl --context kind-$CLUSTER -n $NS get pods -l app.kubernetes.io/name=$REL-controller -o jsonpath='{range .items[*]}{.status.containerStatuses[0].ready}{\"\\n\"}{end}' | grep -c true) == 2 ]]"

# ------------------------------------------------------------------ 4b. pod workspaces and stashes across an adoption (Target 15)
log "scenario 4b: inline podTemplate; a stage stashes, the next is interrupted by losing its controller and resumes on a new pod"
# retry inside podTemplate, as the kubernetes plugin asks for inline templates: the resumed podTemplate step registers
# its template on the adopting controller, so the retry condition recognises the lost pod as a Kubernetes agent
pipeline carry 'stage("write") { podTemplate { node(POD_LABEL) { sh "echo carried-by-stash > out.txt"; stash name: "out", includes: "out.txt" } } }
stage("use") { podTemplate { retry(count: 2, conditions: [kubernetesAgent(), nonresumable()]) { node(POD_LABEL) {
  sh '"'"'echo attempt-on-$(hostname); if test -f out.txt; then echo workspace-kept; else echo workspace-new; fi'"'"'
  sleep 45; unstash "out"; sh "cat out.txt" } } } }'
on "$REL-0" "/job/carry/build?delay=0sec" -X POST >/dev/null
eventually 180 bash -c "curl -gsf -u '$AUTH' http://localhost:$SVC/job/carry/1/consoleText 2>/dev/null | grep -q attempt-on" || true
owner_before=$(psql_q "select convert_from(content, 'UTF8') from jenkins_store where key = 'jobs/carry/running/1.xml'" | sed -n 's/^controller=//p')
info "4b carry #1 running on $owner_before"
k delete pod "$owner_before" --grace-period=0 --force >/dev/null 2>&1
check "4b the build completes after its controller's pod was deleted" eventually 600 result_is carry 1 SUCCESS
check "4b the retried stage ran on a new pod, with a new workspace" bash -c "curl -gsf -u '$AUTH' http://localhost:$SVC/job/carry/1/consoleText | grep -c 'attempt-on-' | grep -q '^[2-9]' && curl -gsf -u '$AUTH' http://localhost:$SVC/job/carry/1/consoleText | grep -q workspace-new"
check "4b ... and got the earlier stage's file from its stash" bash -c "curl -gsf -u '$AUTH' http://localhost:$SVC/job/carry/1/consoleText | grep -q '^carried-by-stash'"
stash_keys=$(g_on "$REL-1" 'println jenkins.persistence.PersistenceStores.getBlobStore().list("artifacts/carry/1/stashes").join(" ")' | tr -d '\r')
info "4b the stash in blob storage: ${stash_keys:-none}"
check "4b ... which the artifact manager had put in blob storage" test -n "$stash_keys"
# evidence of 4b while it is still there (the pods are replaced later)
curl -gs -u "$AUTH" "http://localhost:$SVC/job/carry/1/consoleText" > "$OUT/4b-carry-console.txt" 2>/dev/null || true
for pod in $(k get pods -l app.kubernetes.io/name=$REL-controller -o name 2>/dev/null); do k logs "$pod" > "$OUT/4b-${pod#pod/}.log" 2>&1 || true; done
psql_q "select key, convert_from(content, 'UTF8') from jenkins_store where key like 'jobs/carry/%' and key not like '%/builds/%'" > "$OUT/4b-carry-keys.txt" 2>&1 || true
eventually 400 bash -c "[[ \$(kubectl --context kind-$CLUSTER -n $NS get pods -l app.kubernetes.io/name=$REL-controller -o jsonpath='{range .items[*]}{.status.containerStatuses[0].ready}{\"\\n\"}{end}' | grep -c true) == 2 ]]"
counters_zero() { [[ "$(psql_q "select coalesce(sum(count), 0) from jenkins_counters")" == 0 ]]; }
check "4b the cluster's agent counters are back to 0 once the pods are gone" eventually 300 counters_zero
info "4b counter rows left: $(psql_q "select count(*) from jenkins_counters")"
zero_rows_pruned() { [[ "$(psql_q "select count(*) from jenkins_counters where scope like 'k8s-template:%' and count = 0")" == 0 ]]; }
check "4b the inline templates' counters do not accumulate (rows at 0 are pruned)" zero_rows_pruned

# ------------------------------------------------------------------ 5. no foreign garbage collection
log "scenario 5: no controller deletes another's pods"
for p in $(controllers); do k logs "$p" -c jenkins > "$OUT/$p.log" 2>&1; done
k logs "$REL-0" -c jenkins --previous > "$OUT/$REL-0-previous.log" 2>/dev/null || true
foreign=0
for f in "$OUT"/$REL-*.log; do
  me=$(basename "$f" .log | sed 's/-previous$//')
  while read -r pod; do
    owner=$(awk -v p="$pod" '$2 == p {print $3}' "$OUT/pods.txt" | tail -1)
    [[ -z "$owner" || "$owner" == *"$me"* ]] || { foreign=$((foreign+1)); info "5 $me deleted $pod of $owner"; }
  done < <(grep -o 'Deleting orphan pod [^ ]*' "$f" | awk '{print $4}' | sed 's#.*/##')
done
check "5 no controller garbage collected another's pod" test "$foreign" = 0

# ------------------------------------------------------------------ 6. scale to 3
log "scenario 6: helm upgrade to 3 controllers"
helm --kube-context "kind-$CLUSTER" -n "$NS" upgrade "$REL" "$CHART" --reuse-values --set controllers=3 >/dev/null
check "6 three controllers ready" eventually 400 bash -c "[[ \$(kubectl --context kind-$CLUSTER -n $NS get pods -l app.kubernetes.io/name=$REL-controller -o jsonpath='{range .items[*]}{.status.containerStatuses[0].ready}{\"\\n\"}{end}' | grep -c true) == 3 ]]"
check "6 the third is a live member" eventually 60 bash -c "[[ \$(curl -gsf -u '$AUTH' 'http://localhost:$SVC/manage/cluster/api/json?depth=1' | python3 -c 'import json,sys; d=json.load(sys.stdin); print(len([m for m in d[\"members\"] if m[\"alive\"]]))') == 3 ]]"
jobs_2=$(on "$REL-2" '/api/json?tree=jobs[name]' | jq_ '",".join(sorted(j["name"] for j in d["jobs"]))')
check "6 it sees every job ($jobs_2)" test "$jobs_2" = "carry,flyweight,fs9,kagent,survive"
check "6 and the history (kagent: 6 builds)" test "$(on "$REL-2" '/job/kagent/api/json?tree=builds[number]' | jq_ 'len(d["builds"])')" = 6
on "$REL-2" "/job/flyweight/build?delay=0sec" -X POST >/dev/null
check "6 and runs builds" eventually 120 result_is flyweight 11 SUCCESS

# ------------------------------------------------------------------ 7. every controller pod deleted at once
log "scenario 7: delete every controller pod at once"
k delete pod -l "app.kubernetes.io/name=$REL-controller" --grace-period=0 --force >/dev/null 2>&1
check "7 they come back" eventually 400 bash -c "[[ \$(kubectl --context kind-$CLUSTER -n $NS get pods -l app.kubernetes.io/name=$REL-controller -o jsonpath='{range .items[*]}{.status.containerStatuses[0].ready}{\"\\n\"}{end}' | grep -c true) == 3 ]]"
check "7 with every job and build (flyweight: 11)" eventually 60 bash -c "[[ \$(curl -gsf -u '$AUTH' 'http://localhost:$SVC/job/flyweight/api/json?tree=builds[number]' | python3 -c 'import json,sys; print(len(json.load(sys.stdin)[\"builds\"]))') == 11 ]]"
check "7 the user created at runtime still signs in" bash -c "[[ \$(curl -gs -o /dev/null -w '%{http_code}' -u u9:u9-password-2 http://localhost:$SVC/whoAmI/api/json) == 200 ]]"

for p in $(controllers); do k logs "$p" -c jenkins > "$OUT/$p.log" 2>&1; done
cp "$VALIDATION_DIR/.et1/BUILD" "$OUT/BUILD" 2>/dev/null || true
r ""
r "Failures: $FAILED"
echo "ET9 exit=$FAILED"
exit $FAILED
