# Target 9: a self-contained controller image for Kubernetes: the harness image plus the locally built war and plugins
# (no host mounts) and the kubernetes plugin. Built by experiments/ET9-build-image.sh from validation/.et1.
FROM cnj/jenkins:dev
USER root
COPY war/jenkins.war /war-override/jenkins.war
COPY plugins/ /extra-plugins/
# the kubernetes plugin's dependencies, resolved against the war actually run (the plugin itself comes from /extra-plugins),
# and mock-slave for trying agents. The plugin set is fixed at runtime (FixedPluginSet): plugins wanted on the cluster go here
RUN jenkins-plugin-cli --war /war-override/jenkins.war --plugins kubernetes mock-slave \
 && chown -R jenkins:jenkins /usr/share/jenkins/ref/plugins
USER jenkins
