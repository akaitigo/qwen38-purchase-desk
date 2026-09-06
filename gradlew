#!/usr/bin/env bash
set -Eeuo pipefail
V="${GRADLE_VERSION:-8.14.3}"
BASE="${GRADLE_BOOTSTRAP_HOME:-$HOME/.cache/reposqueeze-gradle}"
DIR="$BASE/gradle-$V"; ZIP="$BASE/gradle-$V-bin.zip"
if [[ ! -x "$DIR/bin/gradle" ]]; then
  mkdir -p "$BASE"
  [[ -f "$ZIP" ]] || curl -fL --retry 3 -o "$ZIP" "https://services.gradle.org/distributions/gradle-$V-bin.zip"
  rm -rf "$DIR"; unzip -q "$ZIP" -d "$BASE"
fi
exec "$DIR/bin/gradle" "$@"
