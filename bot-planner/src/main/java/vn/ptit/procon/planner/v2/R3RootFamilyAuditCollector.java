package vn.ptit.procon.planner.v2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/**
 * Sidecar accounting for R3 root families. It deliberately does not influence state admission,
 * frontier ordering, the normal K16 shortlist, or final plan selection.
 */
final class R3RootFamilyAuditCollector {
    private final R3RootFamilyAuditMode mode;
    private final Map<String, Set<R3RootFamilyProvenance>> provenanceByExactState = new LinkedHashMap<>();
    private final List<TerminalReference> terminals = new ArrayList<>();
    private final Map<JointTerminalEvaluator.StageATerminalCandidate, JointTerminalEvaluation> normalEvaluations =
            new IdentityHashMap<>();
    private final Map<Integer, FamilyRoots> roots = new LinkedHashMap<>();
    private String noRefuelRootKey;
    private int noRefuelRootCollections;
    private int noRefuelExpandablePatrols;
    private int noRefuelReachableOpportunities;
    private int noRefuelFirstExpansionChildren;
    private boolean noRefuelExpansionRecorded;
    private JointTerminalEvaluator.StageATerminalCandidate selected;
    private R3RootFamilyAudit snapshot = R3RootFamilyAudit.empty();

    R3RootFamilyAuditCollector(R3RootFamilyAuditMode mode) {
        this.mode = mode;
        for (int serviceCount = 0; serviceCount <= 3; serviceCount++) {
            roots.put(serviceCount, new FamilyRoots());
        }
    }

    void rootCandidate(R3RootFamilyProvenance provenance) {
        roots.get(provenance.supportServiceCount()).candidates++;
    }

    void rootAdmitted(String exactKey, R3RootFamilyProvenance provenance, boolean survivesMerge) {
        addProvenance(exactKey, List.of(provenance));
        if (provenance.supportServiceCount() == 0 && noRefuelRootKey == null) {
            noRefuelRootKey = exactKey;
        }
        FamilyRoots family = roots.get(provenance.supportServiceCount());
        family.initiallyAdmitted++;
        if (survivesMerge) {
            family.survivingAfterMerge++;
        }
    }

    void noRefuelRoot(String exactKey, int collections, int expandablePatrols) {
        if (noRefuelRootKey == null || noRefuelRootKey.equals(exactKey)) {
            noRefuelRootKey = exactKey;
            noRefuelRootCollections = collections;
            noRefuelExpandablePatrols = expandablePatrols;
        }
    }

    void noRefuelExpansion(String exactKey, int reachableOpportunities, int generatedChildren) {
        if (!noRefuelExpansionRecorded && exactKey.equals(noRefuelRootKey)) {
            noRefuelReachableOpportunities = reachableOpportunities;
            noRefuelFirstExpansionChildren = generatedChildren;
            noRefuelExpansionRecorded = true;
        }
    }

    void inherit(String sourceExactKey, String targetExactKey) {
        addProvenance(targetExactKey, provenanceFor(sourceExactKey));
    }

    void terminal(String exactKey, JointTerminalEvaluator.StageATerminalCandidate candidate) {
        terminals.add(new TerminalReference(exactKey, candidate));
    }

    void normalEvaluation(JointTerminalEvaluator.StageATerminalCandidate candidate,
            JointTerminalEvaluation evaluation) {
        normalEvaluations.put(candidate, evaluation);
    }

    void selected(JointTerminalEvaluator.StageATerminalCandidate candidate) {
        selected = candidate;
    }

    void complete(JointTerminalEvaluator evaluator, long deadlineNanos, LongSupplier clock) {
        Map<String, JointTerminalEvaluation> physicalEvaluations = new LinkedHashMap<>();
        for (Map.Entry<JointTerminalEvaluator.StageATerminalCandidate, JointTerminalEvaluation> entry
                : normalEvaluations.entrySet()) {
            physicalEvaluations.put(entry.getKey().base().deterministicSignature(), entry.getValue());
        }
        Map<Integer, List<TerminalReference>> byFamily = terminalsByFamily();
        Optional<R3LiveFamilyAudit> liveShadow = Optional.empty();
        if (mode == R3RootFamilyAuditMode.BEST_STAGE_A_PER_FAMILY) {
            liveShadow = Optional.of(runLiveShadowAudit(byFamily, physicalEvaluations, evaluator, deadlineNanos, clock));
        }
        snapshot = buildSnapshot(byFamily, physicalEvaluations, liveShadow);
    }

    R3RootFamilyAudit snapshot() {
        return snapshot;
    }

    private R3RootFamilyAudit buildSnapshot(Map<Integer, List<TerminalReference>> byFamily,
            Map<String, JointTerminalEvaluation> physicalEvaluations, Optional<R3LiveFamilyAudit> liveShadow) {
        List<R3RootFamilySummary> summaries = new ArrayList<>();
        for (int serviceCount = 0; serviceCount <= 3; serviceCount++) {
            List<TerminalReference> raw = byFamily.get(serviceCount);
            List<JointTerminalEvaluator.StageATerminalCandidate> unique = uniquePhysical(raw);
            FamilyRoots root = roots.get(serviceCount);
            if (raw.isEmpty()) {
                summaries.add(unavailable(serviceCount, root));
                continue;
            }
            JointTerminalEvaluator.StageATerminalCandidate bestStageA = unique.getFirst();
            List<EvaluatedReference> evaluated = new ArrayList<>();
            for (JointTerminalEvaluator.StageATerminalCandidate candidate : unique) {
                JointTerminalEvaluation evaluation = physicalEvaluations.get(candidate.base().deterministicSignature());
                if (evaluation != null) {
                    evaluated.add(new EvaluatedReference(candidate, evaluation));
                }
            }
            evaluated.sort(EvaluatedReference.PREFERENCE);
            R3RootFamilyProvenance provenance = preferredProvenance(bestStageA, serviceCount);
            if (evaluated.isEmpty()) {
                summaries.add(new R3RootFamilySummary(serviceCount, true, root.candidates, root.initiallyAdmitted,
                        root.survivingAfterMerge, raw.size(), unique.size(),
                        bestStageA.semi().semiCommitmentRealizableBrandCount(),
                        bestStageA.semi().semiCommitmentRealizableCollections(), unique.size(), 0,
                        -1, -1, -1, -1, -1, -1,
                        bestStageA.base().deterministicSignature(), provenance.supportedPatrolIds(),
                        provenance.supportSkeletonSignature()));
                continue;
            }
            EvaluatedReference best = evaluated.getFirst();
            var hybrid = best.evaluation.hybrid();
            R3RootFamilyProvenance bestProvenance = preferredProvenance(best.candidate, serviceCount);
            summaries.add(new R3RootFamilySummary(serviceCount, true, root.candidates, root.initiallyAdmitted,
                    root.survivingAfterMerge, raw.size(), unique.size(),
                    bestStageA.semi().semiCommitmentRealizableBrandCount(),
                    bestStageA.semi().semiCommitmentRealizableCollections(), unique.size(), evaluated.size(),
                    hybrid.ownSemiBrands(), hybrid.ownSemiCollections(), hybrid.coupledOwnCollections(),
                    hybrid.opponentBaselineCollections(), hybrid.coupledOpponentCollections(), hybrid.hybridMarginScore4(),
                    best.candidate.base().deterministicSignature(), bestProvenance.supportedPatrolIds(),
                    bestProvenance.supportSkeletonSignature()));
        }
        int globalUnique = uniquePhysical(terminals).size();
        R3RootFamilyProvenance selectedProvenance = selected == null ? R3RootFamilyProvenance.noRefuel()
                : preferredProvenance(selected, -1);
        R3RootFamilySummary noRefuel = summaries.getFirst();
        Integer selectedSemi = selected == null ? null : selected.semi().semiCommitmentRealizableCollections();
        JointTerminalEvaluation selectedEvaluation = selected == null ? null
                : physicalEvaluations.get(selected.base().deterministicSignature());
        Integer selectedHybrid = selectedEvaluation == null ? null : selectedEvaluation.hybrid().hybridMarginScore4();
        Integer noRefuelSemi = noRefuel.bestFullyEvaluatedOwnSemiCollections() < 0 ? null
                : noRefuel.bestFullyEvaluatedOwnSemiCollections();
        Integer noRefuelHybrid = noRefuel.bestFullyEvaluatedHybridMarginScore4() < 0 ? null
                : noRefuel.bestFullyEvaluatedHybridMarginScore4();
        List<JointTerminalEvaluator.StageATerminalCandidate> noRefuelUnique = uniquePhysical(byFamily.get(0));
        boolean waitOnlyExists = noRefuelUnique.stream().anyMatch(value -> waitOnly(value.base().deterministicSignature()));
        boolean waitOnlyBest = !noRefuelUnique.isEmpty() && waitOnly(noRefuelUnique.getFirst().base().deterministicSignature());
        JointTerminalEvaluator.StageATerminalCandidate bestNoRefuel = noRefuelUnique.isEmpty() ? null : noRefuelUnique.getFirst();
        JointTerminalEvaluation bestNoRefuelEvaluation = bestNoRefuel == null ? null
                : physicalEvaluations.get(bestNoRefuel.base().deterministicSignature());
        R3NoRefuelSearchAudit noRefuelAudit = new R3NoRefuelSearchAudit(noRefuelRootCollections,
                noRefuelExpandablePatrols, noRefuelReachableOpportunities, noRefuelFirstExpansionChildren,
                byFamily.get(0).size(), noRefuelUnique.size(),
                bestNoRefuel == null ? -1 : bestNoRefuel.semi().semiCommitmentRealizableBrandCount(),
                bestNoRefuel == null ? -1 : bestNoRefuel.semi().semiCommitmentRealizableCollections(),
                bestNoRefuelEvaluation == null ? -1 : bestNoRefuelEvaluation.hybrid().hybridMarginScore4(),
                waitOnlyExists, waitOnlyBest);
        return new R3RootFamilyAudit(mode, summaries, terminals.size(), globalUnique, selectedProvenance,
                selectedSemi, noRefuelSemi, difference(selectedSemi, noRefuelSemi), selectedHybrid, noRefuelHybrid,
                difference(selectedHybrid, noRefuelHybrid), Optional.empty(), liveShadow,
                Optional.of(noRefuelAudit));
    }

    /**
     * Runs after the normal K16 result is fixed. Only a family's best Stage-A physical plan is
     * eligible, and a normal K16 evaluation is reused rather than evaluated again.
     */
    private R3LiveFamilyAudit runLiveShadowAudit(Map<Integer, List<TerminalReference>> byFamily,
            Map<String, JointTerminalEvaluation> physicalEvaluations, JointTerminalEvaluator evaluator,
            long deadlineNanos, LongSupplier clock) {
        List<R3LiveFamilyAuditFamily> families = new ArrayList<>();
        List<ShadowHead> evaluated = new ArrayList<>();
        int performed = 0;
        int skipped = 0;
        for (int serviceCount = 0; serviceCount <= 3; serviceCount++) {
            List<JointTerminalEvaluator.StageATerminalCandidate> unique = uniquePhysical(byFamily.get(serviceCount));
            if (unique.isEmpty()) {
                families.add(R3LiveFamilyAuditFamily.unavailable(serviceCount));
                continue;
            }
            JointTerminalEvaluator.StageATerminalCandidate best = unique.getFirst();
            R3RootFamilyProvenance provenance = preferredProvenance(best, serviceCount);
            String signature = best.base().deterministicSignature();
            JointTerminalEvaluation evaluation = physicalEvaluations.get(signature);
            boolean skippedForDeadline = false;
            if (evaluation == null) {
                if (clock.getAsLong() >= deadlineNanos) {
                    skippedForDeadline = true;
                    skipped++;
                } else {
                    evaluation = evaluator.evaluateStageB(best);
                    physicalEvaluations.put(signature, evaluation);
                    performed++;
                }
            }
            if (evaluation != null) {
                var hybrid = evaluation.hybrid();
                evaluated.add(new ShadowHead(serviceCount, best, evaluation));
                families.add(new R3LiveFamilyAuditFamily(serviceCount, true,
                        best.semi().semiCommitmentRealizableBrandCount(),
                        best.semi().semiCommitmentRealizableCollections(), true,
                        hybrid.ownSemiBrands(), hybrid.ownSemiCollections(), hybrid.coupledOwnCollections(),
                        hybrid.opponentBaselineCollections(), hybrid.coupledOpponentCollections(),
                        hybrid.hybridMarginScore4(), provenance.supportedPatrolIds(),
                        provenance.supportSkeletonSignature(), signature, false));
            } else {
                families.add(new R3LiveFamilyAuditFamily(serviceCount, true,
                        best.semi().semiCommitmentRealizableBrandCount(),
                        best.semi().semiCommitmentRealizableCollections(), false,
                        null, null, null, null, null, null, provenance.supportedPatrolIds(),
                        provenance.supportSkeletonSignature(), signature, skippedForDeadline));
            }
        }
        ShadowHead winner = evaluated.stream().min(ShadowHead.PREFERENCE).orElse(null);
        Optional<Integer> bestFamily = winner == null ? Optional.empty() : Optional.of(winner.serviceCount());
        Optional<Boolean> selectedMatches = bestFamily.map(serviceCount -> selected != null
                && preferredProvenance(selected, -1).supportServiceCount() == serviceCount);
        return new R3LiveFamilyAudit(families, performed, skipped, bestFamily, selectedMatches);
    }

    private static Integer difference(Integer left, Integer right) {
        return left == null || right == null ? null : left - right;
    }

    private R3RootFamilySummary unavailable(int serviceCount, FamilyRoots root) {
        return new R3RootFamilySummary(serviceCount, false, root.candidates, root.initiallyAdmitted,
                root.survivingAfterMerge, 0, 0, -1, -1, 0, 0,
                -1, -1, -1, -1, -1, -1, "UNAVAILABLE", List.of(), "UNAVAILABLE");
    }

    private Map<Integer, List<TerminalReference>> terminalsByFamily() {
        Map<Integer, List<TerminalReference>> result = new LinkedHashMap<>();
        for (int serviceCount = 0; serviceCount <= 3; serviceCount++) {
            result.put(serviceCount, new ArrayList<>());
        }
        for (TerminalReference terminal : terminals) {
            for (R3RootFamilyProvenance provenance : provenanceFor(terminal.exactKey)) {
                result.get(provenance.supportServiceCount()).add(terminal);
            }
        }
        return result;
    }

    private static List<JointTerminalEvaluator.StageATerminalCandidate> uniquePhysical(
            List<TerminalReference> terminals) {
        Map<String, JointTerminalEvaluator.StageATerminalCandidate> unique = new LinkedHashMap<>();
        terminals.stream().map(TerminalReference::candidate).sorted(JointTerminalEvaluator::compareStageA)
                .forEach(candidate -> unique.putIfAbsent(candidate.base().deterministicSignature(), candidate));
        return List.copyOf(unique.values());
    }

    private R3RootFamilyProvenance preferredProvenance(
            JointTerminalEvaluator.StageATerminalCandidate candidate, int requestedServiceCount) {
        return terminals.stream().filter(value -> value.candidate == candidate).findFirst()
                .map(value -> provenanceFor(value.exactKey).stream()
                        .filter(provenance -> requestedServiceCount < 0
                                || provenance.supportServiceCount() == requestedServiceCount)
                        .findFirst().orElse(R3RootFamilyProvenance.noRefuel()))
                .orElse(R3RootFamilyProvenance.noRefuel());
    }

    private Set<R3RootFamilyProvenance> provenanceFor(String exactKey) {
        return provenanceByExactState.getOrDefault(exactKey, Set.of());
    }

    private static boolean waitOnly(String signature) {
        if (signature == null || signature.isEmpty()) return false;
        String actions = signature.substring(signature.indexOf(':') + 1);
        return !actions.contains("M");
    }

    private void addProvenance(String exactKey, Iterable<R3RootFamilyProvenance> additions) {
        Set<R3RootFamilyProvenance> values = provenanceByExactState.computeIfAbsent(exactKey,
                ignored -> new TreeSet<>());
        additions.forEach(values::add);
    }

    private static final class FamilyRoots {
        private int candidates;
        private int initiallyAdmitted;
        private int survivingAfterMerge;
    }

    private record TerminalReference(String exactKey, JointTerminalEvaluator.StageATerminalCandidate candidate) {
    }

    private record EvaluatedReference(JointTerminalEvaluator.StageATerminalCandidate candidate,
            JointTerminalEvaluation evaluation) {
        private static final Comparator<EvaluatedReference> PREFERENCE = (left, right) -> {
            if (left.evaluation.betterThan(right.evaluation)) return -1;
            if (right.evaluation.betterThan(left.evaluation)) return 1;
            return left.candidate.base().deterministicSignature()
                    .compareTo(right.candidate.base().deterministicSignature());
        };
    }

    private record ShadowHead(int serviceCount, JointTerminalEvaluator.StageATerminalCandidate candidate,
            JointTerminalEvaluation evaluation) {
        private static final Comparator<ShadowHead> PREFERENCE = (left, right) -> {
            if (left.evaluation.betterThan(right.evaluation)) return -1;
            if (right.evaluation.betterThan(left.evaluation)) return 1;
            return left.candidate.base().deterministicSignature()
                    .compareTo(right.candidate.base().deterministicSignature());
        };
    }
}
