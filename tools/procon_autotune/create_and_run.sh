#!/usr/bin/env bash
# Creates ONE practice match and attaches the production bot to it with the SHORTEST POSSIBLE DELAY.
#
# Why this exists: m-11158 was created successfully but the assignment was refused with
# `E_STALE_DAY: trận đã bắt đầu`, because the server had already advanced day 0 by the time a separate
# follow-up command started the JVM. Creation and attachment must therefore happen inside one process,
# with nothing between them but the JVM start-up.
#
# Credentials are sourced from a 0600 file outside the repository and are never echoed: the factory reads
# HEXSESSION from the environment, the runtime reads PROCON_TOKEN from the environment, and both render as
# `<set>` in every line either of them prints.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AUTOTUNE_HOME="${PROCON_AUTOTUNE_HOME:-${HOME}/.procon-autotune}"
SECRETS_FILE="${SECRETS_FILE:-${AUTOTUNE_HOME}/.secrets.env}"

if [[ -z "${HEXSESSION:-}" || -z "${PROCON_TOKEN:-}" ]]; then
    if [[ -f "${SECRETS_FILE}" ]]; then
        # shellcheck source=/dev/null
        set -a; . "${SECRETS_FILE}"; set +a
    fi
fi
if [[ -z "${HEXSESSION:-}" || -z "${PROCON_TOKEN:-}" ]]; then
    echo "AUTOTUNE_CREDENTIALS_MISSING HEXSESSION or PROCON_TOKEN is not set" >&2
    exit 2
fi

CREATE_LOG="$(mktemp -t create-XXXXXX.log)"
python3 "${HERE}/create_practice_match.py" "$@" | tee "${CREATE_LOG}"
MATCH_ID="$(grep -oE 'matchId=[A-Za-z0-9._-]+' "${CREATE_LOG}" | head -1 | cut -d= -f2)"
rm -f "${CREATE_LOG}"

if [[ -z "${MATCH_ID}" ]]; then
    echo "AUTOTUNE_MATCH_ID_UNKNOWN the factory printed no matchId" >&2
    exit 2
fi

echo "CREATE_AND_RUN_ATTACHING matchId=${MATCH_ID}"
exec bash "${HERE}/run_match.sh" "${MATCH_ID}"
