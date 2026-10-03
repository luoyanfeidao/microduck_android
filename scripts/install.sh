#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
ADB_ARGS=()
if [[ -n "${ANDROID_SERIAL:-}" ]]; then ADB_ARGS=(-s "$ANDROID_SERIAL"); fi
adb "${ADB_ARGS[@]}" install -r "$PROJECT/app/build/outputs/apk/debug/app-debug.apk"
adb "${ADB_ARGS[@]}" shell am start -n com.microduck.sim/.MainActivity --es keyframe SIT --ei benchmark_steps 2000
