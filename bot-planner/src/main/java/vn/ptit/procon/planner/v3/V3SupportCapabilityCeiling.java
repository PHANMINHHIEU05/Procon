package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.engine.DayState;

/**
 * The capability ceiling of the frozen Phase 2.3 caps, measured by deleting fuel entirely.
 *
 * <p>This exists to answer one question honestly. A support root that lifts CURRENT LARGE from
 * {@code own=1} to {@code own=12} while the mandate hoped for {@code 14} could mean either of two very
 * different things: the support semantics are still incomplete, or the enumeration caps simply cannot
 * express fourteen collections whatever the fuel situation is. Filling every PATROL tank to capacity is a
 * strictly stronger intervention than any tanker trajectory could ever be — no schedule, no rendezvous, no
 * waiting, just free fuel forever — so whatever that run scores is an upper bound on what mobile support
 * can possibly recover under the same caps.
 *
 * <p>The lifted state is a diagnostic input only. It is never handed to a planner that produces a plan
 * this project would submit, and it grants fuel, which is exactly what PART 24 forbids in real V3
 * behaviour. That is the point: it is the control, not the treatment.
 */
public record V3SupportCapabilityCeiling(int oracleOwn, int oracleHybrid4, int oracleMaterializedPlans,
        int oracleValidPlans, int rawOwn, int rawHybrid4, int statesExpanded, int terminals,
        int materializedPlanCap) {

    public V3SupportCapabilityCeiling {
        if (materializedPlanCap <= 0) throw new IllegalArgumentException("Materialization cap must be positive");
    }

    /** Measures the ceiling with the same graph policy, oracle caps and search budget the real runs use. */
    public static V3SupportCapabilityCeiling measure(DayState state, StrategicSearchConfig config,
            V3RepresentationConfig oracleConfig) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(config, "Search config must not be null");
        Objects.requireNonNull(oracleConfig, "Oracle config must not be null");
        DayState lifted = fullTank(state);
        V3RepresentationResult oracle = new V3RepresentationOracle().solve(lifted, oracleConfig);
        StrategicSearchResult search = new StrategicTeamSearch().solve(lifted, config);
        return new V3SupportCapabilityCeiling(oracle.winner().ownSemiCollections(),
                oracle.winner().hybridMarginScore4(), oracle.diagnostics().materializedPlans(),
                oracle.diagnostics().validPlans(), search.rawWinner().ownSemiCollections(),
                search.rawWinner().hybridMarginScore4(), search.diagnostics().statesExpanded(),
                search.terminalSnapshots().size(), oracleConfig.maxMaterializedPlans());
    }

    /** The same day with every PATROL tank filled: the fixture with fuel removed as a constraint. */
    public static DayState fullTank(DayState state) {
        Objects.requireNonNull(state, "Day state must not be null");
        int capacity = state.matchData().patrolFuelCapacity().value();
        List<AgentState> agents = new ArrayList<>();
        for (AgentState agent : state.agents()) {
            agents.add(agent.kind() == AgentKind.PATROL
                    ? AgentState.patrol(agent.id(), agent.position(), capacity) : agent);
        }
        return new DayState(state.matchData(), state.day(), List.copyOf(agents), state.roadTraffic(),
                state.spotStock(), state.observedOthers());
    }

    /** True when the oracle exhausted its materialization budget, so its score is cap-bound. */
    public boolean oracleMaterializationSaturated() { return oracleMaterializedPlans >= materializedPlanCap; }

    /** True when a mobile support root recovered everything the fuel-free run could reach. */
    public boolean oracleCeilingReached(int supportedOwn) { return supportedOwn >= oracleOwn; }

    /** The same test for the bounded search, which is the figure PART 28 reports. */
    public boolean rawCeilingReached(int supportedRawOwn) { return supportedRawOwn >= rawOwn; }

    @Override
    public String toString() {
        return "ceiling oracle own=" + oracleOwn + "/h4=" + oracleHybrid4 + " materialized="
                + oracleMaterializedPlans + "/" + materializedPlanCap + " raw own=" + rawOwn + "/h4="
                + rawHybrid4 + " expanded=" + statesExpanded + " terminals=" + terminals;
    }
}
