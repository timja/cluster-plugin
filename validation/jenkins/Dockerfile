ARG JENKINS_IMAGE=jenkins/jenkins:2.580.1-jdk21
FROM ${JENKINS_IMAGE}

USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends inotify-tools procps \
 && rm -rf /var/lib/apt/lists/*

COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

COPY init.groovy.d/ /usr/share/jenkins/ref/init.groovy.d/
COPY entrypoint.sh /usr/local/bin/cnj-entrypoint.sh
RUN chmod +x /usr/local/bin/cnj-entrypoint.sh

USER jenkins
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/cnj-entrypoint.sh"]
