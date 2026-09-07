#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  if [[ -z "$JAVA_HOME" ]] && command -v brew >/dev/null; then
    JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
  fi
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"
fi
python3 -m unittest discover -s tests -p 'test_serverless_docs.py' -v
./dev test
./dev build
