#!/bin/bash
# Builds release files with a bundled Java runtime into app/target/release/.
# Usage: app/package.sh [--type TYPE]...
#   TYPE: deb, rpm, dmg, pkg or app-image (app-image is archived as .tar.gz)
#   Default: deb on Linux, dmg on macOS.
# Must run on the target OS: jpackage cannot cross-build, and JavaFX jars are platform specific.
# The version comes from app/pom.xml (the release workflow sets it from the git tag).
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAME="IGPSPORT Map Updater"
ARTIFACT="igpsport-map-updater"
MAIN_CLASS="io.github.fernandoglatz.igpsmap.ui.Launcher"
MODULES="java.base,java.desktop,java.logging,java.xml,java.sql,java.naming,java.net.http,java.management,java.prefs,java.scripting,jdk.unsupported,jdk.crypto.ec,jdk.httpserver,jdk.zipfs"

TYPES=()
while [ $# -gt 0 ]; do
    case "$1" in
        --type)
            [ -n "${2:-}" ] || { echo "ERROR: --type needs a value" >&2; exit 2; }
            TYPES+=("$2")
            shift 2
            ;;
        *)
            echo "Usage: $0 [--type deb|rpm|dmg|pkg|app-image]..." >&2
            exit 2
            ;;
    esac
done

case "$(uname -s)" in
    Darwin) OS="macos" ;;
    Linux) OS="linux" ;;
    *) OS="$(uname -s | tr '[:upper:]' '[:lower:]')" ;;
esac
case "$(uname -m)" in
    x86_64 | amd64) ARCH="x64" ;;
    arm64 | aarch64) ARCH="arm64" ;;
    *) ARCH="$(uname -m)" ;;
esac
if [ ${#TYPES[@]} -eq 0 ]; then
    if [ "$OS" = "macos" ]; then TYPES=("dmg"); else TYPES=("deb"); fi
fi

command -v jpackage >/dev/null || { echo "ERROR: jpackage not found (needs JDK 17+)" >&2; exit 1; }

echo "Building jar and collecting dependencies..."
mvn -q -B -f "$APP_DIR/pom.xml" -DskipTests clean package
VERSION="$(mvn -q -B -f "$APP_DIR/pom.xml" help:evaluate -Dexpression=project.version -DforceStdout)"
MAIN_JAR="$ARTIFACT-$VERSION.jar"

INPUT="$APP_DIR/target/libs"
DIST="$APP_DIR/target/dist"
RELEASE="$APP_DIR/target/release"
cp "$APP_DIR/target/$MAIN_JAR" "$INPUT/"
rm -rf "$DIST" "$RELEASE"
mkdir -p "$DIST" "$RELEASE"
BASE="$RELEASE/$ARTIFACT-$VERSION-$OS-$ARCH"

for TYPE in "${TYPES[@]}"; do
    OUT="$DIST/$TYPE"
    EXTRA=()
    case "$TYPE" in
        deb | rpm) EXTRA=(--linux-shortcut --linux-menu-group Utility) ;;
    esac

    # Keep bin/java in the runtime: osmosis runs in a child JVM started from the bundled runtime,
    # so native commands must NOT be stripped.
    echo "Running jpackage (type: $TYPE, version: $VERSION)..."
    jpackage \
        --type "$TYPE" \
        --name "$NAME" \
        --app-version "$VERSION" \
        --vendor "igpsport-map-updater" \
        --description "Regenerates IGPSPORT cycling computer maps from OpenStreetMap data" \
        --input "$INPUT" \
        --main-jar "$MAIN_JAR" \
        --main-class "$MAIN_CLASS" \
        --java-options "-Xmx1g" \
        --add-modules "$MODULES" \
        --jlink-options "--strip-debug --no-man-pages --no-header-files" \
        --dest "$OUT" \
        ${EXTRA[@]+"${EXTRA[@]}"}

    if [ "$TYPE" = "app-image" ]; then
        tar -C "$OUT" -czf "$BASE.tar.gz" .
    else
        FILE="$(find "$OUT" -maxdepth 1 -type f -name "*.$TYPE" | head -n 1)"
        [ -n "$FILE" ] || { echo "ERROR: jpackage produced no .$TYPE file" >&2; exit 1; }
        cp "$FILE" "$BASE.$TYPE"
    fi
done

echo "Release files in $RELEASE:"
ls -l "$RELEASE"
