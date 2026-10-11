#!/bin/bash
# Seeds the hand-testing release on kind (http://localhost:8095 by default) with agents and jobs to try things with:
# two mock agents (label "mock"), the chart's Kubernetes cloud (label "k8s"), and a few jobs using them.
# Idempotent: existing agents and jobs are replaced.
#
#   validation/scenarios/seed-kind.sh [base-url]
source "$(dirname "$0")/../lib.sh"
B=${1:-http://localhost:8095}
api() { curl -gsf -u "$AUTH" "$@"; }
groovy_at() { api -X POST "$B/scriptText" --data-urlencode "script=$1"; }

log "agents"
groovy_at '
import org.jenkinci.plugins.mock_slave.MockSlave
for (n in ["mock-1", "mock-2"]) {
  def a = new MockSlave(n)
  a.setNumExecutors(2)
  a.setLabelString("mock")
  Jenkins.get().addNode(a)
}
println Jenkins.get().nodes.collect { it.nodeName + " [" + it.labelString + "]" }.join(", ")
println "clouds: " + Jenkins.get().clouds.collect { it.name }.join(", ")'

job() { # name config.xml-content
  local name=$1 xml=$2
  api -X POST "$B/job/$name/doDelete" -o /dev/null 2>/dev/null || true
  printf '%s' "$xml" | api -X POST "$B/createItem?name=$name" -H 'Content-Type: application/xml' --data-binary @- -o /dev/null
  echo "  $name"
}
pipeline() { # name script
  job "$1" "$(python3 -c '
import sys
from xml.sax.saxutils import escape
print("""<?xml version="1.1" encoding="UTF-8"?>
<flow-definition plugin="workflow-job"><description>%s</description><definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>%s</script><sandbox>true</sandbox></definition></flow-definition>""" % (escape(sys.argv[1]), escape(sys.argv[2])))' "$3" "$2")"
}

log "jobs"
pipeline hello-mock 'pipeline {
  agent { label "mock" }
  stages {
    stage("Checkout") { steps { echo "pretend to check out on ${env.NODE_NAME}" } }
    stage("Build") { steps { sh "hostname; uname -a" } }
    stage("Test") { steps { sh "echo all tests passed" } }
  }
}' 'A Pipeline on a mock agent (they run as processes beside the controller that holds them).'

pipeline hello-k8s 'pipeline {
  agent { label "k8s" }
  stages {
    stage("Where am I") { steps { sh "hostname; head -2 /etc/os-release" } }
    stage("Build") { steps { sh "echo building in a pod" } }
  }
}' 'A Pipeline on a Kubernetes agent: a pod in the release namespace, started by the cloud of whichever controller runs the build.'

pipeline parallel-mixed 'pipeline {
  agent none
  stages {
    stage("Prepare") { agent { label "mock" } steps { echo "preparing" } }
    stage("Build") {
      parallel {
        stage("On a mock agent") { agent { label "mock" } steps { sh "echo mock side on \$(hostname); sleep 20" } }
        stage("In a pod") { agent { label "k8s" } steps { sh "echo k8s side on \$(hostname); sleep 20" } }
      }
    }
    stage("Report") { agent { label "mock" } steps { echo "both sides done" } }
  }
}' 'Both kinds of agents at once, in parallel stages.'

pipeline slow-steps 'pipeline {
  agent none
  stages {
    stage("Warm up") { steps { echo "starting" } }
    stage("Long work") {
      steps {
        script {
          for (int i = 1; i <= 60; i++) {
            echo "step ${i}"
            sleep 5
          }
        }
      }
    }
    stage("Finish") { steps { echo "done" } }
  }
}' 'Five minutes of steps: watch it from the other controller (Builds card, console), kill its controller pod, or restart it; it resumes.'

pipeline wait-for-input 'pipeline {
  agent none
  stages {
    stage("Build") { steps { echo "built" } }
    stage("Approve") { steps { input message: "Carry on?", ok: "Yes" } }
    stage("Deploy") { agent { label "mock" } steps { echo "deploying from ${env.NODE_NAME}" } }
  }
}' 'Waits for input: approve it from either controller.'

job freestyle-mock '<?xml version="1.1" encoding="UTF-8"?>
<project><description>A freestyle build on a mock agent, archiving an artifact.</description><assignedNode>mock</assignedNode><canRoam>false</canRoam>
<builders><hudson.tasks.Shell><command>echo "hello from $NODE_NAME on $(hostname)" | tee hello.txt</command></hudson.tasks.Shell></builders>
<publishers><hudson.tasks.ArtifactArchiver><artifacts>hello.txt</artifacts></hudson.tasks.ArtifactArchiver></publishers></project>'

job nightly-cron '<?xml version="1.1" encoding="UTF-8"?>
<project><description>Runs every 5 minutes, once in the cluster (whichever controller claims the tick).</description><assignedNode>mock</assignedNode><canRoam>false</canRoam>
<triggers><hudson.triggers.TimerTrigger><spec>H/5 * * * *</spec></hudson.triggers.TimerTrigger></triggers>
<builders><hudson.tasks.Shell><command>echo "tick on $(hostname)"</command></hudson.tasks.Shell></builders></project>'

log "seeded $B"
