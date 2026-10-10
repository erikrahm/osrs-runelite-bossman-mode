#!/bin/bash
# Double-click in Finder: builds the plugin and writes the output to build.log next to this file.
cd "$(dirname "$0")"
echo "=== gradle build started $(date) ===" > build.log
./gradlew --no-daemon build >> build.log 2>&1
echo "=== exit code $? at $(date) ===" >> build.log
