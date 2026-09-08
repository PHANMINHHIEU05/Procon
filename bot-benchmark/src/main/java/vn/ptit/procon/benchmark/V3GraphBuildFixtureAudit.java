package vn.ptit.procon.benchmark;

import vn.ptit.procon.planner.V3WorkAuditProbe;

/**
 * ITERATION 6 STEP 1b — the same causal audit, but over the frozen fixture cohort instead of the live corpus.
 *
 * <p>Runs the untouched {@link JointTeamBeamBenchmark} with the diagnostics probe enabled and prints one
 * aggregate report afterwards. The benchmark's own output is unaffected: the probe only reads and accumulates,
 * so the frozen fixture gate lines this run emits stay directly comparable with earlier iterations.
 */
public final class V3GraphBuildFixtureAudit {

    private V3GraphBuildFixtureAudit() {
    }

    public static void main(String[] args) {
        V3WorkAuditProbe.enable();
        try {
            JointTeamBeamBenchmark.main(args);
        } finally {
            V3WorkAuditProbe.disable();
            System.out.print(V3WorkAuditProbe.report("FROZEN_FIXTURE_COHORT"));
            System.out.flush();
        }
    }
}
