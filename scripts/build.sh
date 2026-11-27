#!/usr/bin/env bash
# Build de publicação: sobe a versão e compila.
#   scripts/build.sh [--sem-bump]            app do PC (release)
#   scripts/build.sh android [--sem-bump]    app Android (APK debug; com keystore configurada, também o release)
set -euo pipefail
cd "$(dirname "$0")/.."

if [ "${1:-}" = "android" ]; then
  shift
  [ "${1:-}" = "--sem-bump" ] || python3 scripts/bump-version.py --android
  export JAVA_HOME="${JAVA_HOME:-$(ls -d "$HOME"/.gradle/jdks/eclipse_adoptium-17-* 2>/dev/null | head -n1)}"
  (cd android && ./gradlew :app:assembleDebug)
  echo "APK: android/app/build/outputs/apk/debug/app-debug.apk"
  exit 0
fi

[ "${1:-}" = "--sem-bump" ] || python3 scripts/bump-version.py
cargo build --release -p renandrop
echo "binário: target/release/renandrop"
