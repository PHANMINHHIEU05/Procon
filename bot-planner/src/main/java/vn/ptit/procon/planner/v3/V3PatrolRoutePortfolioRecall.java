package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/**
 * {@code V3_PATROL_ROUTE_PORTFOLIO_RECALL} — PART 2. Per PATROL: did the route the V2 witness actually flew
 * survive into the bounded portfolio, and if not, WHICH layer lost it?
 *
 * <p>The audit is deliberately unable to guess. {@code firstLossReason} is derived from facts the portfolio
 * itself recorded: whether the full route was generated, whether any retained route dominates it, whether the
 * conservative Pareto front was larger than the retention cap, and how deep the deepest generated prefix of
 * the witness route got. If none of those apply the route was simply retained, and this audit says so.
 *
 * <p>The DECISION this feeds is the one the mandate insists on proving rather than assuming: if witness routes
 * are missing here, the route portfolio is the first loss layer and team composition is not to blame.
 */
public record V3PatrolRoutePortfolioRecall(String supportRootSignature, List<PatrolRecall> patrols) {

    public V3PatrolRoutePortfolioRecall {
        Objects.requireNonNull(supportRootSignature, "Support root must not be null");
        patrols = List.copyOf(Objects.requireNonNull(patrols, "Patrols must not be null"));
    }

    /** PART 40 CASE A/B discriminator: true when EVERY route the witness needs is in the portfolio. */
    public boolean allWitnessRoutesRetained() {
        return patrols.stream().allMatch(PatrolRecall::witnessRouteRetained);
    }

    public PatrolRecall patrol(AgentId patrolId) {
        return patrols.stream().filter(value -> value.patrolId().equals(patrolId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown patrol: " + patrolId.value()));
    }

    public int retainedTotal() { return patrols.stream().mapToInt(PatrolRecall::retainedRouteCount).sum(); }

    public int generatedTotal() { return patrols.stream().mapToInt(PatrolRecall::generatedRouteCount).sum(); }

    /** One PATROL's recall verdict. {@code witnessRouteRank} is {@code -1} when the route is not retained. */
    public record PatrolRecall(AgentId patrolId, String witnessRouteSignature, int witnessRouteLength,
            int generatedRouteCount, int retainedRouteCount, boolean witnessRouteGenerated,
            boolean witnessRouteRetained, int witnessRouteRank, int witnessPrefixGenerated,
            String deepestWitnessPrefix, StrategicRoutePortfolio.LossReason firstLossReason) {

        public PatrolRecall {
            Objects.requireNonNull(patrolId, "Patrol id must not be null");
            Objects.requireNonNull(witnessRouteSignature, "Signature must not be null");
            Objects.requireNonNull(deepestWitnessPrefix, "Deepest prefix must not be null");
            Objects.requireNonNull(firstLossReason, "Loss reason must not be null");
        }

        /** True when the deepest generated prefix IS the whole witness route. */
        public boolean prefixComplete() { return witnessPrefixGenerated >= witnessRouteLength; }

        @Override
        public String toString() {
            return "P" + patrolId.value() + " " + witnessRouteSignature + " generated=" + witnessRouteGenerated
                    + " retained=" + witnessRouteRetained + " rank=" + witnessRouteRank + " of "
                    + retainedRouteCount + "/" + generatedRouteCount + " deepestPrefix="
                    + witnessPrefixGenerated + "(" + deepestWitnessPrefix + ") loss=" + firstLossReason;
        }
    }

    /** Measures recall of one decomposition against the portfolios built under the SAME support root. */
    public static V3PatrolRoutePortfolioRecall of(V3WitnessRouteDecomposition witness,
            Map<AgentId, StrategicRoutePortfolio.Portfolio> portfolios, int maxRouteLength) {
        Objects.requireNonNull(witness, "Witness must not be null");
        Objects.requireNonNull(portfolios, "Portfolios must not be null");
        List<PatrolRecall> patrols = new ArrayList<>();
        for (V3WitnessRouteDecomposition.PatrolRoute route : witness.routes()) {
            patrols.add(recall(route, portfolios.get(route.patrolId()), maxRouteLength));
        }
        return new V3PatrolRoutePortfolioRecall(witness.supportRootSignature(), patrols);
    }

    private static PatrolRecall recall(V3WitnessRouteDecomposition.PatrolRoute route,
            StrategicRoutePortfolio.Portfolio portfolio, int maxRouteLength) {
        String signature = route.signature();
        if (portfolio == null) {
            return new PatrolRecall(route.patrolId(), signature, route.length(), 0, 0, false, false, -1, 0,
                    "NONE", StrategicRoutePortfolio.LossReason.ROUTE_NOT_GENERATED);
        }
        // The empty route is a reserved choice of every composition expansion, so it is always available.
        if (route.isEmpty()) {
            return new PatrolRecall(route.patrolId(), signature, 0, portfolio.generated(),
                    portfolio.retained(), true, true, -1, 0, "STOP",
                    StrategicRoutePortfolio.LossReason.FULL_ROUTE_RETAINED);
        }
        Map<Integer, String> prefixes = prefixes(route.targets());
        int deepest = 0;
        String deepestSignature = "NONE";
        for (Map.Entry<Integer, String> prefix : prefixes.entrySet()) {
            if (portfolio.generatedContains(prefix.getValue()) && prefix.getKey() > deepest) {
                deepest = prefix.getKey();
                deepestSignature = prefix.getValue();
            }
        }
        int rank = portfolio.rankOf(signature);
        boolean generated = portfolio.generatedContains(signature);
        return new PatrolRecall(route.patrolId(), signature, route.length(), portfolio.generated(),
                portfolio.retained(), generated, rank >= 0, rank, deepest, deepestSignature,
                loss(route, portfolio, signature, generated, rank, maxRouteLength));
    }

    private static StrategicRoutePortfolio.LossReason loss(V3WitnessRouteDecomposition.PatrolRoute route,
            StrategicRoutePortfolio.Portfolio portfolio, String signature, boolean generated, int rank,
            int maxRouteLength) {
        if (rank >= 0) return StrategicRoutePortfolio.LossReason.FULL_ROUTE_RETAINED;
        if (route.length() > maxRouteLength) return StrategicRoutePortfolio.LossReason.ROUTE_PATH_LENGTH;
        if (!generated) {
            return portfolio.generatedRoutes().isEmpty()
                    ? StrategicRoutePortfolio.LossReason.ROUTE_NOT_GENERATED
                    : StrategicRoutePortfolio.LossReason.ROUTE_PORTFOLIO_PRUNE;
        }
        StrategicRouteCandidate candidate = portfolio.generatedRoute(signature);
        boolean dominated = portfolio.routes().stream()
                .anyMatch(kept -> StrategicRoutePortfolio.dominates(kept, candidate));
        return dominated ? StrategicRoutePortfolio.LossReason.ROUTE_DOMINANCE
                : StrategicRoutePortfolio.LossReason.ROUTE_ORDERING;
    }

    /** Every proper prefix of the witness route, keyed by its length, in the portfolio's own signature form. */
    private static Map<Integer, String> prefixes(List<Position> targets) {
        Map<Integer, String> result = new LinkedHashMap<>();
        for (int length = 1; length <= targets.size(); length++) {
            result.put(length, V3WitnessRouteDecomposition.signature(targets.subList(0, length)));
        }
        return result;
    }

    @Override
    public String toString() {
        return "recall root=" + supportRootSignature + " allRetained=" + allWitnessRoutesRetained() + " "
                + patrols.stream().map(PatrolRecall::toString).toList();
    }
}
