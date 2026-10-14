#!/bin/bash
# ET21: acceptance experiment for Target 15 (real browser): Playwright drives Chromium through the round-robin load
# balancer (no stickiness): a live console following a build on one controller, a job configured in the browser, the
# cluster page, the plugin manager's cluster notice. Tests: validation/ui-tests (npm, @playwright/test).
# ET setup (two tmpfs controllers, S0, Postgres, Azurite, token auth) plus `rr`.
# Exits non-zero if any check fails.
#
# Prerequisites: CORE_REF=cnj/t15-remaining experiments/ET1-build.sh; (cd ui-tests && npm ci)
source "$(dirname "$0")/../lib.sh"
A=$PORT_A B=$PORT_B RR=$PORT_RR OUT="$RAW_DIR/ET21" ET1="$ET1_DIR" UI="$VALIDATION_DIR/ui-tests"
et1() { docker compose --project-directory "$VALIDATION_DIR" -f "$VALIDATION_DIR/compose.et1.yaml" "$@"; }
rm -rf "$OUT"; mkdir -p "$OUT" "$OUT_DIR"
: > "$OUT/results.md"
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
FAILED=0
check() { local d=$1; shift; if "$@" >>"$OUT/check.log" 2>&1; then r "| ✅ | $d |"; else r "| ❌ | $d |"; FAILED=$((FAILED+1)); fi; }
info() { r "| ℹ️ | $* |"; }
eventually() { local t=$1 start=$SECONDS; shift; until "$@"; do (( SECONDS - start > t )) && return 1; sleep 2; done; }
exists() { [[ "$(curl -gs -o /dev/null -w '%{http_code}' -u "$AUTH" "http://localhost:$1$2/api/json")" == 200 ]]; }

[[ -f "$ET1/war/jenkins.war" ]] || { echo "run ET1-build.sh first" >&2; exit 2; }
[[ -d "$UI/node_modules/@playwright/test" ]] || { echo "run (cd ui-tests && npm ci) first" >&2; exit 2; }

# ------------------------------------------------------------------ setup (as ET6, plus rr)
et1 down -v --remove-orphans >/dev/null 2>&1 || true
rm -rf "$ET1/seed"; mkdir -p "$ET1/seed"
docker rm -fv ${P}seed >/dev/null 2>&1 || true
docker run -d --name ${P}seed -p $PORT_SEED:8080 --tmpfs /var/jenkins_home:uid=1000,gid=1000 \
  -e JAVA_OPTS="-Djenkins.install.runSetupWizard=false" -e CASC_JENKINS_CONFIG=/casc -v "$VALIDATION_DIR/jenkins/casc-et1:/casc:ro" \
  -v "$ET1/war:/war-override:ro" -v "$ET1/plugins:/extra-plugins:ro" cnj/jenkins:dev >/dev/null
wait_up $PORT_SEED
groovy $PORT_SEED 'hudson.util.Secret.fromString("x").getEncryptedValue(); org.jenkinsci.main.modules.instance_identity.InstanceIdentity.get()' >/dev/null
docker exec ${P}seed tar -C /var/jenkins_home -cf - secret.key secret.key.not-so-secret identity.key.enc secrets | tar -C "$ET1/seed" -xf -
docker rm -fv ${P}seed >/dev/null
et1 up -d postgres $BLOB_SERVICE >/dev/null 2>&1; wait_postgres
et1 up -d jenkins-a jenkins-b >/dev/null 2>&1
wait_up $A; wait_up $B
et1 up -d rr >/dev/null 2>&1
r "## ET21 results ($(date -u +%Y-%m-%dT%H:%MZ))"
r ""
r "| | Check |"
r "|---|---|"
python3 - "$OUT_DIR/ui-ticker.xml" <<'PY'
import sys
from xml.sax.saxutils import escape
script = 'pipeline { agent none; stages { stage("tick") { steps { script { for (int i = 1; i <= 20; i++) { echo "tick ${i}"; sleep 2 } } } } } }'
open(sys.argv[1], "w").write("<?xml version='1.1' encoding='UTF-8'?>\n<flow-definition plugin=\"workflow-job\"><definition class=\"org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition\" plugin=\"workflow-cps\"><script>%s</script><sandbox>true</sandbox></definition></flow-definition>" % escape(script))
PY
create_job $A ui-ticker "$OUT_DIR/ui-ticker.xml"; eventually 15 exists $B /job/ui-ticker
printf '%s' "<?xml version='1.1' encoding='UTF-8'?><project><builders/></project>" > "$OUT_DIR/ui-config.xml"
create_job $A ui-config "$OUT_DIR/ui-config.xml"; eventually 15 exists $B /job/ui-config
post $A "/job/ui-ticker/build?delay=0sec" >/dev/null
sleep 3
log "Playwright"
(cd "$UI" && BASE_URL="http://localhost:$RR" AUTH="$AUTH" npx playwright test > "$OUT/playwright.txt" 2>&1) || true
cp "$UI/test-results/results.json" "$OUT/playwright-results.json" 2>/dev/null || true
while IFS=$'\t' read -r status title; do
  [[ -n "$title" ]] || continue
  check "$title" test "$status" = passed
done < <(python3 -c '
import json, sys
d = json.load(open(sys.argv[1]))
def walk(s):
    for spec in s.get("specs", []):
        for t in spec["tests"]:
            r = t["results"][-1] if t["results"] else {"status": "missing"}
            print(r["status"] + "\t" + spec["title"])
    for c in s.get("suites", []):
        walk(c)
for s in d.get("suites", []):
    walk(s)
' "$OUT/playwright-results.json" 2>/dev/null)
[[ -s "$OUT/playwright-results.json" ]] || check "Playwright ran" false
info "served by: $(grep -c . "$OUT/playwright.txt") lines of Playwright output"

et1 logs jenkins-a > "$OUT/jenkins-a.log" 2>&1; et1 logs jenkins-b > "$OUT/jenkins-b.log" 2>&1
cp "$ET1/BUILD" "$OUT/BUILD" 2>/dev/null || true
r ""
r "Failures: $FAILED"
exit $(( FAILED > 0 ))
