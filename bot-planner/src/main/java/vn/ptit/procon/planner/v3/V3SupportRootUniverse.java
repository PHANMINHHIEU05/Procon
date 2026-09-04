package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Planner;
import vn.ptit.procon.planner.v2.R3SupportRootExport;
import vn.ptit.procon.planner.v2.R3SupportRootExportSet;

/**
 * The V3 support-root universe: {@code NO_REFUEL} plus every support root the existing R3 planner
 * already retains for this day state.
 *
 * <p>PART 2: V3 adds nothing to this universe. It calls
 * {@link JointTeamBeamR3Planner#exportRetainedSupportRoots(DayState)} and adapts the result, so
 * {@link V3SupportRootUniverseAudit#v3GeneratedNewRefuelTours()} is structurally zero.
 */
public record V3SupportRootUniverse(List<V3SupportRootContext> roots, V3SupportRootUniverseAudit audit) {

    public V3SupportRootUniverse {
        roots = List.copyOf(Objects.requireNonNull(roots, "Support roots must not be null"));
        Objects.requireNonNull(audit, "Support root audit must not be null");
    }

    /** Builds the universe with the frozen default R3 configuration. */
    public static V3SupportRootUniverse of(DayState state) {
        return of(state, new JointTeamBeamR3Planner().exportRetainedSupportRoots(state));
    }

    /** PART 51/52: the ablation baseline. NO_REFUEL only, exactly the historical V3 universe. */
    public static V3SupportRootUniverse noRefuelOnly(DayState state) {
        Objects.requireNonNull(state, "Day state must not be null");
        return of(state, R3SupportRootExportSet.empty());
    }

    static V3SupportRootUniverse of(DayState state, R3SupportRootExportSet exported) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(exported, "Exported root set must not be null");
        List<V3SupportRootContext> roots = new ArrayList<>();
        roots.add(V3SupportRootContext.noRefuel());
        for (R3SupportRootExport export : exported.roots()) roots.add(V3SupportRootContext.of(state, export));
        return new V3SupportRootUniverse(List.copyOf(roots),
                V3SupportRootUniverseAudit.of(roots, exported));
    }

    public List<V3SupportRootContext> mobileRoots() {
        return roots.stream().filter(V3SupportRootContext::present).toList();
    }

    /** PART 22: the universe is searched generically; no signature is ever hardcoded. */
    public V3SupportRootContext bySignature(String signature) {
        return roots.stream().filter(root -> root.signature().equals(signature)).findFirst().orElse(null);
    }

    public V3SupportRootContext noRefuel() {
        return roots.stream().filter(root -> !root.present()).findFirst().orElseThrow();
    }
}
