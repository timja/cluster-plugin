#!/bin/bash
# Backs up a cloud-native Jenkins release with in-chart Postgres and Azurite (Target 10): the database first, then the blob
# container. Blobs are written before the records referring to them, so a dump taken first never refers to a missing blob.
# usage: backup.sh <namespace> <release> <directory>
set -euo pipefail
ns=$1 rel=$2 dir=$3
mkdir -p "$dir"
kubectl -n "$ns" exec "$rel-postgres-0" -- pg_dump -U jenkins -Fc jenkins > "$dir/jenkins.dump"
pod="$rel-azurite-0"
kubectl -n "$ns" exec "$pod" -- tar -C /data -czf - . > "$dir/blobs.tgz"
echo "backed up $ns/$rel to $dir: $(du -h "$dir/jenkins.dump" | cut -f1) database, $(du -h "$dir/blobs.tgz" | cut -f1) blobs"
