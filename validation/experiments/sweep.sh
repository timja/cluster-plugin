#!/bin/bash
# Runs every experiment on a harness already built (experiments/ET1-build.sh, here or on another machine with .et1
# copied over): ET1-ET22 on Docker Compose, ET17 again with C killed at 36 s, the S3 variant (experiments/with-s3.sh) of
# ET3, ET4, ET8, ET15 and ET19, and the kind experiments (the image, ET9, ET10, ET13). One line per experiment on stdout;
# logs in $SWEEP_OUT.
#
# The Compose experiments run side by side in lanes, each lane in a slot of its own (lib.sh: its Compose project, ports,
# container names and files), taking the next experiment from a queue, longest first. How many lanes:
#   SWEEP_LANES=n        that many
#   on macOS             1 (a laptop: Docker Desktop's VM is small, and the machine stays usable)
#   otherwise            from the machine: a lane per 4 CPUs and per 8 GB that Docker has, at most 8
# The kind experiments run in a lane of their own beside them (slot 0; they share no ports with the Compose ones).
#
#   experiments/sweep.sh
#   SWEEP_LANES=2 SKIP_KIND=1 SKIP_S3=1 experiments/sweep.sh
#
# The kind experiments use the namespace "cnj" of the kind cluster "cnj": a release there is deleted first.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh
set +euo pipefail # lib.sh is strict for experiments; the sweep carries on past failures
SWEEP_OUT=${SWEEP_OUT:-$PWD/out/sweep}
rm -rf "$SWEEP_OUT"; mkdir -p "$SWEEP_OUT"
# temporary files under the repository: Docker Desktop (e.g. on Linux) shares only some host paths, not /tmp, with
# containers, and experiments bind-mount files they make with mktemp
export TMPDIR="$PWD/out/tmp"; mkdir -p "$TMPDIR"

if [[ -n "${SWEEP_LANES:-}" ]]; then
  lanes=$SWEEP_LANES
elif [[ "$(uname)" == Darwin ]]; then
  lanes=1
else
  cpus=$(docker info -f '{{.NCPU}}' 2>/dev/null || nproc)
  mem_gb=$(( $(docker info -f '{{.MemTotal}}' 2>/dev/null || echo 8589934592) / 1024 / 1024 / 1024 ))
  lanes=$(( cpus / 4 < mem_gb / 8 ? cpus / 4 : mem_gb / 8 ))
  (( lanes < 1 )) && lanes=1
  (( lanes > 8 )) && lanes=8
fi

# the queue, longest first (rough minutes; the order only balances the lanes)
queue="$SWEEP_OUT/queue"; mkdir -p "$queue"
jobs=(
  "30 ET17-scale-3" "30 ET17-scale-3-kill36" "25 ET2-scheduling" "25 ET16-scale" "15 ET14-store-outage"
  "15 ET15-logs-api-failover" "12 ET4-pipeline-failover" "10 ET21-browser" "10 ET18-pitr" "8 ET1-shared-items"
  "8 ET5-shared-queue" "8 ET6-static-state" "8 ET7-owner-routing" "8 ET12-remainder" "6 ET3-build-records"
  "6 ET8-history" "6 ET11-cross-cutting" "6 ET19-plugin-manager" "6 ET20-websocket-cli" "6 ET22-avatar"
)
if [[ -z "${SKIP_S3:-}" ]]; then
  jobs+=("15 s3-ET15-logs-api-failover" "12 s3-ET4-pipeline-failover" "6 s3-ET3-build-records" "6 s3-ET8-history"
    "6 s3-ET19-plugin-manager")
fi
i=0
for j in $(printf '%s\n' "${jobs[@]}" | sort -rn | awk '{print $2}'); do
  printf -v n '%03d' $i; : > "$queue/$n-$j"; i=$((i + 1))
done

limit() { perl -e 'alarm shift; exec @ARGV' "$@"; }
# run_job <name>: one experiment, by its sweep name (s3-… on the S3 variant, ET17-scale-3-kill36 with KILL_AFTER=36)
run_job() {
  local name=$1 rc
  case $name in
    s3-*) limit 3600 experiments/with-s3.sh "${name#s3-}" > "$SWEEP_OUT/$name.log" 2>&1 ;;
    ET17-scale-3-kill36) KILL_AFTER=36 limit 3600 experiments/ET17-scale-3.sh > "$SWEEP_OUT/$name.log" 2>&1 ;;
    *) limit 3600 "experiments/$name.sh" > "$SWEEP_OUT/$name.log" 2>&1 ;;
  esac
  rc=$?
  echo "$name rc=$rc (slot $SLOT)"
}
down_slot() {
  docker compose --project-directory . -f compose.et1.yaml -f compose.et17.yaml --profile et17 --profile agents --profile s3 \
    down -v --remove-orphans >/dev/null 2>&1
}
# lane <slot>: takes jobs from the queue (an atomic rename claims one) until it is empty
lane() {
  export SLOT=$1
  source ./lib.sh
  set +euo pipefail
  prepare_slot "$SLOT"
  local f
  while true; do
    f=$(ls "$queue" 2>/dev/null | head -1)
    [[ -z "$f" ]] && break
    mv "$queue/$f" "$SWEEP_OUT/claimed-$f-slot$SLOT" 2>/dev/null || continue # another lane took it first
    run_job "${f#*-}"
  done
  down_slot
}
kind_lane() {
  export SLOT=0 SCRATCH=kind
  source ./lib.sh
  set +euo pipefail
  run_job ET9-build-image
  kubectl --context kind-cnj delete ns cnj --wait=true >/dev/null 2>&1
  for e in ET9-kubernetes ET10-operations ET13-cluster-plugins; do run_job "$e"; done
}

echo "sweep: $lanes Compose lane(s), ${#jobs[@]} experiments$([[ -z "${SKIP_KIND:-}" ]] && echo ", and the kind experiments")"
# ET6 and ET9 both compile the browser journey: compiled once here, so they do not race on its target/
(cd journey && mvn -B -ntp -q compile) > "$SWEEP_OUT/journey-compile.log" 2>&1 || echo "journey compile failed (see $SWEEP_OUT/journey-compile.log)"
kubectl --context kind-cnj delete ns cnj --wait=true >/dev/null 2>&1
pids=()
[[ -z "${SKIP_KIND:-}" ]] && { kind_lane & pids+=($!); }
for s in $(seq 1 "$lanes"); do
  lane "$s" & pids+=($!)
  sleep 20 # staggered: controllers starting all at once compete for CPU
done
wait "${pids[@]}"
echo "sweep done"
