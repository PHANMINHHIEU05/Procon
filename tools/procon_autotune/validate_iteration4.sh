#!/usr/bin/env bash
# Iteration-4 decision witness: replay captured days twice — once with the memoization candidate, once with
# the iteration-3 label-once baseline — and diff the decision fields.
#
# With no argument the whole live corpus is replayed. With a match id, only that match is replayed, which is
# the FRESH-STATE form: days the change has never seen.
#
# V3DecisionSignatureDump keeps decisions (V3_DECISION) and timing/work (V3_WORK) on separate lines, so the
# decision comparison is a plain diff with no normalisation and therefore no residue to interpret.
set -euo pipefail

REPO=/home/hiubeo/Documents/Procon
MATCH="${1:-}"
LABEL="${MATCH:-corpus}"
OUT=/tmp/procon-autotune/reports
TARGET="${REPO}/bot-planner/src/main/java/vn/ptit/procon/planner/NextDayHarvestCapacityCalculator.java"
BASELINE=/tmp/procon-autotune-backup/iteration-4/bot-planner/src/main/java/vn/ptit/procon/planner/NextDayHarvestCapacityCalculator.java
CANDIDATE=/tmp/procon-autotune/backups/iter4/NextDayHarvestCapacityCalculator.java.memo

cd "${REPO}"
mkdir -p "${OUT}"

install_planner() {
    mvn -o -q -DskipTests install -pl bot-planner >"/tmp/procon-autotune/install-iter4-$1.log" 2>&1
    echo "INSTALLED variant=$1 sourceMd5=$(md5sum "${TARGET}" | cut -d' ' -f1)"
}

dump() {  # $1 = variant name
    local args=()
    [[ -n "${MATCH}" ]] && args=(-Dexec.args="--match ${MATCH}")
    mvn -o -q -pl bot-benchmark exec:java \
        -Dexec.mainClass=vn.ptit.procon.benchmark.V3DecisionSignatureDump \
        "${args[@]}" \
        >"${OUT}/iter4-${LABEL}-$1.log" 2>&1
    echo "DUMPED variant=$1 decisionLines=$(grep -c '^V3_DECISION ' "${OUT}/iter4-${LABEL}-$1.log" || true)"
}

cp "${TARGET}" /tmp/procon-autotune/backups/iter4/candidate-as-run.java
install_planner candidate
dump candidate

cp "${BASELINE}" "${TARGET}"
install_planner baseline
dump baseline

cp "${CANDIDATE}" "${TARGET}"
install_planner candidate-restored
if ! diff -q /tmp/procon-autotune/backups/iter4/candidate-as-run.java "${TARGET}" >/dev/null; then
    echo "RESTORE_FAILED the working tree does not match the candidate that was measured" >&2
    exit 3
fi

for variant in baseline candidate; do
    grep '^V3_DECISION ' "${OUT}/iter4-${LABEL}-${variant}.log" | sort > "${OUT}/iter4-${LABEL}-${variant}.decisions"
done
DIFF=$(diff "${OUT}/iter4-${LABEL}-baseline.decisions" "${OUT}/iter4-${LABEL}-candidate.decisions" | grep -c '^[<>]' || true)
echo "DECISION_DIFF scope=${LABEL} differingLines=${DIFF}"
echo "ITERATION4_VALIDATION_DONE"
