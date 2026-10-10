#!/bin/bash
# E6: can a storage SPI implementation (here: ConfidentialStore) be supplied without forking core?
#  (a) jar on Winstone --commonLibFolder     (b) jar injected into the war's WEB-INF/lib
source "$(dirname "$0")/../lib.sh"
IMG=jenkins/jenkins:2.580.1-jdk21 SRC="$VALIDATION_DIR/experiments/E6/src" OUT="$RAW_DIR/E6"
W=$(mktemp -d); mkdir -p "$OUT" "$W/classes" "$W/lib" "$W/warb/WEB-INF/lib"
docker run --rm --entrypoint cat $IMG /usr/share/jenkins/jenkins.war > "$W/jenkins.war"
(cd "$W" && unzip -q -o jenkins.war 'WEB-INF/lib/jenkins-core-*.jar' 'WEB-INF/lib/spotbugs-annotations*.jar' -d .)
javac -proc:none --release 21 -cp "$W/WEB-INF/lib/*" -d "$W/classes" "$SRC/jenkins/security/E6LoggingConfidentialStore.java"
cp -r "$SRC/META-INF" "$W/classes/"; (cd "$W/classes" && jar cf ../e6.jar .)
cp "$W/e6.jar" "$W/lib/"; cp "$W/e6.jar" "$W/warb/WEB-INF/lib/"; cp "$W/jenkins.war" "$W/jenkins-e6.war"
(cd "$W/warb" && zip -q -0 ../jenkins-e6.war WEB-INF/lib/e6.jar)
docker rm -f e6a e6b >/dev/null 2>&1 || true
docker run -d --name e6a -e JAVA_OPTS=-Djenkins.install.runSetupWizard=false -v "$W/lib:/e6lib:ro" $IMG --commonLibFolder=/e6lib >/dev/null
docker run -d --name e6b -e JAVA_OPTS=-Djenkins.install.runSetupWizard=false -e JENKINS_WAR=/e6/jenkins-e6.war -v "$W:/e6:ro" $IMG >/dev/null
sleep 45
docker logs e6a > "$OUT/commonLibFolder.log" 2>&1 || true
docker logs e6b > "$OUT/war-web-inf-lib.log" 2>&1
{ echo "e6b JENKINS_HOME:"; docker exec e6b ls -la /var/jenkins_home; } > "$OUT/war-web-inf-lib-home.txt" 2>&1
docker rm -f e6a e6b >/dev/null; rm -rf "$W"
grep -hE "E6:|NoClassDefFoundError|Completed initialization" "$OUT"/*.log | head -20 >&2
