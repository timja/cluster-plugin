#!/bin/bash
# E5: two controllers (shared home, so same users + secrets) behind a NON-sticky round-robin LB.
# What breaks for browsers, API clients and agents?
source "$(dirname "$0")/../lib.sh"
A=8081 B=8082 LB=8080 OUT="$RAW_DIR/E5"
rm -rf "$OUT"; mkdir -p "$OUT"
reset_env
export HOME_A=home-shared HOME_B=home-shared
r() { echo "$*" | tee -a "$OUT/results.md" >&2; }
: > "$OUT/results.md"
dc up -d postgres azurite jenkins-a >/dev/null 2>&1; wait_up $A
dc --profile multi up -d jenkins-b lb >/dev/null 2>&1; wait_up $B; wait_up $LB
r "## E5 results"

ups() { awk -F': ' 'tolower($1)=="x-upstream"{print $2}' "$1" | tr -d '\r' | cut -d: -f1; }
spacer() { curl -s -o /dev/null "http://localhost:$LB/login"; }  # shifts round-robin parity

r "### 1. Browser session: form login through the LB (login form carries a session-bound crumb)"
r "| attempt | /login served by | POST served by | login result | later page loads authenticated |"
r "|---|---|---|---|---|"
CJ="$OUT_DIR/lb.cookies"
for i in $(seq 1 6); do
  rm -f "$CJ"; (( i % 2 == 0 )) && spacer
  crumb=$(curl -s -c "$CJ" -b "$CJ" -D "$OUT_DIR/h1" "http://localhost:$LB/login" | grep -o 'Jenkins-Crumb" type="hidden" value="[^"]*"' | sed 's/.*value="//; s/"$//' || true)
  curl -s -c "$CJ" -b "$CJ" -D "$OUT_DIR/h2" -o /dev/null --data-urlencode "Jenkins-Crumb=$crumb" \
    --data 'j_username=admin&j_password=admin&from=%2F' "http://localhost:$LB/j_spring_security_check"
  loc=$(awk -F': ' 'tolower($1)=="location"{print $2}' "$OUT_DIR/h2" | tr -d '\r')
  ok=0; for k in $(seq 1 6); do curl -s -b "$CJ" "http://localhost:$LB/whoAmI/api/json" | grep -q '"name":"admin"' && ok=$((ok+1)); done
  r "| $i | $(ups "$OUT_DIR/h1") | $(ups "$OUT_DIR/h2") | redirect to \`$loc\` | $ok/6 |"
done

r "### 2. Crumb obtained via LB, then POST via LB (password auth + session cookie + crumb)"
r "| attempt | crumb issued by | POST served by | HTTP |"
r "|---|---|---|---|"
for i in $(seq 1 6); do
  rm -f "$CJ.2"; (( i % 2 == 0 )) && spacer
  crumb=$(curl -s -u "$PASSWORD_AUTH" -c "$CJ.2" -D "$OUT_DIR/h1" "http://localhost:$LB/crumbIssuer/api/json" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["crumbRequestField"]+":"+d["crumb"])')
  code=$(curl -s -o /dev/null -D "$OUT_DIR/h2" -w '%{http_code}' -u "$PASSWORD_AUTH" -b "$CJ.2" -H "$crumb" -X POST "http://localhost:$LB/scriptText" --data-urlencode 'script=print 1')
  r "| $i | $(ups "$OUT_DIR/h1") | $(ups "$OUT_DIR/h2") | $code |"
done

r "### 3. API token created on A after both controllers booted"
TOKEN=$(groovy $A 'def u=hudson.model.User.getById("admin",false); def t=u.getProperty(jenkins.security.ApiTokenProperty).tokenStore.generateNewToken("e5"); u.save(); print(t.plainValue)')
r "- token on A: $(curl -s -o /dev/null -w '%{http_code}' -u "admin:$TOKEN" "http://localhost:$A/whoAmI/api/json")"
r "- token on B: $(curl -s -o /dev/null -w '%{http_code}' -u "admin:$TOKEN" "http://localhost:$B/whoAmI/api/json")"
codes=""; for i in $(seq 1 6); do codes+="$(curl -s -o /dev/null -w '%{http_code}' -u "admin:$TOKEN" "http://localhost:$LB/whoAmI/api/json") "; done
r "- token via LB x6: $codes"
r "- POST via LB with API token (no crumb needed): $(for i in 1 2 3 4; do curl -s -o /dev/null -w '%{http_code} ' -u "admin:$TOKEN" -X POST "http://localhost:$LB/scriptText" --data-urlencode 'script=print 1'; done)"

r "### 4. Inbound WebSocket agent connecting through the LB"
SECRET=$(agent_secret $A agent1)
r "- agent secret identical on A and B (HMAC of shared master.key): $([[ "$SECRET" == "$(agent_secret $B agent1)" ]] && echo yes || echo no)"
AGENT_SECRET=$SECRET AGENT_JENKINS_URL=http://lb:80/ dc --profile agent up -d agent >/dev/null 2>&1
sleep 30
r "- agent1 online on A: $(groovy $A 'print(jenkins.model.Jenkins.get().getComputer("agent1").isOnline())'), on B: $(groovy $B 'print(jenkins.model.Jenkins.get().getComputer("agent1").isOnline())')"
r '```'; dc logs --no-color agent 2>&1 | grep -E 'INFO: (Connected|Agent discovery|Handshaking|Remote identity)|SEVERE|rejected|Failed' | tail -8 | tee -a "$OUT/results.md" >&2; r '```'
