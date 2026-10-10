#!/bin/bash
# E9: which job/item state is written by *build activity* (not by an admin)? Multibranch indexing
# creates and deletes jobs; the properties() step rewrites job config on every build.
source "$(dirname "$0")/../lib.sh"
J=8081 JOBS="$VALIDATION_DIR/scenarios/jobs" OUT="$RAW_DIR/E9"
rm -rf "$OUT"; mkdir -p "$OUT"
reset_env
export CNJ_JAVA_OPTS="-Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true"
CNJ_INOTIFY=1 dc up -d postgres azurite jenkins-a >/dev/null 2>&1; wait_up $J
mark booted
sh_a() { dc exec -T jenkins-a bash -c "$1"; }
sh_a 'set -e; git config --global user.email e9@example.com; git config --global user.name e9; git config --global init.defaultBranch main
  rm -rf /tmp/repo; git init -q /tmp/repo; cd /tmp/repo
  cat > Jenkinsfile <<JF
properties([
  parameters([string(name: "GREETING", defaultValue: "hi")]),
  buildDiscarder(logRotator(numToKeepStr: "5")),
  pipelineTriggers([cron("H H * * *")])
])
echo "branch \${env.BRANCH_NAME} says \${params.GREETING}"
JF
  git add Jenkinsfile; git commit -qm init; git branch feature'
mark repo-ready

wait_idle() { sleep 5; until [[ "$(curl -gsf -u "$AUTH" "http://localhost:$J/queue/api/json?tree=items[id]" | grep -c '"id"')" == 0 && \
  "$(curl -gsf -u "$AUTH" "http://localhost:$J/computer/api/json?tree=busyExecutors" | grep -o '"busyExecutors":[0-9]*' | cut -d: -f2)" == 0 ]]; do sleep 2; done; }
jobs_tree() { curl -gsf -u "$AUTH" "http://localhost:$J/job/mb/api/json?tree=jobs[name,nextBuildNumber]"; }

create_job $J mb "$JOBS/multibranch.xml"; mark mb-created
post $J /job/mb/build >/dev/null; wait_idle; mark indexed-and-built
echo "after first index: $(jobs_tree)" >> "$OUT/jobs.txt"
post $J "/job/mb/job/main/buildWithParameters?GREETING=again" >/dev/null; wait_idle; mark rebuilt-main
sh_a 'cd /tmp/repo && git branch feature2'
post $J /job/mb/build >/dev/null; wait_idle; mark branch-added
echo "after branch added: $(jobs_tree)" >> "$OUT/jobs.txt"
sh_a 'cd /tmp/repo && git branch -D feature'
post $J /job/mb/build >/dev/null; wait_idle; mark branch-deleted
echo "after branch deleted: $(jobs_tree)" >> "$OUT/jobs.txt"

cp "$OUT_DIR/markers.log" "$OUT/"; cp "$OUT_DIR/jenkins-a.inotify.log" "$OUT/inotify.log"
dc logs -t --no-color jenkins-a > "$OUT/jenkins.log" 2>&1
sh_a 'cd /var/jenkins_home/jobs && find . -type f | sort' > "$OUT/jobs-files.txt"
sh_a 'cat /var/jenkins_home/jobs/mb/branches/main/config.xml' > "$OUT/main-config.xml" 2>&1 || true
python3 -I - "$OUT" > "$OUT/writes-by-phase.md" <<'PY'
import re, sys
from pathlib import Path
out = Path(sys.argv[1])
marks = [(float(l.split(" ", 1)[0]), l.split(" ", 1)[1]) for l in (out / "markers.log").read_text().splitlines()]
def phase(t):
    # markers are dropped once an action completes, so a write belongs to the next marker
    for mt, n in marks:
        if t < mt: return n
    return "after last marker"
writes = {}
for line in (out / "inotify.log").read_text(errors="replace").splitlines():
    try: t, ev, p = line.split(" ", 2)
    except ValueError: continue
    if "/var/jenkins_home/jobs/" not in p or "ISDIR" in ev: continue
    if not set(ev.split(",")) & {"MOVED_TO", "MODIFY", "CREATE", "DELETE"}: continue
    rel = re.sub(r"-atomic\d+tmp$|atomic\d+\.tmp$", "", p.split("/var/jenkins_home/", 1)[1])
    rel = re.sub(r"/builds/\d+/", "/builds/N/", rel)
    kind = "DELETE" if "DELETE" in ev else "write"
    writes.setdefault(phase(float(t)), set()).add((rel, kind))
print("# E9 writes under jobs/ by action (writes that happened while the action ran)\n")
for mt, n in marks:
    if n in writes:
        print(f"## {n}\n")
        for rel, kind in sorted(writes[n]):
            print(f"- {'~~' if kind == 'DELETE' else ''}`{rel}`{'~~ (deleted)' if kind == 'DELETE' else ''}")
        print()
PY
cat "$OUT/jobs.txt" >&2
