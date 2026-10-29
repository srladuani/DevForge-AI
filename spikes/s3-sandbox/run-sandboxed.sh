#!/usr/bin/env bash
# usage: run-sandboxed.sh <net|nonet> <logfile> <cmd...>   spike harness: bubblewrap stand-in for the Docker sandbox
S=${SPIKE_DIR:?set SPIKE_DIR to a scratch dir containing work/gradle-backend and gradle-home}
mode=$1; log=$2; shift 2
net=(); [ "$mode" = nonet ] && net=(--unshare-net)
JDK=$(dirname "$(dirname "$(readlink -f "$(which java)")")")
/usr/bin/time -v -o "$log.time" bwrap --ro-bind / / --dev /dev --proc /proc --tmpfs /mnt \
  --bind "$S/work/gradle-backend" /mnt/work --bind "$S/gradle-home" /mnt/gh --ro-bind "$JDK" /mnt/jdk \
  --tmpfs /tmp --tmpfs "$HOME" --unshare-pid --die-with-parent "${net[@]}" \
  --chdir /mnt/work --setenv GRADLE_USER_HOME /mnt/gh --setenv JAVA_HOME /mnt/jdk --setenv HOME /tmp \
  --setenv PATH /mnt/jdk/bin:/usr/bin:/bin "$@" > "$log" 2>&1
echo "exit=$?" >> "$log"
