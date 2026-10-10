#!/bin/bash
# Sourced by build.command / run-client.command: find a JVM when none is on PATH (Finder launches with a bare PATH).
find_java() {
    if command -v java >/dev/null 2>&1 && java -version >/dev/null 2>&1; then echo "$(command -v java)"; return; fi
    local c
    for c in \
        /opt/homebrew/opt/openjdk*/bin/java /opt/homebrew/opt/openjdk/bin/java /usr/local/opt/openjdk*/bin/java \
        "$HOME"/.sdkman/candidates/java/current/bin/java \
        /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java "$HOME"/Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java \
        "$HOME"/.jdks/*/bin/java "$HOME"/.gradle/jdks/*/bin/java \
        /Applications/RuneLite.app/Contents/PlugIns/*/Contents/Home/bin/java /Applications/RuneLite.app/Contents/Resources/*/bin/java \
        /Applications/IntelliJ*.app/Contents/jbr/Contents/Home/bin/java "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" \
        "$HOME"/Applications/IntelliJ*.app/Contents/jbr/Contents/Home/bin/java \
        "$HOME"/Library/Application\ Support/JetBrains/Toolbox/apps/*/ch-0/*/IntelliJ*.app/Contents/jbr/Contents/Home/bin/java ; do
        if [ -x "$c" ]; then echo "$c"; return; fi
    done
}
JAVA_BIN="$(find_java)"
if [ -n "$JAVA_BIN" ]; then
    export JAVA_HOME="$(cd "$(dirname "$JAVA_BIN")/.." && pwd)"
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "using java: $JAVA_BIN"; "$JAVA_BIN" -version 2>&1
    [ -x "$JAVA_HOME/bin/javac" ] && echo "javac: present" || echo "javac: MISSING (JRE only) — gradle will fetch a JDK via the toolchain init script"
else
    echo "NO JAVA FOUND. Candidates checked:"; ls -d /opt/homebrew/opt/openjdk* /usr/local/opt/openjdk* /Library/Java/JavaVirtualMachines/* "$HOME"/.sdkman "$HOME"/.jdks /Applications/RuneLite.app/Contents/PlugIns/* /Applications/*.app/Contents/jbr 2>&1 | head -20
fi
