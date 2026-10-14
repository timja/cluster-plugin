#!/bin/bash
# Builds cnj/jenkins:k8s for Target 9 from the outputs of ET1-build.sh, plus the kubernetes plugin built from
# ../kubernetes-plugin-cnj (cnj/t9-m2-cluster-cap), and loads it and the other images into the kind cluster.
source "$(dirname "$0")/../lib.sh"
ET1="$VALIDATION_DIR/.et1" K8S=${K8S_PLUGIN:-$(cd "$VALIDATION_DIR/../.." && pwd)/kubernetes-plugin-cnj} CLUSTER=${KIND_CLUSTER:-cnj}
[[ -f "$ET1/war/jenkins.war" ]] || { echo "run ET1-build.sh first" >&2; exit 2; }
# kept out of $ET1/plugins, which the compose controllers mount: there it would fail to load without its dependencies
rm -f "$ET1/plugins/kubernetes.hpi"; mkdir -p "$ET1/k8s-plugins"
if [[ -d "$K8S" ]]; then
  (cd "$K8S" && mvn -B -ntp -q -Dspotbugs.skip -Dcheckstyle.skip -Denforcer.skip -Dlicense.skip=true -Djacoco.skip -Dspotless.check.skip -DskipTests package) || exit 3
  cp "$K8S/target/kubernetes.hpi" "$ET1/k8s-plugins/kubernetes.hpi"
  echo "kubernetes $(git -C "$K8S" rev-parse --abbrev-ref HEAD) $(git -C "$K8S" rev-parse HEAD)" >> "$ET1/BUILD"
else
  # another machine running a harness built elsewhere: the plugin was built with the rest of .et1
  [[ -f "$ET1/k8s-plugins/kubernetes.hpi" ]] || { echo "no $K8S and no prebuilt $ET1/k8s-plugins/kubernetes.hpi" >&2; exit 3; }
fi
docker build -q -t cnj/jenkins:k8s -f "$VALIDATION_DIR/jenkins/k8s/Dockerfile" "$ET1" || exit 4
if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  echo "cnj/jenkins:k8s built; no kind cluster $CLUSTER yet to load it into (ET9 creates it, then loads it)"
  exit 0
fi
for image in cnj/jenkins:k8s postgres:16 mcr.microsoft.com/azure-storage/azurite:3.34.0 jenkins/inbound-agent:latest-jdk21; do
  kind_load "$CLUSTER" "$image" || exit 5
done
echo "cnj/jenkins:k8s built and loaded into kind cluster $CLUSTER"
