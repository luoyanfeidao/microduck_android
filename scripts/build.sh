#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -n "${MICRODUCK_JAVA_HOME:-}" ]]; then export JAVA_HOME="$MICRODUCK_JAVA_HOME"; fi
cd "$PROJECT"
./gradlew --no-daemon :app:assembleDebug "$@"
printf '\nAPK: %s\n' "$PROJECT/app/build/outputs/apk/debug/app-debug.apk"
