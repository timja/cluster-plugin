#!/usr/bin/env python3
"""Classify JENKINS_HOME inotify events (from entrypoint.sh) into state categories.

usage: analyze_inventory.py <raw-dir> [home=/var/jenkins_home]
Reads <raw-dir>/inotify.log, markers.log, jenkins.log; prints a markdown report.
"""
import re
import sys
from collections import defaultdict
from datetime import datetime
from pathlib import Path

raw = Path(sys.argv[1])
home = (sys.argv[2] if len(sys.argv) > 2 else "/var/jenkins_home").rstrip("/") + "/"

# Ordered: first match wins. (regex on home-relative path, category, externalisation tier)
CATEGORIES = [
    (r"^war/", "Extracted webapp (war/)", "immutable"),
    (r"^plugins/", "Plugin binaries (plugins/)", "immutable"),
    (r"^(\.cache|\.java|\.groovy)/", "JVM/OS caches", "ephemeral"),
    (r"^(\.lastStarted|\.owner|copy_reference_file\.log|plugins\.txt|jenkins\.install\.|failed-boot-attempts\.txt)", "Install/boot markers", "ephemeral"),
    (r"^(secrets/|secret\.key|identity\.key)", "Secrets & instance identity", "T1"),
    (r"^config\.xml$", "Global config: Jenkins config.xml", "T1"),
    (r"^credentials\.xml$", "System credentials store", "T2"),
    (r"^queue\.xml", "Queue persistence", "T5"),
    (r"^org\.jenkinsci\.plugins\.workflow\.flow\.FlowExecutionList\.xml$", "Running Pipeline index", "T4"),
    (r"^(updates/|hudson\.model\.UpdateCenter\.xml)", "Update center cache", "ephemeral"),
    (r"^[^/]+\.xml$", "Global config: descriptor/plugin XML", "T1"),
    (r"^jobs/.*?/builds/\d+/(workflow|workflow-completed|program\.dat)", "Pipeline runtime state (flow nodes, program.dat)", "T4"),
    (r"^jobs/.*?/builds/\d+/(log|log-index|log\.gz)$", "Build logs", "T3"),
    (r"^jobs/.*?/builds/\d+/(archive|stashes)/", "Artifacts & stashes (file manager)", "T3"),
    (r"^jobs/.*?/builds/\d+/(build\.xml|changelog.*\.xml)$", "Build records", "T3"),
    (r"^jobs/.*?/builds/\d+/", "Other per-build files (plugin data)", "T3"),
    (r"^jobs/.*?/builds/(permalinks|legacyIds)$", "Build index (permalinks)", "T3"),
    (r"^jobs/.*?/builds/?$", "Build index (permalinks)", "T3"),
    (r"^jobs/.*?/nextBuildNumber$", "Build numbering", "T3"),
    (r"^jobs/.*?/config\.xml$", "Item config (jobs/folders)", "T2"),
    (r"^jobs/", "Item directories (other)", "T2"),
    (r"^fingerprints/", "Fingerprints", "T3"),
    (r"^nodes/", "Node config", "T2"),
    (r"^users/", "Users (config, API tokens, id mapping)", "T2"),
    (r"^logs/", "Task logs (agents, tasks)", "T3"),
    (r"^workspace/", "Built-in node workspaces", "T6"),
    (r"^(userContent|caches)/", "userContent / caches", "T6"),
]
WRITE = {"CREATE", "MODIFY", "MOVED_TO", "DELETE"}


def categorise(rel):
    for rx, cat, tier in CATEGORIES:
        if re.search(rx, rel):
            return cat, tier
    return "Uncategorised", "?"


def milestones():
    out = []
    for line in (raw / "jenkins.log").read_text(errors="replace").splitlines():
        m = re.search(r"(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\+0000 .*onAttained: (.*)$", line)
        if m:
            ts = datetime.strptime(m.group(1) + "+0000", "%Y-%m-%d %H:%M:%S.%f%z").timestamp()
            out.append((ts, "init: " + m.group(2).strip()))
    return out


markers = []
for line in (raw / "markers.log").read_text().splitlines():
    t, name = line.split(" ", 1)
    markers.append((float(t), name))
phases = sorted(markers + milestones())


def phase_of(t):
    cur = "pre-start"
    for pt, name in phases:
        if t >= pt:
            cur = name
        else:
            break
    return cur


stats = defaultdict(lambda: {"read": set(), "write": set(), "first": None, "tier": "?"})
pre_plugin_reads = {"core-only (before Listed all plugins)": set(), "plugin load/start (Listed → Started all plugins)": set()}
# Windows from JVM start (approximated by the previous phase marker) to "Started all plugins", per boot.
windows, last = [], phases[0][0]
for t, n in phases:
    if n == "init: Started initialization":
        last_start = last
    if n == "init: Listed all plugins":
        prepared = t
    if n == "init: Started all plugins":
        windows.append((last_start, prepared, t))
    if not n.startswith("init:"):
        last = t


def pre_plugins(t):
    for a, p, b in windows:
        if a <= t < p:
            return "core-only (before Listed all plugins)"
        if p <= t < b:
            return "plugin load/start (Listed → Started all plugins)"
    return None

for line in (raw / "inotify.log").read_text(errors="replace").splitlines():
    try:
        t, events, path = line.split(" ", 2)
    except ValueError:
        continue
    if not path.startswith(home):
        continue
    rel = path[len(home):]
    if not rel or "ISDIR" in events:
        continue
    # AtomicFileWriter temp files: attribute to the file being written
    rel = re.sub(r"-atomic\d+tmp$", "", rel)
    if re.search(r"(^|/)atomic\d+\.tmp$", rel):
        rel = re.sub(r"atomic\d+\.tmp$", "build.xml", rel)
    cat, tier = categorise(rel)
    s = stats[cat]
    s["tier"] = tier
    kinds = set(events.split(","))
    norm = re.sub(r"/builds/\d+/", "/builds/N/", re.sub(r"^jobs/[^/]+/", "jobs/*/", rel))
    norm = re.sub(r"^users/[^/]+/", "users/*/", re.sub(r"^fingerprints/.*", "fingerprints/**", norm))
    if kinds & WRITE:
        s["write"].add(norm)
    if "OPEN" in kinds:
        s["read"].add(norm)
        w = pre_plugins(float(t))
        if w and tier not in ("immutable", "ephemeral"):
            pre_plugin_reads[w].add(norm)
    if s["first"] is None:
        s["first"] = phase_of(float(t))

print("## Phases\n")
t0 = phases[0][0]
for t, n in phases:
    print(f"- +{t - t0:7.2f}s {n}")
print("\n## State categories touched\n")
print("| Category | Tier | Paths written | Paths opened | First touched (after marker) | Example paths written |")
print("|---|---|---|---|---|---|")
order = {k: i for i, (_, k, _) in enumerate(CATEGORIES)}
for cat in sorted(stats, key=lambda c: order.get(c, 999)):
    s = stats[cat]
    ex = ", ".join(f"`{p}`" for p in sorted(s["write"])[:4]) if s["tier"] not in ("immutable",) else ""
    print(f"| {cat} | {s['tier']} | {len(s['write'])} | {len(s['read'])} | {s['first']} | {ex} |")
print("\n## Durable state read before `Started all plugins`\n")
print("Core-only reads happen before any plugin class is loaded, so a backend serving them cannot be a plugin.\n")
for w, paths in pre_plugin_reads.items():
    print(f"**{w}:**\n")
    for p in sorted(paths):
        print(f"- `{p}`")
    print()

# Durable state rewritten while a controller restarts against existing state (no user action).
stopped = next((t for t, n in phases if n == "stopped"), None)
restarted = next((t for t, n in phases if n == "restarted"), None)
if stopped and restarted:
    rewritten = set()
    for line in (raw / "inotify.log").read_text(errors="replace").splitlines():
        try:
            t, events, path = line.split(" ", 2)
        except ValueError:
            continue
        if not (stopped <= float(t) <= restarted) or "ISDIR" in events or not path.startswith(home):
            continue
        rel = re.sub(r"-atomic\d+tmp$", "", path[len(home):])
        rel = re.sub(r"atomic\d+\.tmp$", "build.xml", rel)
        cat, tier = categorise(rel)
        if tier in ("immutable", "ephemeral") or not set(events.split(",")) & {"MOVED_TO", "MODIFY", "CREATE"}:
            continue
        rewritten.add(re.sub(r"^users/[^/]+/", "users/*/", rel))
    print("## Durable state rewritten during a plain restart\n")
    print("Every replica booting against shared state would rewrite these (CasC re-apply, migrations, monitors).\n")
    for p in sorted(rewritten):
        print(f"- `{p}`")
