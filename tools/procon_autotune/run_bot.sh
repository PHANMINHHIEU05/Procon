#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${PROCON_TOKEN:-}" ]]; then
    echo "PROCON_TOKEN is required and is intentionally not read from command-line arguments" >&2
    exit 2
fi

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec java -jar "${HERE}/target/procon-bot.jar" "$@"
