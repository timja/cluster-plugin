#!/bin/bash
# Shared helpers for scenarios and experiments. Source from validation/.
set -euo pipefail

VALIDATION_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$VALIDATION_DIR")"
RAW_DIR="$ROOT_DIR/findings/raw"
OUT_DIR="$VALIDATION_DIR/out"
AUTH="admin:admin"
COOKIES="$OUT_DIR/.cookies"

dc() { docker compose --project-directory "$VALIDATION_DIR" -f "$VALIDATION_DIR/compose.yaml" "$@"; }

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }

# reset_env: tear everything down, including volumes, and clear inventory output.
reset_env() {
  dc --profile multi --profile agent down -v --remove-orphans >/dev/null 2>&1 || true
  # down -v only removes volumes attached to services in the current interpolation
  docker volume rm -f validation_home-a validation_home-b validation_home-shared >/dev/null 2>&1 || true
  rm -rf "$OUT_DIR"; mkdir -p "$OUT_DIR"
}

# wait_up <port> [timeout-seconds]: until Jenkins answers authenticated API calls.
wait_up() {
  local port=$1 timeout=${2:-300} start=$SECONDS
  until curl -gsf -u "$AUTH" "http://localhost:$port/api/json" >/dev/null 2>&1; do
    (( SECONDS - start > timeout )) && { log "Jenkins on $port not up after ${timeout}s"; return 1; }
    sleep 2
  done
  log "Jenkins on $port is up ($((SECONDS - start))s)"
}

_crumb() {
  local port=$1
  curl -sf -u "$AUTH" -c "$COOKIES.$port" "http://localhost:$port/crumbIssuer/api/json" \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["crumbRequestField"]+":"+d["crumb"])'
}

# post <port> <path> [curl args...]: authenticated POST with crumb.
post() {
  local port=$1 path=$2; shift 2
  local crumb; crumb=$(_crumb "$port")
  curl -sf -u "$AUTH" -b "$COOKIES.$port" -H "$crumb" -X POST "http://localhost:$port$path" "$@"
}

# groovy <port> <script>: run a script console script, print its output.
groovy() {
  local port=$1 script=$2
  post "$port" /scriptText --data-urlencode "script=$script"
}

# create_job <port> <name> <config.xml path>
create_job() {
  post "$1" "/createItem?name=$2" -H 'Content-Type: application/xml' --data-binary "@$3" >/dev/null
}

# build <port> <job>: trigger and print the queue item URL.
build() {
  post "$1" "/job/$2/build" -o /dev/null -D - | awk -F': ' 'tolower($1)=="location"{print $2}' | tr -d '\r'
}

# wait_build <port> <job> <number> [timeout]: until the build is finished; print result.
wait_build() {
  local port=$1 job=$2 n=$3 timeout=${4:-300} start=$SECONDS r
  while :; do
    r=$(curl -gsf -u "$AUTH" "http://localhost:$port/job/$job/$n/api/json?tree=building,result" 2>/dev/null || true)
    if [[ -n "$r" ]] && python3 -c 'import json,sys; d=json.loads(sys.argv[1]); sys.exit(0 if not d["building"] and d["result"] else 1)' "$r"; then
      python3 -c 'import json,sys; print(json.loads(sys.argv[1])["result"])' "$r"; return 0
    fi
    (( SECONDS - start > timeout )) && { echo TIMEOUT; return 1; }
    sleep 2
  done
}

# mark <name>: record a phase marker (epoch seconds, ms) used to slice inotify logs.
mark() { printf '%.3f %s\n' "$(python3 -c 'import time; print(time.time())')" "$1" >> "$OUT_DIR/markers.log"; log "phase: $1"; }

# agent_secret <port> <node>
agent_secret() {
  groovy "$1" "println(jenkins.model.Jenkins.get().getComputer('$2').getJnlpMac())" | tr -d '\r\n'
}
