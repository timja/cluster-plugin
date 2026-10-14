#!/bin/bash
# ET18: acceptance experiment for Target 15 (managed services, point-in-time recovery). Postgres archives its WAL; a base
# backup, then work, then a named restore point T (with a Pipeline running), then more work (a job deleted with its
# builds, another edited, a third created). Postgres is restored to T into a fresh data directory and fresh controllers
# start on it: first with blob storage as it is now (what is missing without blob versioning), then with blob storage
# as it was at T as well (standing in for Azure's blob versioning or point-in-time restore).
# ET setup (two tmpfs controllers, S0, token auth); Postgres and Azurite with volumes (compose.et18.yaml).
# Exits non-zero if any check fails.
#
# Prerequisite: CORE_REF=cnj/t15-remaining experiments/ET1-build.sh
source "$(dirname "$0")/../lib.sh"
A=8081 B=8082 OUT="$RAW_DIR/ET18" ET1="$VALIDATION_DIR/.et1"
FILES=(-f "$VALIDATION_DIR/compose.et1.yaml" -f "$VALIDATION_DIR/compose.et18.yaml")
et1() { docker compose --project-directory "$VALIDATION_DIR" "${FILES[@]}" "$@"; }
rm -rf "$OUT"; mkdir -p "$OUT" "$OUT_DIR"
: > "$OUT/results.md"
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
FAILED=0
check() { local d=$1; shift; if "$@" >>"$OUT/check.log" 2>&1; then r "| ✅ | $d |"; else r "| ❌ | $d |"; FAILED=$((FAILED+1)); fi; }
info() { r "| ℹ️ | $* |"; }
eventually() { local t=$1 start=$SECONDS; shift; until "$@"; do (( SECONDS - start > t )) && return 1; sleep 2; done; }
json() { curl -gsf -u "$AUTH" "http://localhost:$1$2"; }
jq_() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
exists() { [[ "$(curl -gs -o /dev/null -w '%{http_code}' -u "$AUTH" "http://localhost:$1$2/api/json")" == 200 ]]; }
console_has() { json "$1" "$2/consoleText" 2>/dev/null | grep -q -- "$3"; }
result_is() { [[ "$(json "$1" "$2/api/json?tree=result" 2>/dev/null | jq_ 'd["result"]')" == "$3" ]]; }
psql_q() { et1 exec -T postgres psql -U jenkins -At -c "$1"; }
freestyle() { # name command
  printf '%s' "<?xml version='1.1' encoding='UTF-8'?><project><description>$1 as built</description><builders><hudson.tasks.Shell><command>$2</command></hudson.tasks.Shell></builders></project>" > "$OUT_DIR/$1.xml"
  create_job $A "$1" "$OUT_DIR/$1.xml"
}
pipeline() { # name script
  python3 - "$OUT_DIR/$1.xml" "$2" <<'PY'
import sys
from xml.sax.saxutils import escape
open(sys.argv[1], "w").write("""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job"><definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>%s</script><sandbox>true</sandbox></definition></flow-definition>""" % escape(sys.argv[2]))
PY
  create_job $A "$1" "$OUT_DIR/$1.xml"
}
fresh_controllers() {
  et1 rm -sf jenkins-a jenkins-b >/dev/null 2>&1
  et1 up -d --no-deps jenkins-a jenkins-b >/dev/null 2>&1
  wait_up $A 240; wait_up $B 240
}

[[ -f "$ET1/war/jenkins.war" ]] || { echo "run ET1-build.sh first" >&2; exit 2; }

# ------------------------------------------------------------------ setup (as ET6, plus rr)
et1 down -v --remove-orphans >/dev/null 2>&1 || true
docker volume rm -f et1_et18-archive et1_et18-pgdata et1_et18-blobs et1_et18-pgrestore >/dev/null 2>&1 || true
rm -rf "$ET1/seed"; mkdir -p "$ET1/seed"
docker rm -fv et1-seed >/dev/null 2>&1 || true
docker run -d --name et1-seed -p 8089:8080 --tmpfs /var/jenkins_home:uid=1000,gid=1000 \
  -e JAVA_OPTS="-Djenkins.install.runSetupWizard=false" -e CASC_JENKINS_CONFIG=/casc -v "$VALIDATION_DIR/jenkins/casc-et1:/casc:ro" \
  -v "$ET1/war:/war-override:ro" -v "$ET1/plugins:/extra-plugins:ro" cnj/jenkins:dev >/dev/null
wait_up 8089
groovy 8089 'hudson.util.Secret.fromString("x").getEncryptedValue(); org.jenkinsci.main.modules.instance_identity.InstanceIdentity.get()' >/dev/null
docker exec et1-seed tar -C /var/jenkins_home -cf - secret.key secret.key.not-so-secret identity.key.enc secrets | tar -C "$ET1/seed" -xf -
docker rm -fv et1-seed >/dev/null
et1 up -d postgres $BLOB_SERVICE >/dev/null 2>&1; wait_postgres
et1 up -d jenkins-a jenkins-b >/dev/null 2>&1
wait_up $A; wait_up $B
r "## ET18 results ($(date -u +%Y-%m-%dT%H:%MZ))"
r ""
r "| | Check |"
r "|---|---|"

# ------------------------------------------------------------------ 1. a base backup, work, a restore point, more work
log "scenario 1: base backup, then work"
et1 exec -T -u root postgres chown postgres:postgres /archive # the archiver runs as postgres (it retries what failed)
et1 exec -T postgres bash -c 'rm -rf /archive/base && pg_basebackup -U jenkins -D /archive/base -Ft -z -X stream' >/dev/null
psql_q "select pg_switch_wal()" >/dev/null
archived() { [[ $(psql_q "select failed_count = 0 or last_archived_time > last_failed_time from pg_stat_archiver") == t ]]; }
check "1 the WAL is archived" eventually 60 archived
freestyle kept 'echo kept-build-ran'
freestyle doomed 'echo doomed-build-ran'
freestyle edited 'echo edited-build-ran'
for j in kept doomed edited; do post $A "/job/$j/build?delay=0sec" >/dev/null; done
for j in kept doomed edited; do eventually 120 result_is $A /job/$j/1 SUCCESS; done
pipeline longp 'pipeline { agent none; stages { stage("long") { steps { echo "before T"; sleep 120; echo "after T" } } } }'
post $A "/job/longp/build?delay=0sec" >/dev/null
eventually 60 console_has $A /job/longp/1 "before T"
sleep 5 # its save point is shipped
log "restore point T"
psql_q "select pg_create_restore_point('T')" >/dev/null
docker exec et1-azurite-1 tar -C /data -cf - . > "$OUT_DIR/blobs-at-T.tar"
psql_q "select pg_switch_wal()" >/dev/null
info "1 at T: $(psql_q "select count(*) from jenkins_store") keys; blobs $(du -h "$OUT_DIR/blobs-at-T.tar" | cut -f1)"
log "after T: doomed deleted, edited changed, created made"
curl -gsf -u "$AUTH" -X POST "http://localhost:$A/job/doomed/doDelete" -o /dev/null
printf '%s' "<?xml version='1.1' encoding='UTF-8'?><project><description>edited after T</description><builders/></project>" | \
  curl -gsf -u "$AUTH" -X POST "http://localhost:$A/job/edited/config.xml" -H 'Content-Type: application/xml' --data-binary @- -o /dev/null
freestyle created 'echo created-after-T'
sleep 3
psql_q "select pg_switch_wal()" >/dev/null
sleep 8 # archive_timeout
check "1 after T: doomed is gone, created exists" bash -c "! curl -gsf -u '$AUTH' http://localhost:$B/job/doomed/api/json -o /dev/null && curl -gsf -u '$AUTH' http://localhost:$B/job/created/api/json -o /dev/null"

# ------------------------------------------------------------------ 2. Postgres restored to T, blob storage as it is now
log "scenario 2: restore Postgres to T into a fresh data directory"
et1 stop jenkins-a jenkins-b postgres >/dev/null 2>&1
docker run --rm -v et1_et18-archive:/archive -v et1_et18-pgrestore:/r postgres:16 bash -c '
  set -e; rm -rf /r/*; tar -xzf /archive/base/base.tar.gz -C /r; mkdir -p /r/pg_wal; tar -xzf /archive/base/pg_wal.tar.gz -C /r/pg_wal
  echo "restore_command = '"'"'cp /archive/%f %p'"'"'" >> /r/postgresql.auto.conf
  echo "recovery_target_name = '"'"'T'"'"'" >> /r/postgresql.auto.conf
  echo "recovery_target_action = '"'"'promote'"'"'" >> /r/postgresql.auto.conf
  touch /r/recovery.signal; chown -R postgres:postgres /r; chmod 700 /r' > "$OUT/restore.log" 2>&1
FILES=(-f "$VALIDATION_DIR/compose.et1.yaml" -f "$VALIDATION_DIR/compose.et18.yaml" -f "$VALIDATION_DIR/compose.et18-restore.yaml")
et1 up -d --no-deps postgres >/dev/null 2>&1
eventually 120 bash -c "cd $VALIDATION_DIR && docker compose ${FILES[*]} exec -T postgres psql -U jenkins -At -c 'select not pg_is_in_recovery()' 2>/dev/null | grep -q t"
check "2 Postgres recovered to T and was promoted" bash -c "docker logs et1-postgres-1 2>&1 | grep -q 'recovery stopping at restore point \"T\"'"
pglog=$(docker logs et1-postgres-1 2>&1) # whole, first (pipefail with an early grep exit)
if ! grep -q 'recovery stopping at restore point "T"' <<<"$pglog"; then
  et1 logs postgres > "$OUT/postgres.log" 2>&1; r ""; r "Failures: $FAILED (stopped: no recovery to T)"; exit 1
fi
fresh_controllers
check "2 doomed is back (its configuration and records, as at T)" eventually 60 exists $B /job/doomed
check "2 created is not there (made after T)" bash -c "! curl -gsf -u '$AUTH' http://localhost:$B/job/created/api/json -o /dev/null"
check "2 edited has its configuration as at T" bash -c "curl -gsf -u '$AUTH' http://localhost:$B/job/edited/config.xml | grep -q 'edited as built'"
check "2 kept #1 is there, with its console" eventually 60 console_has $B /job/kept/1 kept-build-ran
doomed_console=$(curl -gs -o /dev/null -w '%{http_code}' -u "$AUTH" "http://localhost:$B/job/doomed/1/consoleText")
info "2 without blob versioning: doomed #1's record is back, its console answers $doomed_console (its snapshot was deleted after T)"
doomed_gone() { ! console_has $B /job/doomed/1 doomed-build-ran; }
check "2 ... which is what blob versioning is needed for: doomed #1's snapshot is missing" doomed_gone
lp=$(json $B /job/longp/1/api/json?tree=building,result 2>/dev/null | jq_ 'd.get("result") or ("building" if d.get("building") else "?")' 2>/dev/null || echo "?")
info "2 longp #1 (running at T): $lp"

# ------------------------------------------------------------------ 3. blob storage restored to T as well
log "scenario 3: blob storage restored to T as well"
et1 stop jenkins-a jenkins-b azurite >/dev/null 2>&1
docker run --rm -i -v et1_et18-blobs:/data postgres:16-alpine sh -c 'rm -rf /data/* /data/.[!.]* 2>/dev/null; tar -C /data -xf -' < "$OUT_DIR/blobs-at-T.tar"
et1 up -d --no-deps azurite >/dev/null 2>&1; sleep 3
fresh_controllers
check "3 doomed #1's console is back" eventually 60 console_has $B /job/doomed/1 doomed-build-ran
lp_done() { result_is $B /job/longp/1 SUCCESS || result_is $B /job/longp/1 ABORTED; }
check "3 the Pipeline running at T is resumed from its save point (or closed as aborted), not lost" eventually 240 lp_done
info "3 longp #1: $(json $B /job/longp/1/api/json?tree=result 2>/dev/null | jq_ 'd.get("result")' 2>/dev/null)"
check "3 its console has what it printed before T" console_has $B /job/longp/1 "before T"

et1 logs jenkins-a > "$OUT/jenkins-a.log" 2>&1; et1 logs jenkins-b > "$OUT/jenkins-b.log" 2>&1; et1 logs postgres > "$OUT/postgres.log" 2>&1
cp "$ET1/BUILD" "$OUT/BUILD" 2>/dev/null || true
r ""
r "Failures: $FAILED"
exit $(( FAILED > 0 ))
