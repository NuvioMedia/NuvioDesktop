#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/composeApp/build/compose/jars/Nuvio-linux-x64-1.1.16.jar"
java \
  --add-opens=java.desktop/java.awt=ALL-UNNAMED \
  --add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED \
  -jar "$JAR" "$@"
