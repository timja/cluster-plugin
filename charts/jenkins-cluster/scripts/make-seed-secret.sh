#!/bin/bash
# Makes the S0 seed Secret (the key material created before plugins load, identical on every controller; findings/E8):
# starts the controller image once in Docker, has it create its keys, and stores them as seed.tgz in a Secret.
# usage: make-seed-secret.sh <image> <namespace> [secret name]
set -euo pipefail
# SEED_MAKER_NAME / SEED_MAKER_PORT: the temporary container's name and host port (another user of the same Docker)
image=$1 ns=$2 name=${3:-cluster-seed} work=$(mktemp -d) maker=${SEED_MAKER_NAME:-cluster-seed-maker} port=${SEED_MAKER_PORT:-18089}
docker rm -f "$maker" >/dev/null 2>&1 || true
# an admin user, so that the image's API token authenticates (no store: this controller only makes keys)
printf 'jenkins:\n  securityRealm:\n    local:\n      users: [{id: admin, password: admin}]\n  authorizationStrategy: loggedInUsersCanDoAnything\n' > "$work/casc.yaml"
chmod 644 "$work/casc.yaml"
docker run -d --name "$maker" -p "$port:8080" -e JAVA_OPTS=-Djenkins.install.runSetupWizard=false \
  -e CASC_JENKINS_CONFIG=/casc/casc.yaml -v "$work/casc.yaml:/casc/casc.yaml:ro" "$image" >/dev/null
trap 'docker rm -f "$maker" >/dev/null 2>&1; rm -rf "$work"' EXIT
token=admin:1100000000000000000000000000c0ffee
until curl -gsf -u "$token" http://localhost:$port/api/json >/dev/null 2>&1; do sleep 2; done
curl -gsf -u "$token" -X POST http://localhost:$port/scriptText \
  --data-urlencode 'script=hudson.util.Secret.fromString("x").getEncryptedValue(); org.jenkinsci.main.modules.instance_identity.InstanceIdentity.get()' >/dev/null
docker exec "$maker" tar -C /var/jenkins_home -czf - secret.key secret.key.not-so-secret identity.key.enc secrets > "$work/seed.tgz"
kubectl -n "$ns" create secret generic "$name" --from-file=seed.tgz="$work/seed.tgz" --dry-run=client -o yaml | kubectl -n "$ns" apply -f - >/dev/null
echo "secret $ns/$name created"
