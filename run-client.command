#!/bin/bash
# Double-click in Finder: launches a RuneLite client with Bossman Mode loaded; log in run-client.log.
cd "$(dirname "$0")"
echo "=== runClient started $(date) ===" > run-client.log
./gradlew --no-daemon runClient >> run-client.log 2>&1
echo "=== exit code $? at $(date) ===" >> run-client.log
