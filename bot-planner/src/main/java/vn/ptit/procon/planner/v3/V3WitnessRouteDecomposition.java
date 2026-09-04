package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;

/**
 * PART 1: the validated V2 team plan, decomposed into one WHOLE strategic route per PATROL.
 *
 * <p>Nothing here is hardcoded. Every field is read out of the {@link V2BaselineWitness} chronology and its
 * {@link V2StrategicWitness} strategic reading, so if the frozen R3 planner ever changes its mind about
 * CURRENT LARGE this decomposition changes with it instead of asserting a stale path.
 *
 * <p>The reason Phase 2.6 needs this shape at all: the joint per-hop beam it replaces had no name for "the
 * whole route PATROL 2 takes". Recall could therefore only ever be measured hop by hop, which is exactly how
 * a fourteen-transition team can look perfectly representable and still be unreachable.
 */
public record V3WitnessRouteDecomposition(List<PatrolRoute> routes, String supportRootSignature,
        int totalStrategicTransitions, int totalStrategicCollections, int totalIntermediateCollections,
        int ownSemiCollections, int hybridMarginScore4, boolean validatorAccepted, boolean simulatorValid) {

    public V3WitnessRouteDecomposition {
        routes = List.copyOf(Objects.requireNonNull(routes, "Routes must not be null"));
        Objects.requireNonNull(supportRootSignature, "Support root must not be null");
    }

    public PatrolRoute route(AgentId patrolId) {
        return routes.stream().filter(value -> value.patrolId().equals(patrolId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown patrol: " + patrolId.value()));
    }

    /** The PATROLs the witness actually gives work to — the ones a recall audit has something to look for. */
    public List<PatrolRoute> workingRoutes() {
        return routes.stream().filter(value -> !value.isEmpty()).toList();
    }

    public int longestRoute() {
        return routes.stream().mapToInt(PatrolRoute::length).max().orElse(0);
    }

    /**
     * One PATROL's whole route as the witness flew it.
     *
     * @param signature the {@code a>b>c} target chain, or {@code STOP} — the exact key a portfolio is keyed on
     * @param durationSteps the step of the last move, i.e. how much of the day this route consumes
     * @param fuelChronology every fuel change, in order, as {@code step:before>after:cause}
     */
    public record PatrolRoute(AgentId patrolId, Position start, int startFuel, List<Position> targets,
            List<Position> intermediateCollections, Set<BrandId> brands, int collectionCount, int durationSteps,
            int endFuel, Position endPosition, List<String> fuelChronology, String supportProvenance,
            String signature) {

        public PatrolRoute {
            Objects.requireNonNull(patrolId, "Patrol id must not be null");
            targets = List.copyOf(Objects.requireNonNull(targets, "Targets must not be null"));
            intermediateCollections = List.copyOf(Objects.requireNonNull(intermediateCollections));
            brands = Set.copyOf(Objects.requireNonNull(brands, "Brands must not be null"));
            fuelChronology = List.copyOf(Objects.requireNonNull(fuelChronology));
            Objects.requireNonNull(signature, "Signature must not be null");
        }

        public boolean isEmpty() { return targets.isEmpty(); }

        public int length() { return targets.size(); }

        public Position firstTarget() { return targets.isEmpty() ? start : targets.getFirst(); }

        public Position lastTarget() { return targets.isEmpty() ? start : targets.getLast(); }

        public boolean requiresSupport() { return !supportProvenance.equals("NONE"); }

        @Override
        public String toString() {
            return "P" + patrolId.value() + " " + signature + " own=" + collectionCount + " brands="
                    + brands.size() + " steps=" + durationSteps + " support=" + supportProvenance;
        }
    }

    /** Reads the decomposition out of an already-captured witness; captures nothing itself. */
    public static V3WitnessRouteDecomposition of(V2BaselineWitness witness, V2StrategicWitness strategic) {
        Objects.requireNonNull(witness, "Witness must not be null");
        Objects.requireNonNull(strategic, "Strategic witness must not be null");
        List<PatrolRoute> routes = new ArrayList<>();
        for (V2StrategicWitness.PatrolSkeleton skeleton : strategic.patrols()) {
            V2BaselineWitness.PatrolWitness patrol = witness.patrol(skeleton.patrolId());
            List<Position> targets = skeleton.orderedStrategicCollections();
            Set<BrandId> brands = new LinkedHashSet<>(patrol.brands());
            List<String> fuel = patrol.fuelChronology().stream()
                    .map(point -> point.step() + ":" + point.before() + ">" + point.after() + ":" + point.cause())
                    .toList();
            routes.add(new PatrolRoute(skeleton.patrolId(), skeleton.start(), skeleton.startFuel(), targets,
                    skeleton.intermediateTrajectoryCollections(), brands, patrol.claims().size(),
                    patrol.lastMoveStep(), patrol.finalFuel(), patrol.finalPosition(), fuel,
                    skeleton.supportProvenance(), signature(targets)));
        }
        return new V3WitnessRouteDecomposition(routes, strategic.supportRootSignature(),
                strategic.totalStrategicTransitions(), strategic.totalStrategicCollections(),
                strategic.totalIntermediateCollections(), witness.ownSemiCollections(),
                witness.hybridMarginScore4(), witness.validatorAccepted(), witness.simulatorValid());
    }

    /** The portfolio's own signature convention, so a recall lookup is a string equality and nothing more. */
    public static String signature(List<Position> targets) {
        return targets.stream().map(position -> Integer.toString(position.value()))
                .reduce((left, right) -> left + ">" + right).orElse("STOP");
    }

    /**
     * PART 36: the whole-team witness key, in exactly the form {@link TeamCompositionState#routeSignatures()}
     * produces, so team recall is also a string equality and never a structural re-derivation.
     */
    public String teamSignature() {
        return routes.stream().sorted((left, right) ->
                        Integer.compare(left.patrolId().value(), right.patrolId().value()))
                .map(route -> route.patrolId().value() + "=" + route.signature())
                .reduce((left, right) -> left + " " + right).orElse("EMPTY");
    }

    @Override
    public String toString() {
        return "witness own=" + ownSemiCollections + "/h4=" + hybridMarginScore4 + " transitions="
                + totalStrategicTransitions + " root=" + supportRootSignature + " routes="
                + routes.stream().map(PatrolRoute::signature).toList();
    }
}
