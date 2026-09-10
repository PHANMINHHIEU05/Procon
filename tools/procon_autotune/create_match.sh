#!/usr/bin/env bash
# Create one PTIT practice match without starting or requiring a bot JAR.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AUTOTUNE_HOME="${PROCON_AUTOTUNE_HOME:-${HOME}/.procon-autotune}"
SECRETS_FILE="${SECRETS_FILE:-${AUTOTUNE_HOME}/.secrets.env}"

if [[ -f "${SECRETS_FILE}" && "${PROCON_USE_ENV_HEXSESSION:-false}" != "true" ]]; then
    # A terminal can retain a stale HEXSESSION after update_session.sh rewrites the file.  The
    # managed secrets file is therefore authoritative by default; an explicit opt-in retains a
    # caller-supplied environment override for debugging.
    # shellcheck source=/dev/null
    set -a
    . "${SECRETS_FILE}"
    set +a
fi

exec python3 "${HERE}/create_practice_match.py" "$@"
