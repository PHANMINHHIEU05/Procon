#!/usr/bin/env bash
# Iteration-3 FRESH-STATE validation: replay the PRE-iteration-3 baseline and the iteration-3 candidate
# on the days captured from a brand-new practice match, and compare every decision field.
#
# The comparison is byte-exact by construction: V3DecisionSignatureDump prints decisions and work
# counters on separate lines, so the decision diff needs no timing normalisation at all.
set -euo pipefail

REPO=/home/hiubeo/Documents/Procon
MATCH="${1:?usage: validate_iteration3.sh <matchId>}"
OUT=/tmp/procon-autotune/reports
TARGET="${REPO}/bot-planner/src/main/java/vn/ptit/procon/planner/NextDayHarvestCapacityCalculator.java"
BASELINE=/tmp/procon-autotune-backup/iteration-3/bot-planner/src/main/java/vn/ptit/procon/planner/NextDayHarvestCapacityCalculator.java
CANDIDATE=/tmp/procon-autotune/backups/iter3/NextDayHarvestCapacityCalculator.java.labelonce

cd "${REPO}"
mkdir -p "${OUT}"

install_planner() {
    mvn -o -q -DskipTests install -pl bot-planner >"/tmp/procon-autotune/install-$1.log" 2>&1
    echo "INSTALLED variant=$1 sourceMd5=$(md5sum "${TARGET}" | cut -d' ' -f1)"
}

dump() {  # $1 = variant name
    mvn -o -q -pl bot-benchmark exec:java \
        -Dexec.mainClass=vn.ptit.procon.benchmark.V3DecisionSignatureDump \
        -Dexec.args="--match ${MATCH}" \
        >"${OUT}/freshstate-${MATCH}-$1.log" 2>&1
    echo "DUMPED variant=$1 decisionLines=$(grep -c '^V3_DECISION ' "${OUT}/freshstate-${MATCH}-$1.log" || true)"
}

# 1. The candidate exactly as it stands in the working tree.
cp "${TARGET}" /tmp/procon-autotune/backups/iter3/candidate-as-run.java
install_planner candidate
dump candidate

# 2. The pre-iteration-3 baseline, restored from the iteration backup.
cp "${BASELINE}" "${TARGET}"
install_planner baseline
dump baseline

# 3. Put the candidate back and reinstall, so the tree is never left on the baseline.
cp "${CANDIDATE}" "${TARGET}"
install_planner candidate-restored
if ! diff -q /tmp/procon-autotune/backups/iter3/candidate-as-run.java "${TARGET}" >/dev/null; then
    echo "RESTORE_FAILED the working tree does not match the candidate that was measured" >&2
    exit 3
fi

grep '^V3_DECISION ' "${OUT}/freshstate-${MATCH}-baseline.log"  | sort > "${OUT}/freshstate-${MATCH}-baseline.decisions"
grep '^V3_DECISION ' "${OUT}/freshstate-${MATCH}-candidate.log" | sort > "${OUT}/freshstate-${MATCH}-candidate.decisions"
DIFF=$(diff "${OUT}/freshstate-${MATCH}-baseline.decisions" "${OUT}/freshstate-${MATCH}-candidate.decisions" | grep -c '^[<>]' || true)
echo "FRESH_STATE_DECISION_DIFF match=${MATCH} differingLines=${DIFF}"
echo "FRESH_STATE_VALIDATION_DONE"
