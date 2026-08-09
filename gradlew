#!/bin/bash
# Gradle Wrapper bootstrap script

APP_NAME="Gradle"
GRADLE_VERSION="8.2"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
DIST_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
DIST_DIR="${GRADLE_USER_HOME}/wrapper/dists/gradle-${GRADLE_VERSION}-bin"

if [ ! -d "$DIST_DIR" ]; then
    mkdir -p "$DIST_DIR"
    echo "Downloading Gradle $GRADLE_VERSION..."
    curl -L -o "$DIST_DIR/gradle.zip" "$DIST_URL"
    unzip -q "$DIST_DIR/gradle.zip" -d "$DIST_DIR"
fi

GRADLE_HOME=$(find "$DIST_DIR" -maxdepth 1 -name "gradle-*" -type d | head -1)
exec "$GRADLE_HOME/bin/gradle" "$@"
