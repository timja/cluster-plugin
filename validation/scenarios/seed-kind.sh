#!/bin/bash
# Seeds the hand-testing release on kind (http://localhost:8095 by default) with agents and jobs to try things with:
# two mock agents (label "mock"), the chart's Kubernetes cloud (label "k8s", its pod template "default"), the lockable
# resource the release declares (installed with kind-values.yaml), Declarative Pipelines (one taking that lock, one
# triggering another job and waiting for it), a freestyle job, and a folder with a job in it. With --build, runs each job that ends by itself once (twice for some), so there is
# history. Calls use the API token every controller provisions at boot (lib.sh's AUTH): no crumbs or session cookies.
# Idempotent: existing agents and jobs are replaced.
#
#   validation/scenarios/seed-kind.sh [--build] [base-url]
source "$(dirname "$0")/../lib.sh"
BUILD=false
[[ ${1:-} == --build ]] && { BUILD=true; shift; }
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
println "clouds: " + Jenkins.get().clouds.collect { c -> c.name + " " + c.templates.collect { it.name + " [" + it.label + "]" } }.join(", ")'

log "lockable resources (each controller declares them: kind-values.yaml)"
groovy_at '
import org.jenkins.plugins.lockableresources.LockableResourcesManager
println LockableResourcesManager.get().resources.collect { it.name }.join(", ")'

path_of() { # a/b -> job/a/job/b
  local p= part; IFS=/ read -ra parts <<<"$1"
  for part in "${parts[@]}"; do p+="/job/$part"; done
  printf '%s' "${p#/}"
}
job() { # [folder/]name config.xml-content
  local full=$1 xml=$2 parent= name=$1
  [[ $full == */* ]] && { parent="$(path_of "${full%/*}")/"; name=${full##*/}; }
  api -X POST "$B/$(path_of "$full")/doDelete" -o /dev/null 2>/dev/null || true
  printf '%s' "$xml" | api -X POST "$B/${parent}createItem?name=$name" -H 'Content-Type: application/xml' --data-binary @- -o /dev/null
  echo "  $full"
}
pipeline() { # [folder/]name script description
  job "$1" "$(python3 -c '
import sys
from xml.sax.saxutils import escape
print("""<?xml version="1.1" encoding="UTF-8"?>
<flow-definition plugin="workflow-job"><description>%s</description><definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>%s</script><sandbox>true</sandbox></definition></flow-definition>""" % (escape(sys.argv[1]), escape(sys.argv[2])))' "$3" "$2")"
}

log "jobs"
pipeline hello-mock 'pipeline {
  agent {
    label "mock"
  }
  stages {
    stage("Checkout") {
      steps {
        echo "pretend to check out on ${env.NODE_NAME}"
      }
    }
    stage("Build") {
      steps {
        sh "hostname; uname -a"
      }
    }
    stage("Test") {
      steps {
        sh "echo all tests passed"
      }
    }
  }
}' 'A Pipeline on a mock agent (they run as processes beside the controller that holds them).'

pipeline hello-k8s 'pipeline {
  agent {
    label "k8s"
  }
  stages {
    stage("Where am I") {
      steps {
        sh "hostname; head -2 /etc/os-release"
      }
    }
    stage("Build") {
      steps {
        sh "echo building in a pod"
      }
    }
  }
}' 'A Pipeline on a Kubernetes agent: a pod from the cloud'"'"'s pod template "default", in the release namespace, started by the cloud of whichever controller runs the build.'

pipeline parallel-mixed 'pipeline {
  agent none
  stages {
    stage("Prepare") {
      agent {
        label "mock"
      }
      steps {
        echo "preparing"
      }
    }
    stage("Build") {
      parallel {
        stage("On a mock agent") {
          agent {
            label "mock"
          }
          steps {
            sh "echo mock side on \$(hostname); sleep 20"
          }
        }
        stage("In a pod") {
          agent {
            label "k8s"
          }
          steps {
            sh "echo k8s side on \$(hostname); sleep 20"
          }
        }
      }
    }
    stage("Report") {
      agent {
        label "mock"
      }
      steps {
        echo "both sides done"
      }
    }
  }
}' 'Both kinds of agents at once, in parallel stages.'

pipeline locked-deploy 'pipeline {
  agent none
  parameters {
    string(name: "CHANGE", defaultValue: "", description: "What to deploy (two builds asked for at once with different values are not merged)")
  }
  stages {
    stage("Deploy") {
      steps {
        lock("staging-env") {
          echo "holding staging-env"
          sleep 15
          echo "releasing staging-env"
        }
      }
    }
    stage("Smoke test") {
      agent {
        label "mock"
      }
      steps {
        echo "testing staging-env from ${env.NODE_NAME}"
      }
    }
  }
}' 'Takes the lockable resource staging-env, on whichever controller runs it: run it twice at once, from two controllers, and the second waits for the first.'

pipeline release-train 'pipeline {
  agent none
  stages {
    stage("Build") {
      steps {
        echo "building the train"
      }
    }
    stage("Downstream") {
      steps {
        build job: "hello-mock", wait: true
      }
    }
    stage("Report") {
      steps {
        echo "downstream finished"
      }
    }
  }
}' 'Triggers hello-mock and waits for its result, which may come from a build on another controller.'

pipeline slow-steps 'pipeline {
  agent none
  stages {
    stage("Warm up") {
      steps {
        echo "starting"
      }
    }
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
    stage("Finish") {
      steps {
        echo "done"
      }
    }
  }
}' 'Five minutes of steps: watch it from the other controller (Builds card, console), kill its controller pod, or restart it; it resumes.'

pipeline wait-for-input 'pipeline {
  agent none
  stages {
    stage("Build") {
      steps {
        echo "built"
      }
    }
    stage("Approve") {
      steps {
        input message: "Carry on?", ok: "Yes"
      }
    }
    stage("Deploy") {
      agent {
        label "mock"
      }
      steps {
        echo "deploying from ${env.NODE_NAME}"
      }
    }
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

job team-a '<?xml version="1.1" encoding="UTF-8"?>
<com.cloudbees.hudson.plugins.folder.Folder plugin="cloudbees-folder"><description>A folder with a job in it.</description></com.cloudbees.hudson.plugins.folder.Folder>'

pipeline team-a/app 'pipeline {
  agent {
    label "mock"
  }
  stages {
    stage("Build") {
      steps {
        echo "building team-a/app on ${env.NODE_NAME}"
      }
    }
    stage("Test") {
      steps {
        sh "echo tests pass"
      }
    }
  }
}' 'A Pipeline in a folder.'

if $BUILD; then
  last() { # the job's latest build number (0 if none); a job's nextBuildNumber is only up to date where it last built
    api "$B/$1/api/json?tree=lastBuild%5Bnumber%5D" | python3 -c 'import json,sys; print((json.load(sys.stdin)["lastBuild"] or {}).get("number", 0))'
  }
  # build <job> <times>: triggers and waits for each build, one at a time, and says how it ended
  build_wait() {
    local full=$1 p n before r i
    p=$(path_of "$full")
    for ((i = 0; i < $2; i++)); do
      before=$(last "$p")
      api -X POST "$B/$p/build" -o /dev/null 2>/dev/null || api -X POST "$B/$p/buildWithParameters" -o /dev/null
      r= n=
      for _ in $(seq 1 120); do
        [[ -z $n ]] && { n=$(last "$p"); (( n > before )) || n=; }
        [[ -n $n ]] && r=$(api "$B/$p/$n/api/json?tree=building,result" 2>/dev/null \
          | python3 -c 'import json,sys; d=json.load(sys.stdin); print("" if d["building"] else d["result"])' 2>/dev/null || true)
        [[ -n $r ]] && break
        sleep 5
      done
      echo "  $full #$n ${r:-not finished}"
    done
  }
  log "builds"
  build_wait hello-mock 2
  build_wait freestyle-mock 2
  build_wait team-a/app 2
  build_wait locked-deploy 1
  build_wait release-train 1
  build_wait hello-k8s 1
  build_wait parallel-mixed 1
fi

log "seeded $B"
