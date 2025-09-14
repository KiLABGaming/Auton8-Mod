#!/usr/bin/env sh
set -e

# go to project root
cd "$(dirname "$(realpath "$0")")/.."

./gradlew --console plain --no-daemon --full-stacktrace check build
