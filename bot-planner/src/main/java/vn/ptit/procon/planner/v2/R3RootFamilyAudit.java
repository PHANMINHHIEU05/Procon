package vn.ptit.procon.planner.v2;

import java.util.List;
import java.util.Optional;

/** Complete audit output. It is observational and never participates in normal R3 plan selection. */
public record R3RootFamilyAudit(
        R3RootFamilyAuditMode mode,
        List<R3RootFamilySummary> families,
        int globalRawTerminalCandidates,
        int globalUniquePhysicalTerminals,
        R3RootFamilyProvenance selectedProvenance,
        Integer selectedOwnSemiCollections,
        Integer bestNoRefuelOwnSemiCollections,
        Integer selectedGainVsNoRefuelSemi,
        Integer selectedHybridMarginScore4,
        Integer bestNoRefuelHybridMarginScore4,
        Integer selectedGainVsNoRefuelHybridScore4,
        Optional<R3SupportProductivity> supportProductivity,
        Optional<R3LiveFamilyAudit> liveShadowAudit,
        Optional<R3NoRefuelSearchAudit> noRefuelSearchAudit) {

    public R3RootFamilyAudit {
        families = List.copyOf(families);
        supportProductivity = supportProductivity == null ? Optional.empty() : supportProductivity;
        liveShadowAudit = liveShadowAudit == null ? Optional.empty() : liveShadowAudit;
        noRefuelSearchAudit = noRefuelSearchAudit == null ? Optional.empty() : noRefuelSearchAudit;
    }

    public static R3RootFamilyAudit empty() {
        return new R3RootFamilyAudit(R3RootFamilyAuditMode.OFF, List.of(), 0, 0,
                R3RootFamilyProvenance.noRefuel(), null, null, null, null, null, null, Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    public double globalPhysicalDedupRatio() {
        return globalRawTerminalCandidates == 0 ? 0.0
                : 1.0 - (double) globalUniquePhysicalTerminals / globalRawTerminalCandidates;
    }

    public R3RootFamilyAudit withSupportProductivity(R3SupportProductivity productivity) {
        return new R3RootFamilyAudit(mode, families, globalRawTerminalCandidates, globalUniquePhysicalTerminals,
                selectedProvenance, selectedOwnSemiCollections, bestNoRefuelOwnSemiCollections,
                selectedGainVsNoRefuelSemi, selectedHybridMarginScore4, bestNoRefuelHybridMarginScore4,
                selectedGainVsNoRefuelHybridScore4, Optional.ofNullable(productivity), liveShadowAudit,
                noRefuelSearchAudit);
    }

    public R3RootFamilyAudit withNoRefuelSearchAudit(R3NoRefuelSearchAudit value) {
        return new R3RootFamilyAudit(mode, families, globalRawTerminalCandidates, globalUniquePhysicalTerminals,
                selectedProvenance, selectedOwnSemiCollections, bestNoRefuelOwnSemiCollections,
                selectedGainVsNoRefuelSemi, selectedHybridMarginScore4, bestNoRefuelHybridMarginScore4,
                selectedGainVsNoRefuelHybridScore4, supportProductivity, liveShadowAudit,
                Optional.ofNullable(value));
    }
}
