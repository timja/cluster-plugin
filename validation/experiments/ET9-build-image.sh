#!/bin/bash
# Builds cnj/jenkins:k8s for Target 9 from the outputs of ET1-build.sh, plus the kubernetes plugin built from
# ../kubernetes-plugin-cnj (cnj/t9-m2-cluster-cap), and loads it and the other images into the kind cluster.
source "$(dirname "$0")/../lib.sh"
ET1="$VALIDATION_DIR/.et1" K8S=${K8S_PLUGIN:-$(cd "$VALIDATION_DIR/../.." && pwd)/kubernetes-plugin-cnj} CLUSTER=${KIND_CLUSTER:-cnj}
[[ -f "$ET1/war/jenkins.war" ]] || { echo "run ET1-build.sh first" >&2; exit 2; }
(cd "$K8S" && mvn -B -ntp -q -Dspotbugs.skip -Dcheckstyle.skip -Denforcer.skip -Dlicense.skip=true -Djacoco.skip -Dspotless.check.skip -DskipTests package) || exit 3
# kept out of $ET1/plugins, which the compose controllers mount: there it would fail to load without its dependencies
rm -f "$ET1/plugins/kubernetes.hpi"; mkdir -p "$ET1/k8s-plugins"
cp "$K8S/target/kubernetes.hpi" "$ET1/k8s-plugins/kubernetes.hpi"
echo "kubernetes $(git -C "$K8S" rev-parse --abbrev-ref HEAD) $(git -C "$K8S" rev-parse HEAD)" >> "$ET1/BUILD"
docker build -q -t cnj/jenkins:k8s -f "$VALIDATION_DIR/jenkins/k8s/Dockerfile" "$ET1" || exit 4
for image in cnj/jenkins:k8s postgres:16 mcr.microsoft.com/azure-storage/azurite:3.34.0 jenkins/inbound-agent:latest-jdk21; do
  kind load docker-image --name "$CLUSTER" "$image" >/dev/null || exit 5
done
echo "cnj/jenkins:k8s built and loaded into kind cluster $CLUSTER"
