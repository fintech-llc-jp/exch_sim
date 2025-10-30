#!/bin/bash

export JAVA_HOME=/opt/homebrew/opt/openjdk@17

echo "🚀 Running GMO WebSocket Trade Channel Test..."
echo ""

# Build the project first
./gradlew clean compileJava -q

# Get all the dependencies using Gradle
CLASSPATH="build/classes/main:build/resources/main:$(./gradlew -q dependencies --configuration runtimeClasspath | grep -oP '.*jar' | tr '\n' ':' 2>/dev/null || echo '')"

# Run the test class
java -cp "build/classes/main:build/resources/main:$(ls ~/.gradle/caches/modules-2/files-2.1/*/*//*.jar 2>/dev/null | head -50 | tr '\n' ':')" \
  com.ys.exch_sim.GmoWebSocketTest

