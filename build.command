#!/bin/bash
# Double-click in Finder: builds the plugin and writes the output to build.log next to this file.
cd "$(dirname "$0")"
echo "=== gradle build started $(date) ===" > build.log
source ./javaenv.sh >> build.log 2>&1
./gradlew --no-daemon --init-script toolchain.init.gradle build >> build.log 2>&1
echo "=== exit code $? at $(date) ===" >> build.log
