#!/bin/bash
# Runs experiments against the S3 variant of the harness: the same controllers and Postgres, with the cluster's blobs in
# Garage (an S3-compatible store) through cluster-s3 instead of Azurite through cluster-azure-blob, and artifacts through
# artifact-manager-s3.
#
#   experiments/with-s3.sh ET3-build-records ET4-pipeline-failover ...
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
export COMPOSE_PROFILES="${COMPOSE_PROFILES:+$COMPOSE_PROFILES,}s3"
export CLUSTER_PLUGINS_DIR=./.et1/plugins-s3
export CNJ_CASC_DIR=./jenkins/casc-s3
export CLUSTER_S3_BUCKET=jenkins CLUSTER_S3_ENDPOINT=http://garage:3900 CLUSTER_S3_REGION=garage CLUSTER_S3_PATH_STYLE=true
export CLUSTER_S3_ACCESS_KEY=GKcluster0test0key CLUSTER_S3_SECRET_KEY=cluster-test-secret-key-0123456789
rc=0
for e in "$@"; do
  "$HERE/$e.sh" || rc=$?
done
exit $rc
