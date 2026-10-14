#!/bin/bash
# Optionally records every filesystem event under JENKINS_HOME (from before Jenkins starts)
# to /inventory/<controller>.inotify.log, then hands over to the stock jenkins.sh.
set -e

if [[ "${CNJ_INOTIFY:-0}" == "1" ]]; then
  mkdir -p /inventory
  log="/inventory/${JENKINS_CLUSTER_NAME:-jenkins}.inotify.log"
  # inotifywait only has 1s timestamps; perl stamps each event with ms precision so events
  # can be correlated with Jenkins init milestones in the (docker -t) log.
  inotifywait -m -r -q \
    -e create,modify,moved_to,delete,open \
    --format '%e %w%f' \
    "$JENKINS_HOME" 2>/dev/null \
    | perl -MTime::HiRes=time -ne '$|=1; printf "%.3f %s", time, $_' >> "$log" &
  # inotifywait needs a moment to establish its watches
  sleep 2
fi

# S0: seed key material (secret.key*, secrets/, identity.key.enc) shared by all controllers, see findings/E8.
if [[ -n "${CNJ_SEED_DIR:-}" && -d "$CNJ_SEED_DIR" && ! -f "$JENKINS_HOME/secrets/master.key" ]]; then
  cp -a "$CNJ_SEED_DIR/." "$JENKINS_HOME/"
fi

# Target 9 (Kubernetes): the same seed as one archive, from a Secret
if [[ -n "${CNJ_SEED_TGZ:-}" && -f "$CNJ_SEED_TGZ" && ! -f "$JENKINS_HOME/secrets/master.key" ]]; then
  tar -C "$JENKINS_HOME" -xzf "$CNJ_SEED_TGZ"
fi

# Locally built plugins (e.g. from the cnj/t1-* branches) replace the image's versions.
if [[ -d /extra-plugins ]]; then
  mkdir -p "$JENKINS_HOME/plugins"
  for p in /extra-plugins/*.hpi /extra-plugins/*.jpi; do
    [[ -f "$p" ]] || continue
    name=$(basename "$p"); name="${name%.*}"
    # Target 13: a version the cluster's plugin set installed here (e.g. an upgrade) stays until the home is gone
    [[ -f "$JENKINS_HOME/plugins/$name.jpi.cluster" ]] && continue
    rm -rf "$JENKINS_HOME/plugins/$name" "$JENKINS_HOME/plugins/$name".?pi
    cp "$p" "$JENKINS_HOME/plugins/$name.jpi"
  done
fi

# Target 6: each controller prefixes its bound object ids with its name, so requests for them can be forwarded to it
if [[ -n "${JENKINS_CLUSTER_NAME:-}" ]]; then
  export JAVA_OPTS="${JAVA_OPTS:-} -Dorg.kohsuke.stapler.bind.BoundObjectTable.idPrefix=${JENKINS_CLUSTER_NAME} -Djenkins.persistence.RunningBuilds.controllerName=${JENKINS_CLUSTER_NAME}"
fi

if [[ -f /war-override/jenkins.war ]]; then
  export JENKINS_WAR=/war-override/jenkins.war
fi

exec /usr/local/bin/jenkins.sh "$@"
