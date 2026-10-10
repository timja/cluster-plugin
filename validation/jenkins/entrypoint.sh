#!/bin/bash
# Optionally records every filesystem event under JENKINS_HOME (from before Jenkins starts)
# to /inventory/<controller>.inotify.log, then hands over to the stock jenkins.sh.
set -e

if [[ "${CNJ_INOTIFY:-0}" == "1" ]]; then
  mkdir -p /inventory
  log="/inventory/${CNJ_NAME:-jenkins}.inotify.log"
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

if [[ -f /war-override/jenkins.war ]]; then
  export JENKINS_WAR=/war-override/jenkins.war
fi

exec /usr/local/bin/jenkins.sh "$@"
