#!/bin/bash
# ET22: acceptance experiment for Target 15 item 27 (plugin files in user directories): the avatar plugin keeps a
# user's image next to the user's config.xml, written with file APIs. With the opt-in sharing of plugin files
# (ItemFiles), an avatar set on A is served by B, and by B again after it is replaced by a fresh controller.
# ET setup (two tmpfs controllers, S0, Postgres, Azurite, token auth); the avatar plugin from the update center.
# Exits non-zero if any check fails.
#
# Prerequisite: CORE_REF=cnj/t15-remaining experiments/ET1-build.sh
source "$(dirname "$0")/../lib.sh"
export CNJ_JAVA_OPTS="-Djenkins.persistence.ItemFiles.enabled=true -Djenkins.persistence.ItemFiles.periodSeconds=5"
A=8081 B=8082 OUT="$RAW_DIR/ET22" ET1="$VALIDATION_DIR/.et1"
et1() { docker compose --project-directory "$VALIDATION_DIR" -f "$VALIDATION_DIR/compose.et1.yaml" "$@"; }
rm -rf "$OUT"; mkdir -p "$OUT" "$OUT_DIR"
: > "$OUT/results.md"
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
FAILED=0
check() { local d=$1; shift; if "$@" >>"$OUT/check.log" 2>&1; then r "| ✅ | $d |"; else r "| ❌ | $d |"; FAILED=$((FAILED+1)); fi; }
info() { r "| ℹ️ | $* |"; }
eventually() { local t=$1 start=$SECONDS; shift; until "$@"; do (( SECONDS - start > t )) && return 1; sleep 2; done; }
trap 'et1 logs jenkins-a > "$OUT/jenkins-a.log" 2>&1; et1 logs jenkins-b > "$OUT/jenkins-b.log" 2>&1' EXIT
# the image a controller serves for admin's avatar, into a file; true if it is the one set on A
serves_avatar() { # port
  curl -gsf -u "$AUTH" -o "$OUT/avatar-$1.png" "http://localhost:$1/user/admin/avatar/image" && cmp -s "$OUT/avatar-$1.png" "$OUT_DIR/avatar.png"
}

[[ -f "$ET1/war/jenkins.war" ]] || { echo "run ET1-build.sh first" >&2; exit 2; }

# ------------------------------------------------------------------ setup (as ET6)
et1 down -v --remove-orphans >/dev/null 2>&1 || true
rm -rf "$ET1/seed"; mkdir -p "$ET1/seed"
docker rm -f et1-seed >/dev/null 2>&1 || true
docker run -d --name et1-seed -p 8089:8080 --tmpfs /var/jenkins_home:uid=1000,gid=1000 \
  -e JAVA_OPTS="-Djenkins.install.runSetupWizard=false" -e CASC_JENKINS_CONFIG=/casc -v "$VALIDATION_DIR/jenkins/casc-et1:/casc:ro" \
  -v "$ET1/war:/war-override:ro" -v "$ET1/plugins:/extra-plugins:ro" cnj/jenkins:dev >/dev/null
wait_up 8089
groovy 8089 'hudson.util.Secret.fromString("x").getEncryptedValue(); org.jenkinsci.main.modules.instance_identity.InstanceIdentity.get()' >/dev/null
docker exec et1-seed tar -C /var/jenkins_home -cf - secret.key secret.key.not-so-secret identity.key.enc secrets | tar -C "$ET1/seed" -xf -
docker rm -f et1-seed >/dev/null
et1 up -d postgres azurite >/dev/null 2>&1; wait_postgres
et1 up -d jenkins-a jenkins-b >/dev/null 2>&1
wait_up $A; wait_up $B
r "## ET22 results ($(date -u +%Y-%m-%dT%H:%MZ))"
r ""
r "| | Check |"
r "|---|---|"

# a small PNG (8x8, one colour)
python3 - "$OUT_DIR/avatar.png" <<'PY'
import struct, sys, zlib
def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
raw = b"".join(b"\x00" + b"\x20\x80\xc0" * 8 for _ in range(8))
open(sys.argv[1], "wb").write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 8, 8, 8, 2, 0, 0, 0))
    + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))
PY

# ------------------------------------------------------------------ 1. an avatar set on A, served by B
log "scenario 1: admin's avatar set on A"
b64=$(base64 < "$OUT_DIR/avatar.png" | tr -d '\n')
# what the plugin's upload does (AvatarProperty.reconfigure): the image written next to the user's config.xml with file
# APIs, and the property (its type) saved with the user
groovy $A "
def bytes = '$b64'.decodeBase64()
def u = hudson.model.User.getById('admin', true)
def img = net.hurstfrost.jenkins.avatar.user.AvatarProperty.AvatarImage.fromBytes(bytes)
new File(u.getUserFolder(), 'avatar.' + img.filenameSuffix).bytes = bytes
def p = new net.hurstfrost.jenkins.avatar.user.AvatarProperty()
p.avatarImage = img
u.addProperty(p)
println img.filenameSuffix" > "$OUT/set-avatar.txt" 2>&1
info "1 set on A: avatar.$(tr -d '\r\n ' < "$OUT/set-avatar.txt")"
check "1 A serves it" serves_avatar $A
check "1 B serves the same image (shared within 60 s)" eventually 60 serves_avatar $B
check "1 ... and its API names an avatar" bash -c "curl -gsf -u '$AUTH' 'http://localhost:$B/user/admin/api/json?depth=1' | grep -q 'avatar/image'"

# ------------------------------------------------------------------ 2. B replaced by a fresh controller
log "scenario 2: B replaced by a fresh controller (empty home)"
et1 rm -sf jenkins-b >/dev/null 2>&1; et1 up -d jenkins-b >/dev/null 2>&1; wait_up $B
check "2 the fresh B serves it" eventually 60 serves_avatar $B

# ------------------------------------------------------------------ 3. changed on B, then removed on B
log "scenario 3: changed on B"
python3 - "$OUT_DIR/avatar.png" <<'PY'
import struct, sys, zlib
def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
raw = b"".join(b"\x00" + b"\xc0\x20\x20" * 8 for _ in range(8))
open(sys.argv[1], "wb").write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 8, 8, 8, 2, 0, 0, 0))
    + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))
PY
b64=$(base64 < "$OUT_DIR/avatar.png" | tr -d '\n')
groovy $B "new File(hudson.model.User.getById('admin', false).getUserFolder(), 'avatar.png').bytes = '$b64'.decodeBase64()" >/dev/null
check "3 A serves the new image" eventually 60 serves_avatar $A

r ""
r "Failures: $FAILED"
exit $(( FAILED > 0 ))
