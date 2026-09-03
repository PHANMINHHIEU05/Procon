package vn.ptit.procon.planner.v3;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.engine.TeamPlan;

/**
 * Canonical benchmark-only witness of one frozen V2/R3 selected {@link TeamPlan}.
 *
 * <p>Every field is captured from the authoritative validator/simulator trace, never rewritten by
 * hand.  The witness is diagnostic input for the V3 representability audits; it is never fed back
 * into V3 search.
 */
public record V2BaselineWitness(TeamPlan plan, boolean validatorAccepted, boolean simulatorValid,
        List<PatrolWitness> patrols, SupportWitness support, int ownSemiCollections, int ownSemiBrands,
        int coupledOwnCollections, int hybridMarginScore4, String physicalSignature,
        Map<Position, Integer> remainingStock) {

    public V2BaselineWitness {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(support);
        Objects.requireNonNull(physicalSignature);
        patrols = List.copyOf(patrols);
        remainingStock = Map.copyOf(remainingStock);
    }

    /** Collections credited to the trace, summed independently of the objective evaluator. */
    public int tracedCollections() {
        return patrols.stream().mapToInt(patrol -> patrol.claims().size()).sum();
    }

    public PatrolWitness patrol(AgentId patrolId) {
        return patrols.stream().filter(value -> value.patrolId().equals(patrolId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown patrol: " + patrolId.value()));
    }

    /** One PATROL's full authoritative day chronology. */
    public record PatrolWitness(AgentId patrolId, Position start, int startFuel, List<Position> movementRoute,
            List<Encounter> strategicEncounters, List<Claim> claims, List<BrandId> brands,
            List<FuelPoint> fuelChronology, Position finalPosition, int finalFuel, int lastMoveStep) {
        public PatrolWitness {
            Objects.requireNonNull(patrolId);
            movementRoute = List.copyOf(movementRoute);
            strategicEncounters = List.copyOf(strategicEncounters);
            claims = List.copyOf(claims);
            brands = List.copyOf(brands);
            fuelChronology = List.copyOf(fuelChronology);
        }

        /** Moves executed; all benchmark fixtures are PLAIN, so this equals fuel consumed. */
        public int moves() { return movementRoute.size() - 1; }

        public List<Position> claimedPositions() { return claims.stream().map(Claim::position).toList(); }
    }

    /** A spot cell this PATROL arrived at, whether or not the arrival produced a claim. */
    public record Encounter(int step, Position position, boolean claimed) {
        public Encounter { Objects.requireNonNull(position); }
    }

    public record Claim(int step, Position position, BrandId brand, int remainingStock) {
        public Claim { Objects.requireNonNull(position); Objects.requireNonNull(brand); }
    }

    /** One authoritative fuel transition; {@code cause} is MOVE or REFUEL. */
    public record FuelPoint(int step, Position position, int before, int after, String cause) {
        public FuelPoint { Objects.requireNonNull(position); Objects.requireNonNull(cause); }
    }

    /**
     * The selected R3 support root, reconstructed from {@code RefueledEvent}s.
     *
     * <p>{@code services} keeps the first refuel per PATROL, which is what the R3 root signature
     * commits to.  {@code incidentalTopUps} keeps every later refuel, which happens when the tanker
     * and the PATROL simply occupy the same cell again while both are moving.
     */
    public record SupportWitness(List<AgentId> refuelAgents, List<Position> starts,
            Map<AgentId, List<Position>> movementRoutes, List<ServiceEvent> services,
            List<ServiceEvent> incidentalTopUps, List<AgentId> supportedPatrols, String rootSignature) {
        public SupportWitness {
            refuelAgents = List.copyOf(refuelAgents);
            starts = List.copyOf(starts);
            movementRoutes = Map.copyOf(movementRoutes);
            services = List.copyOf(services);
            incidentalTopUps = List.copyOf(incidentalTopUps);
            supportedPatrols = List.copyOf(supportedPatrols);
            Objects.requireNonNull(rootSignature);
        }

        public int serviceCount() { return services.size(); }

        /** Positions where a refuel actually happened, service or top-up. */
        public List<Position> refuelPositions() {
            return java.util.stream.Stream.concat(services.stream(), incidentalTopUps.stream())
                    .map(ServiceEvent::position).distinct()
                    .sorted(Comparator.comparingInt(Position::value)).toList();
        }

        public java.util.Optional<ServiceEvent> serviceFor(AgentId patrolId) {
            return services.stream().filter(value -> value.patrolId().equals(patrolId)).findFirst();
        }
    }

    public record ServiceEvent(int step, AgentId patrolId, Position position, int before, int after,
            List<AgentId> providers) {
        public ServiceEvent {
            Objects.requireNonNull(patrolId); Objects.requireNonNull(position);
            providers = List.copyOf(providers);
        }
    }
}
