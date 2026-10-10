#!/bin/bash
# Restores a backup made by backup.sh into a new release (Target 10): installs it with no controllers, loads the database
# and the blobs into the empty services, then starts the controllers.
# usage: restore.sh <namespace> <release> <directory> <chart> [helm args...]
set -euo pipefail
ns=$1 rel=$2 dir=$3 chart=$4; shift 4
helm -n "$ns" install "$rel" "$chart" --set controllers=0 "$@" >/dev/null
kubectl -n "$ns" rollout status "statefulset/$rel-postgres" --timeout=180s >/dev/null
kubectl -n "$ns" rollout status "statefulset/$rel-azurite" --timeout=180s >/dev/null
until kubectl -n "$ns" exec "$rel-postgres-0" -- pg_isready -U jenkins >/dev/null 2>&1; do sleep 2; done
kubectl -n "$ns" exec -i "$rel-postgres-0" -- pg_restore -U jenkins -d jenkins --no-owner < "$dir/jenkins.dump"
pod="$rel-azurite-0"
kubectl -n "$ns" exec -i "$pod" -- tar -C /data -xzf - < "$dir/blobs.tgz"
kubectl -n "$ns" delete pod "$pod" --wait=true >/dev/null  # Azurite reads its location at startup (the volume stays)
kubectl -n "$ns" rollout status "statefulset/$rel-azurite" --timeout=180s >/dev/null
helm -n "$ns" upgrade "$rel" "$chart" --reuse-values --set controllers="${CONTROLLERS:-2}" >/dev/null
echo "restored $dir into $ns/$rel"
