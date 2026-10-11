#!/bin/bash
# ET10: acceptance experiment for Target 10 (rolling upgrades, importing a file-based home, backup and restore), on kind.
# Uses the cluster and images of ET9 (cnj/jenkins:k8s; cnj/jenkins:k8s-next is the same with a rebuilt plugin).
# Exits non-zero if any check fails.
#
# Prerequisites: CORE_REF=cnj/t10-m1-import-api experiments/ET1-build.sh; experiments/ET9-build-image.sh
source "$(dirname "$0")/../lib.sh"
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.fsmonitor GIT_CONFIG_VALUE_0=false
CLUSTER=${KIND_CLUSTER:-cnj} NS=cnj-ops REL=ops OUT="$RAW_DIR/ET10" CHART="$ROOT_DIR/charts/cloud-native-jenkins"
rm -rf "$OUT"; mkdir -p "$OUT" "$OUT_DIR"
: > "$OUT/results.md"
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
FAILED=0
check() { local d=$1; shift; if "$@" >>"$OUT/check.log" 2>&1; then r "| ✅ | $d |"; else r "| ❌ | $d |"; FAILED=$((FAILED+1)); fi; }
info() { r "| ℹ️ | $* |"; }
eventually() { local t=$1 start=$SECONDS; shift; until "$@"; do (( SECONDS - start > t )) && return 1; sleep 2; done; }
k() { kubectl --context "kind-$CLUSTER" -n "$NS" "$@"; }
h() { helm --kube-context "kind-$CLUSTER" -n "$NS" "$@"; }
jq_() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
on() { local pod=$1 path=$2; shift 2; k exec "$pod" -c jenkins -- curl -gsf -u "$AUTH" "http://localhost:8080$path" "$@"; }
g_on() { on "$1" /scriptText -X POST --data-urlencode "script=$2" | tr -d '\r' | sed -e 's/[[:space:]]*$//'; }
ready() { [[ "$(k get pods -l "app.kubernetes.io/name=$REL-controller" -o jsonpath='{range .items[*]}{.status.containerStatuses[0].ready}{"\n"}{end}' | grep -c true)" == "$1" ]]; }
result_on() { [[ "$(on "$1" "/job/$2/$3/api/json?tree=result" 2>/dev/null | jq_ 'd["result"]')" == "$4" ]]; }
pipeline_on() { # pod name script
  python3 - "$OUT_DIR/$2.xml" "$3" <<'PY'
import sys
from xml.sax.saxutils import escape
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job"><definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>%s</script><sandbox>true</sandbox></definition></flow-definition>""" % escape(sys.argv[2]))
PY
  k exec -i "$1" -c jenkins -- curl -gsf -u "$AUTH" -X POST "http://localhost:8080/createItem?name=$2" -H 'Content-Type: application/xml' --data-binary @- < "$OUT_DIR/$2.xml" >/dev/null
}
fresh_ns() { k delete namespace "$NS" --ignore-not-found --wait=true >/dev/null 2>&1; kubectl --context "kind-$CLUSTER" create namespace "$NS" >/dev/null; }

kind get clusters 2>/dev/null | grep -qx "$CLUSTER" || { echo "run ET9 first (it creates the kind cluster)" >&2; exit 2; }
# the "next" image: the same build, rebuilt (a new image id), to roll to
docker build -q -t cnj/jenkins:k8s-next - <<'EOF' >/dev/null
FROM cnj/jenkins:k8s
LABEL io.jenkins.cnj.next=true
EOF
kind load docker-image --name "$CLUSTER" cnj/jenkins:k8s-next >/dev/null
r "## ET10 results ($(date -u +%Y-%m-%dT%H:%MZ))"
r ""
r "| | Check |"
r "|---|---|"

# ------------------------------------------------------------------ 1. rolling upgrade with builds running
log "scenario 1: rolling upgrade while Pipelines run"
fresh_ns
"$CHART/scripts/make-seed-secret.sh" cnj/jenkins:k8s "$NS" >/dev/null
h install "$REL" "$CHART" --set kubernetesCloud.enabled=false >/dev/null
eventually 400 ready 2
pipeline_on "$REL-0" roll 'for (int i = 1; i <= 90; i++) { echo "tick ${i}"; sleep 1 }; echo "rolled"'
on "$REL-0" "/job/roll/build?delay=0sec" -X POST >/dev/null; on "$REL-1" "/job/roll/build?delay=0sec" -X POST >/dev/null
sleep 15
h upgrade "$REL" "$CHART" --reuse-values --set image=cnj/jenkins:k8s-next >/dev/null
both_versions=0
for i in $(seq 1 120); do
  v=$(on "$REL-0" /manage/cluster/api/json?depth=1 2>/dev/null || on "$REL-1" /manage/cluster/api/json?depth=1 2>/dev/null || true)
  imgs=$(k get pods -l "app.kubernetes.io/name=$REL-controller" -o jsonpath='{range .items[*]}{.spec.containers[0].image}{"\n"}{end}' | sort -u | wc -l | tr -d ' ')
  [[ "$imgs" == 2 ]] && both_versions=1
  [[ "$(k get pods -l "app.kubernetes.io/name=$REL-controller" -o jsonpath='{range .items[*]}{.spec.containers[0].image}{"\n"}{end}' | sort -u)" == cnj/jenkins:k8s-next ]] && ready 2 && break
  sleep 3
done
check "1 controllers were replaced one at a time (both images ran at once)" test "$both_versions" = 1
check "1 the rollout completes with both controllers on the new image" eventually 300 ready 2
pipeline_on "$REL-1" during 'echo "after the rollout"'
on "$REL-1" "/job/during/build?delay=0sec" -X POST >/dev/null
check "1 every running build completed" eventually 300 bash -c "for n in 1 2; do [[ \$(kubectl --context kind-$CLUSTER -n $NS exec $REL-0 -c jenkins -- curl -gsf -u '$AUTH' http://localhost:8080/job/roll/\$n/api/json?tree=result) == *SUCCESS* ]] || exit 1; done"
check "1 a build started after the rollout ran" eventually 120 result_on "$REL-0" during 1 SUCCESS
members=$(on "$REL-0" /manage/cluster/api/json?depth=1 | jq_ '" ".join(sorted("%s:%s" % (m["name"], m["protocol"]) for m in d["members"] if m["alive"]))')
info "1 live members after the rollout: $members"

# ------------------------------------------------------------------ 2. refusing to start next to an older protocol
log "scenario 2: a controller requiring a newer peer protocol than the live peers speak"
h upgrade "$REL" "$CHART" --reuse-values --set controllers=3 --set "javaOpts=-Dio.jenkins.plugins.cnj.store.minimumPeerProtocol=99" >/dev/null 2>&1
# only the new pod gets the property applied at start; the others restart too (javaOpts changed), so check the first that starts
check "2 a controller requiring protocol 99 refuses to start, saying why" eventually 300 bash -c "for p in $REL-0 $REL-1 $REL-2; do kubectl --context kind-$CLUSTER -n $NS logs \$p -c jenkins 2>/dev/null | grep -q 'needs every peer to speak cluster protocol 99' && exit 0; done; exit 1"
h upgrade "$REL" "$CHART" --reuse-values --set controllers=2 --set javaOpts= >/dev/null
eventually 400 ready 2

# ------------------------------------------------------------------ 3. importing a file-based home
log "scenario 3: import a plain Jenkins home"
# a plain controller (no store), as a team's existing Jenkins: an admin user and executors of its own
cat <<EOF | k apply -f - >/dev/null
apiVersion: v1
kind: ConfigMap
metadata: {name: old-casc}
data:
  base.yaml: |
    jenkins:
      numExecutors: 2
      # the kind node's disk is shared with every other image and volume; never take the built-in node offline for it
      nodeMonitors:
        - diskSpace: {freeSpaceThreshold: "100MB"}
        - tmpSpace: {freeSpaceThreshold: "100MB"}
      securityRealm:
        local:
          users: [{id: admin, password: admin}]
      authorizationStrategy: loggedInUsersCanDoAnything
EOF
cat <<EOF | k apply -f - >/dev/null
apiVersion: v1
kind: PersistentVolumeClaim
metadata: {name: old-home}
spec: {accessModes: [ReadWriteOnce], resources: {requests: {storage: 1Gi}}}
---
apiVersion: v1
kind: Pod
metadata: {name: old, labels: {app: old}}
spec:
  securityContext: {fsGroup: 1000}
  containers:
    - name: jenkins
      image: cnj/jenkins:k8s
      imagePullPolicy: Never
      env:
        - {name: JAVA_OPTS, value: "-Djenkins.install.runSetupWizard=false"}
        - {name: CASC_JENKINS_CONFIG, value: /casc/base.yaml}
      volumeMounts: [{name: home, mountPath: /var/jenkins_home}, {name: casc, mountPath: /casc}]
  volumes: [{name: home, persistentVolumeClaim: {claimName: old-home}}, {name: casc, configMap: {name: old-casc}}]
EOF
k wait --for=condition=Ready pod/old --timeout=300s >/dev/null
until on old /api/json >/dev/null 2>&1; do sleep 3; done
g_on old 'def f = Jenkins.get().createProject(com.cloudbees.hudson.plugins.folder.Folder, "team"); f.createProject(hudson.model.FreeStyleProject, "fs").with { it.buildersList.add(new hudson.tasks.Shell("echo old-build; echo \"<testsuite name=\\\"s\\\" tests=\\\"1\\\"><testcase classname=\\\"c\\\" name=\\\"t\\\"/></testsuite>\" > r.xml")); it.publishersList.add(new hudson.tasks.junit.JUnitResultArchiver("r.xml")); it.save() }' >/dev/null
pipeline_on old oldp 'echo "an old pipeline"'
on old "/job/team/job/fs/build?delay=0sec" -X POST >/dev/null; sleep 20; on old "/job/team/job/fs/build?delay=0sec" -X POST >/dev/null
on old "/job/oldp/build?delay=0sec" -X POST >/dev/null
for b in "team/job/fs 1" "team/job/fs 2" "oldp 1"; do set -- $b; eventually 120 result_on old "$1" "$2" SUCCESS || true; done
g_on old 'println "executors: " + Jenkins.get().numExecutors + ", online: " + Jenkins.get().toComputer().isOnline() + " (" + Jenkins.get().toComputer().offlineCause + ")"; Jenkins.get().queue.items.each { println "queued: " + it.task.fullDisplayName + ": " + it.why }; println "fs builds: " + Jenkins.get().getItemByFullName("team/fs")?.builds?.collect { it.number + " " + it.result }' > "$OUT/old-state.txt" 2>&1 || true
g_on old 'Jenkins.get().securityRealm.createAccount("olduser", "old-password-1")' >/dev/null
old_token=$(g_on old 'def u = hudson.model.User.getById("olduser", false); def t = u.getProperty(jenkins.security.ApiTokenProperty).tokenStore.generateNewToken("old"); u.save(); println t.plainValue')
g_on old 'def p = com.cloudbees.plugins.credentials.SystemCredentialsProvider.getInstance(); p.getCredentials().add(new org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl(com.cloudbees.plugins.credentials.CredentialsScope.GLOBAL, "oldsecret", "old", hudson.util.Secret.fromString("old-value"))); p.save()' >/dev/null
g_on old 'Jenkins.get().addNode(new hudson.slaves.DumbSlave("oldnode", "/tmp/oldnode", new hudson.slaves.JNLPLauncher())); Jenkins.get().setQuietPeriod(3)' >/dev/null
info "3 the old controller's quiet period: $(g_on old 'println Jenkins.get().quietPeriod')"
info "3 the old home: $(g_on old 'println Jenkins.get().getAllItems(hudson.model.Job).collect { it.fullName + "#" + it.builds.size() }.join(" ")')"
k delete pod old --wait=true >/dev/null
# the importer: the old home, with the store, importing (its keys become the cluster's seed)
h upgrade "$REL" "$CHART" --reuse-values --set controllers=0 >/dev/null; sleep 10
k exec "$REL-postgres-0" -- psql -U jenkins -c "drop schema public cascade; create schema public;" >/dev/null # a cluster to import into: empty
importer() {
cat <<EOF | k apply -f - >/dev/null
apiVersion: v1
kind: Pod
metadata: {name: importer}
spec:
  securityContext: {fsGroup: 1000}
  containers:
    - name: jenkins
      image: cnj/jenkins:k8s
      imagePullPolicy: Never
      envFrom: [{secretRef: {name: $REL-store}}]
      env:
        - {name: CNJ_NAME, value: importer}
        - {name: JAVA_OPTS, value: "-Djenkins.install.runSetupWizard=false -Dio.jenkins.plugins.cnj.store.importHome=true"}
        - {name: CASC_JENKINS_CONFIG, value: /casc/base.yaml}
      volumeMounts: [{name: home, mountPath: /var/jenkins_home}, {name: casc, mountPath: /casc}]
  volumes: [{name: home, persistentVolumeClaim: {claimName: old-home}}, {name: casc, configMap: {name: $REL-casc}}]
EOF
  k wait --for=condition=Ready pod/importer --timeout=300s >/dev/null
  until on importer /api/json >/dev/null 2>&1; do sleep 3; done
  sleep 5
}
importer
k exec importer -c jenkins -- cat /var/jenkins_home/cnj-import-report.txt > "$OUT/import-report-1.txt" 2>/dev/null || true
info "3 the importer's quiet period: $(g_on importer 'println Jenkins.get().quietPeriod'); stored: $(k exec "$REL-postgres-0" -- psql -U jenkins -At -c "select substring(convert_from(content, 'UTF8') from '<quietPeriod>[0-9]+</quietPeriod>') from jenkins_store where key = 'config.xml'")"
k exec "$REL-postgres-0" -- psql -U jenkins -At -c "select e.kind, e.version, e.origin, e.at from jenkins_store_events e where e.key = 'config.xml' order by seq" > "$OUT/config-events.txt" 2>&1 || true
info "3 import: $(tail -1 "$OUT/import-report-1.txt")"
k exec importer -c jenkins -- tar -C /var/jenkins_home -czf - secret.key secret.key.not-so-secret identity.key.enc secrets > "$OUT_DIR/old-seed.tgz"
k create secret generic old-seed --from-file=seed.tgz="$OUT_DIR/old-seed.tgz" >/dev/null
k delete pod importer --wait=true >/dev/null
h upgrade "$REL" "$CHART" --reuse-values --set seedSecret=old-seed --set controllers=2 >/dev/null
eventually 400 ready 2
check "3 the folder's job and its builds are there (team/fs: 2)" bash -c "[[ \$(kubectl --context kind-$CLUSTER -n $NS exec $REL-1 -c jenkins -- curl -gsf -u '$AUTH' 'http://localhost:8080/job/team/job/fs/api/json?tree=builds[number]' | python3 -c 'import json,sys; print(len(json.load(sys.stdin)[\"builds\"]))') == 2 ]]"
check "3 an old console" bash -c "kubectl --context kind-$CLUSTER -n $NS exec $REL-1 -c jenkins -- curl -gsf -u '$AUTH' http://localhost:8080/job/team/job/fs/1/consoleText | grep -q old-build"
check "3 an old Pipeline console" bash -c "kubectl --context kind-$CLUSTER -n $NS exec $REL-0 -c jenkins -- curl -gsf -u '$AUTH' http://localhost:8080/job/oldp/1/consoleText | grep -q 'an old pipeline'"
check "3 old test results" bash -c "[[ \$(kubectl --context kind-$CLUSTER -n $NS exec $REL-1 -c jenkins -- curl -gsf -u '$AUTH' 'http://localhost:8080/job/team/job/fs/2/testReport/api/json?tree=passCount') == *'\"passCount\":1'* ]]"
check "3 the old user's API token works" bash -c "[[ \$(kubectl --context kind-$CLUSTER -n $NS exec $REL-0 -c jenkins -- curl -gs -o /dev/null -w '%{http_code}' -u 'olduser:$old_token' http://localhost:8080/whoAmI/api/json) == 200 ]]"
pipeline_on "$REL-0" usecred 'withCredentials([string(credentialsId: "oldsecret", variable: "S")]) { echo "length ${S.length()}" }'
on "$REL-0" "/job/usecred/build?delay=0sec" -X POST >/dev/null
check "3 the old credential decrypts in a build" eventually 120 bash -c "kubectl --context kind-$CLUSTER -n $NS exec $REL-1 -c jenkins -- curl -gsf -u '$AUTH' http://localhost:8080/job/usecred/1/consoleText | grep -q 'length 9'"
info "3 node and quiet period on $REL-1: $(g_on "$REL-1" 'println("" + (Jenkins.get().getNode("oldnode") != null) + "/" + Jenkins.get().quietPeriod)')"
check "3 the old node and setting" bash -c "[[ \$(kubectl --context kind-$CLUSTER -n $NS exec $REL-1 -c jenkins -- curl -gsf -u '$AUTH' -X POST http://localhost:8080/scriptText --data-urlencode 'script=println(\"\" + (Jenkins.get().getNode(\"oldnode\") != null) + \"/\" + Jenkins.get().quietPeriod)' | tr -d '\r\n ') == true/3 ]]"
# again: nothing changes
h upgrade "$REL" "$CHART" --reuse-values --set controllers=0 >/dev/null; sleep 10
importer
k exec importer -c jenkins -- cat /var/jenkins_home/cnj-import-report.txt > "$OUT/import-report-2.txt" 2>/dev/null || true
k delete pod importer --wait=true >/dev/null
info "3 import again: $(tail -1 "$OUT/import-report-2.txt")"
check "3 importing again changes nothing" bash -c "tail -1 '$OUT/import-report-2.txt' | grep -q 'files imported 0, .*builds imported 0'"
h upgrade "$REL" "$CHART" --reuse-values --set controllers=2 >/dev/null
eventually 400 ready 2

# ------------------------------------------------------------------ 4. backup and restore
log "scenario 4: back up while a build runs, restore into a new namespace"
pipeline_on "$REL-0" during-backup 'sleep 20; echo "ran across the backup"'
on "$REL-0" "/job/during-backup/build?delay=0sec" -X POST >/dev/null; sleep 5
jobs_before=$(on "$REL-0" '/api/json?tree=jobs[name]' | jq_ '",".join(sorted(j["name"] for j in d["jobs"]))')
"$CHART/scripts/backup.sh" "$NS" "$REL" "$OUT_DIR/backup" > "$OUT/backup.txt" 2>&1
info "4 $(cat "$OUT/backup.txt")"
k get secret old-seed -o yaml | sed -e '/resourceVersion/d' -e '/uid:/d' -e '/creationTimestamp/d' -e "s/namespace: $NS/namespace: $NS-restored/" > "$OUT_DIR/seed.yaml"
k delete namespace "$NS" --wait=true >/dev/null 2>&1
NS="$NS-restored"; fresh_ns
k apply -f "$OUT_DIR/seed.yaml" >/dev/null
CONTROLLERS=2 "$CHART/scripts/restore.sh" "$NS" "$REL" "$OUT_DIR/backup" "$CHART" --set kubernetesCloud.enabled=false --set seedSecret=old-seed > "$OUT/restore.txt" 2>&1
check "4 the restored cluster comes up" eventually 400 ready 2
jobs_after=$(on "$REL-0" '/api/json?tree=jobs[name]' | jq_ '",".join(sorted(j["name"] for j in d["jobs"]))')
check "4 every job is there ($jobs_after)" test "$jobs_after" = "$jobs_before"
check "4 history and consoles (team/fs #1)" bash -c "kubectl --context kind-$CLUSTER -n $NS exec $REL-1 -c jenkins -- curl -gsf -u '$AUTH' http://localhost:8080/job/team/job/fs/1/consoleText | grep -q old-build"
check "4 users (the old token)" bash -c "[[ \$(kubectl --context kind-$CLUSTER -n $NS exec $REL-0 -c jenkins -- curl -gs -o /dev/null -w '%{http_code}' -u 'olduser:$old_token' http://localhost:8080/whoAmI/api/json) == 200 ]]"
check "4 credentials and nodes" bash -c "[[ \$(kubectl --context kind-$CLUSTER -n $NS exec $REL-1 -c jenkins -- curl -gsf -u '$AUTH' -X POST http://localhost:8080/scriptText --data-urlencode 'script=println(\"\" + com.cloudbees.plugins.credentials.SystemCredentialsProvider.getInstance().getCredentials().collect { it.id }.contains(\"oldsecret\") + \"/\" + (Jenkins.get().getNode(\"oldnode\") != null))' | tr -d '\r\n ') == true/true ]]"
r=$(on "$REL-0" '/job/during-backup/1/api/json?tree=result,building' 2>/dev/null || echo '{}')
info "4 the build running at backup time, after restore: $r"

for p in $(k get pods -o name | grep "$REL-[0-9]"); do k logs "$p" -c jenkins > "$OUT/$(basename "$p").log" 2>&1; done
cp "$VALIDATION_DIR/.et1/BUILD" "$OUT/BUILD" 2>/dev/null || true
k delete namespace "$NS" --wait=false >/dev/null 2>&1
r ""
r "Failures: $FAILED"
echo "ET10 exit=$FAILED"
exit $FAILED
