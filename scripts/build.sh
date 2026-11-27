#!/usr/bin/env bash
# Build de publicação: sobe a versão, compila em release. Uso: scripts/build.sh [--sem-bump]
set -euo pipefail
cd "$(dirname "$0")/.."
[ "${1:-}" = "--sem-bump" ] || python3 scripts/bump-version.py
cargo build --release -p renandrop
echo "binário: target/release/renandrop"
