#!/usr/bin/env bash
# Replace only the browser-session cookie used by the practice-match factory.
# The value is read through a hidden terminal prompt and is never passed as a command argument,
# echoed, or written to a log.  The existing bot token line is preserved verbatim.
set -euo pipefail

AUTOTUNE_HOME="${PROCON_AUTOTUNE_HOME:-${HOME}/.procon-autotune}"
SECRETS_FILE="${SECRETS_FILE:-${AUTOTUNE_HOME}/.secrets.env}"

if [[ ! -f "${SECRETS_FILE}" ]]; then
    echo "SESSION_UPDATE_FAILED secrets file is missing: ${SECRETS_FILE}" >&2
    exit 2
fi

TOKEN_LINE="$(sed -n '/^[[:space:]]*export[[:space:]]\+PROCON_TOKEN=/p' "${SECRETS_FILE}" | head -n 1)"
if [[ -z "${TOKEN_LINE}" ]]; then
    echo "SESSION_UPDATE_FAILED PROCON_TOKEN is missing; refusing to overwrite secrets" >&2
    exit 2
fi

trim() {
    local value="$1"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    printf '%s' "${value}"
}

normalize_hexsession() {
    local value lower first last
    value="$(trim "$1")"
    lower="${value,,}"
    # People commonly copy `hexsession:"…"` or `hexsession=…` from browser/devtools.
    # Accept those harmless wrappers so the saved file always contains only the cookie value.
    if [[ "${lower}" == hexsession:* ]]; then
        value="${value#*:}"
    elif [[ "${lower}" == hexsession=* ]]; then
        value="${value#*=}"
    fi
    value="$(trim "${value}")"
    if (( ${#value} >= 2 )); then
        first="${value:0:1}"
        last="${value: -1}"
        if [[ ( "${first}" == '"' && "${last}" == '"' ) || ( "${first}" == "'" && "${last}" == "'" ) ]]; then
            value="${value:1:${#value}-2}"
        fi
    fi
    printf '%s' "$(trim "${value}")"
}

case "${1:-}" in
    "")
        read -r -s -p "Paste fresh HEXSESSION (input hidden): " NEW_HEXSESSION
        echo
        ;;
    --normalize-existing)
        set -a
        # shellcheck source=/dev/null
        . "${SECRETS_FILE}"
        set +a
        NEW_HEXSESSION="${HEXSESSION:-}"
        ;;
    *)
        echo "Usage: bash $0 [--normalize-existing]" >&2
        exit 2
        ;;
esac

NEW_HEXSESSION="$(normalize_hexsession "${NEW_HEXSESSION}")"
LOWER_HEXSESSION="${NEW_HEXSESSION,,}"
if [[ -z "${NEW_HEXSESSION}" || "${NEW_HEXSESSION}" == *$'\n'* || "${NEW_HEXSESSION}" == *$'\r'* \
      || "${NEW_HEXSESSION}" == *[[:space:]]* || "${NEW_HEXSESSION}" == *'"'* \
      || "${NEW_HEXSESSION}" == *"'"* || "${LOWER_HEXSESSION}" == *hexsession* ]]; then
    echo "SESSION_UPDATE_FAILED session value is empty or malformed" >&2
    exit 2
fi

umask 077
TEMP_FILE="$(mktemp "${SECRETS_FILE}.tmp.XXXXXX")"
trap 'rm -f "${TEMP_FILE}"' EXIT
printf 'export HEXSESSION=%q\n%s\n' "${NEW_HEXSESSION}" "${TOKEN_LINE}" > "${TEMP_FILE}"
chmod 600 "${TEMP_FILE}"
mv "${TEMP_FILE}" "${SECRETS_FILE}"
trap - EXIT
echo "SESSION_UPDATE_OK credentials updated without printing their values"
