#!/usr/bin/env bash
# Runs the CURRENT PRODUCTION BOT on one practice match with deferred live-state capture ON.
#
# PART 28 run environment, verbatim:
#   PROCON_MATCH_ID=<created match>
#   PROCON_PLANNER_MODE=JOINT_TEAM_BEAM_V2_R3   (production authority; V3 never submits)
#   PROCON_V3_SHADOW=false                      (no V3 CPU contention during the live match)
#   PROCON_V3_CAPTURE_STATES=true               (capture only; does not change submitted actions)
#
# Credentials are sourced from a 0600 file outside the repository and are never printed: the token is
# exported into the child process only, and the runtime itself renders it as `token=<set>`.
set -euo pipefail

# ITERATION 6: every authoritative artefact lives under $HOME. A mid-session /tmp wipe destroyed the
# previous corpus, the baseline logs and the JFR profiles, so /tmp is no longer trusted for anything
# that has to survive.
AUTOTUNE_HOME="${PROCON_AUTOTUNE_HOME:-${HOME}/.procon-autotune}"
SECRETS_FILE="${SECRETS_FILE:-${AUTOTUNE_HOME}/.secrets.env}"
CORPUS_DIR="${PROCON_V3_CAPTURE_DIR:-${AUTOTUNE_HOME}/corpus}"
JAR="${PROCON_BOT_JAR:-bot-app/target/procon-bot.jar}"

MATCH_ID="${1:-}"
if [[ -z "${MATCH_ID}" ]]; then
    echo "usage: run_match.sh <matchId>" >&2
    exit 2
fi

if [[ -z "${PROCON_TOKEN:-}" ]]; then
    if [[ -f "${SECRETS_FILE}" ]]; then
        # shellcheck source=/dev/null
        set -a; . "${SECRETS_FILE}"; set +a
    fi
fi
if [[ -z "${PROCON_TOKEN:-}" ]]; then
    echo "AUTOTUNE_CREDENTIALS_MISSING PROCON_TOKEN is not set" >&2
    exit 2
fi
if [[ ! -f "${JAR}" ]]; then
    echo "AUTOTUNE_BOT_JAR_MISSING ${JAR}" >&2
    exit 2
fi

LOG_DIR="${AUTOTUNE_HOME}/logs/${MATCH_ID}"
mkdir -p "${LOG_DIR}" "${CORPUS_DIR}"
LOG="${LOG_DIR}/runtime.log"

echo "MATCH_RUN_START matchId=${MATCH_ID} planner=JOINT_TEAM_BEAM_V2_R3 shadow=false capture=true" \
     "corpus=${CORPUS_DIR} log=${LOG}"

# Only the child sees the token. Nothing below echoes an environment value.
PROCON_MATCH_ID="${MATCH_ID}" \
PROCON_PLANNER_MODE=JOINT_TEAM_BEAM_V2_R3 \
PROCON_V3_SHADOW=false \
PROCON_V3_CAPTURE_STATES=true \
PROCON_V3_CAPTURE_DIR="${CORPUS_DIR}" \
PROCON_OTHERS_SHAPE_DIAGNOSTICS=false \
PROCON_OTHERS_VALUE_DIAGNOSTICS=false \
PROCON_V2_COMPETITIVE_POLICY="${PROCON_V2_COMPETITIVE_POLICY:-FINAL_FIXED}" \
PROCON_REPOSITIONING="${PROCON_REPOSITIONING:-true}" \
PROCON_REFUEL_MEETING_LIMIT="${PROCON_REFUEL_MEETING_LIMIT:-4}" \
PROCON_V2_BEAM_WIDTH="${PROCON_V2_BEAM_WIDTH:-384}" \
PROCON_V2_MAX_EXPANDED_STATES="${PROCON_V2_MAX_EXPANDED_STATES:-512}" \
PROCON_V2_MAX_CHILDREN="${PROCON_V2_MAX_CHILDREN:-32}" \
PROCON_V2_MAX_TARGETS="${PROCON_V2_MAX_TARGETS:-6}" \
PROCON_V2_MAX_STAGE_B="${PROCON_V2_MAX_STAGE_B:-96}" \
PROCON_V2_STAGE_B_FAIR_ALLOCATION="${PROCON_V2_STAGE_B_FAIR_ALLOCATION:-true}" \
PROCON_V2_FAIR_FAMILY_EXPANSION="${PROCON_V2_FAIR_FAMILY_EXPANSION:-true}" \
PROCON_V2_STOCK_WEIGHT="${PROCON_V2_STOCK_WEIGHT:-2000}" \
PROCON_REFUEL_SUPPRESS_SPAWN_MEETING="${PROCON_REFUEL_SUPPRESS_SPAWN_MEETING:-true}" \
PROCON_HYBRID_BASE_WEIGHT="${PROCON_HYBRID_BASE_WEIGHT:-3}" \
PROCON_HYBRID_COUPLED_WEIGHT="${PROCON_HYBRID_COUPLED_WEIGHT:-1}" \
PROCON_COMPETITIVE_STRICT_EARLIER_CLAIMS="${PROCON_COMPETITIVE_STRICT_EARLIER_CLAIMS:-true}" \
    java -jar "${JAR}" > "${LOG}" 2>&1 &
BOT_PID=$!
echo "MATCH_RUN_PID pid=${BOT_PID}"
wait "${BOT_PID}" && STATUS=0 || STATUS=$?

CAPTURED=$(find "${CORPUS_DIR}/${MATCH_ID}" -name accepted.json 2>/dev/null | wc -l | tr -d ' ')
DAYS=$(find "${CORPUS_DIR}/${MATCH_ID}" -maxdepth 1 -type d -name 'day-*' 2>/dev/null | wc -l | tr -d ' ')
echo "MATCH_RUN_DONE matchId=${MATCH_ID} exit=${STATUS} capturedDays=${DAYS} acceptedDays=${CAPTURED}"
grep -c 'ACTIONS_ACCEPTED' "${LOG}" 2>/dev/null | sed 's/^/MATCH_RUN_ACCEPTED_LINES /' || true
tail -3 "${LOG}" || true
exit "${STATUS}"
