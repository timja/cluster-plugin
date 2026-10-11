#!/bin/bash
# Shared helpers for scenarios and experiments. Source from validation/.
set -euo pipefail

VALIDATION_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$VALIDATION_DIR")"
RAW_DIR="$ROOT_DIR/findings/raw"
OUT_DIR="$VALIDATION_DIR/out"
# API token provisioned on every controller by jenkins/init.groovy.d/cnj-admin-token.groovy:
# token auth needs no crumbs or session cookies, so calls are stateless and safe to run concurrently.
CNJ_ADMIN_TOKEN=${CNJ_ADMIN_TOKEN:-1100000000000000000000000000c0ffee}
AUTH="admin:$CNJ_ADMIN_TOKEN"
# password auth, only for experiments about sessions and crumbs (E5)
PASSWORD_AUTH="admin:admin"

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
    (( SECONDS - start > timeout )) && {
      # why: not answering at all, still starting (503), or up but the token is not accepted (401)
      log "Jenkins on $port not up after ${timeout}s (/login $(curl -gs -m 5 -o /dev/null -w '%{http_code}' "http://localhost:$port/login"), authenticated /api/json $(curl -gs -m 5 -o /dev/null -w '%{http_code}' -u "$AUTH" "http://localhost:$port/api/json"))"
      boot_diagnostics "$port"; return 1; }
    sleep 2
  done
  log "Jenkins on $port is up ($((SECONDS - start))s)"
}

# wait_postgres [container]: until the harness's Postgres accepts connections (a fresh volume initialises, then restarts)
wait_postgres() {
  local c=${1:-et1-postgres-1} start=$SECONDS
  until docker exec "$c" pg_isready -U jenkins >/dev/null 2>&1; do
    (( SECONDS - start > 120 )) && { log "Postgres in $c not ready after 120s"; return 1; }
    sleep 1
  done
  sleep 1 # pg_isready answers during the init restart too
  until docker exec "$c" pg_isready -U jenkins >/dev/null 2>&1; do sleep 1; done
}

# thread_dump <container>: a thread dump of the Jenkins JVM in a container
thread_dump() {
  docker exec "$1" sh -c 'jcmd $(pgrep -f jenkins.war | head -1) Thread.print 2>/dev/null' 2>&1
}

# On a boot timeout: the container's log and a thread dump of its JVM, the other controllers' thread dumps, and what
# Postgres is doing, to $RAW_DIR/boot-timeout-<port>-<time>.txt
boot_diagnostics() {
  local port=$1 c out other
  c=$(docker ps --filter "publish=$port" --format '{{.Names}}' | head -1)
  [[ -n "$c" ]] || return 0
  out="$RAW_DIR/boot-timeout-$port-$(date +%H%M%S).txt"
  mkdir -p "$RAW_DIR"
  { echo "== docker logs $c"; docker logs "$c" 2>&1 | tail -300
    echo "== thread dump of $c"; thread_dump "$c"
    for other in $(docker ps --format '{{.Names}}' | grep -E 'jenkins-[a-z]-1$' | grep -v "^$c$"); do
      echo "== thread dump of $other"; thread_dump "$other"
    done
    for pg in $(docker ps --format '{{.Names}}' | grep -E 'postgres-1$'); do
      echo "== $pg activity"; docker exec "$pg" psql -U jenkins -At -c "select pid, state, wait_event_type, wait_event, now() - query_start, left(query, 200) from pg_stat_activity where datname = 'jenkins'" 2>&1
      echo "== $pg locks"; docker exec "$pg" psql -U jenkins -At -c "select l.pid, l.locktype, l.mode, l.granted, left(a.query, 120) from pg_locks l join pg_stat_activity a on a.pid = l.pid where a.datname = 'jenkins' and not l.granted" 2>&1
    done; } > "$out" 2>&1
  log "boot diagnostics in $out"
}

# post <port> <path> [curl args...]: authenticated POST (API token, so no crumb).
post() {
  local port=$1 path=$2; shift 2
  curl -sf -u "$AUTH" -X POST "http://localhost:$port$path" "$@"
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

# Namespaces on the kind cluster that experiments create are labelled, and only those are ever deleted: a namespace
# someone else made (e.g. a release installed by hand to try things) is left alone and the experiment stops.
# claim_ns <kube-context> <namespace>
claim_ns() {
  local ctx=$1 ns=$2 owner
  if kubectl --context "$ctx" get namespace "$ns" >/dev/null 2>&1; then
    owner=$(kubectl --context "$ctx" get namespace "$ns" -o jsonpath='{.metadata.labels.cnj-validation}')
    if [[ "$owner" != experiment ]]; then
      echo "namespace $ns exists and was not created by an experiment; not touching it (delete it yourself, or use another one)" >&2
      return 1
    fi
    kubectl --context "$ctx" delete namespace "$ns" --wait=true >/dev/null 2>&1
  fi
  kubectl --context "$ctx" create namespace "$ns" >/dev/null && kubectl --context "$ctx" label namespace "$ns" cnj-validation=experiment >/dev/null
}
