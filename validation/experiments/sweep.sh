#!/bin/bash
# Runs every experiment on a harness already built (experiments/ET1-build.sh, here or on another machine with .et1
# copied over): ET1-ET22 on Docker Compose, ET17 again with C killed at 36 s, then the kind image and ET9, ET10 and
# ET13, then the S3 variant (experiments/with-s3.sh). One line per experiment on stdout; logs in $SWEEP_OUT.
#
#   experiments/sweep.sh            everything
#   SKIP_KIND=1 SKIP_S3=1 experiments/sweep.sh
#
# The kind experiments use the namespace "cnj" of the kind cluster "cnj": a release there is deleted first.
cd "$(dirname "$0")/.." || exit 2
SWEEP_OUT=${SWEEP_OUT:-$PWD/out/sweep}
mkdir -p "$SWEEP_OUT"
limit() { perl -e 'alarm shift; exec @ARGV' "$@"; }
run() { # name command...
  local name=$1; shift
  limit 3600 "$@" > "$SWEEP_OUT/$name.log" 2>&1
  echo "$name rc=$?"
}
down() { docker compose --project-directory . -f compose.et1.yaml -f compose.et17.yaml --profile et17 --profile agents --profile s3 down -v --remove-orphans >/dev/null 2>&1; }

# the kind release competes with the Compose experiments for the machine's memory
kubectl --context kind-cnj delete ns cnj --wait=true >/dev/null 2>&1
for e in ET1-shared-items ET2-scheduling ET3-build-records ET4-pipeline-failover ET5-shared-queue ET6-static-state \
    ET7-owner-routing ET8-history ET11-cross-cutting ET12-remainder ET14-store-outage ET15-logs-api-failover ET16-scale \
    ET17-scale-3 ET18-pitr ET19-plugin-manager ET20-websocket-cli ET21-browser ET22-avatar; do
  run "$e" "experiments/$e.sh"
done
KILL_AFTER=36 run ET17-scale-3-kill36 experiments/ET17-scale-3.sh
down

if [[ -z "${SKIP_KIND:-}" ]]; then
  run image experiments/ET9-build-image.sh
  kubectl --context kind-cnj delete ns cnj --wait=true >/dev/null 2>&1
  for e in ET9-kubernetes ET10-operations ET13-cluster-plugins; do
    run "$e" "experiments/$e.sh"
  done
fi

if [[ -z "${SKIP_S3:-}" ]]; then
  for e in ET3-build-records ET4-pipeline-failover ET8-history ET15-logs-api-failover ET19-plugin-manager; do
    run "s3-$e" experiments/with-s3.sh "$e"
  done
  down
fi
