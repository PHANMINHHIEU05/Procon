package vn.ptit.procon.benchmark;

import vn.ptit.procon.model.Model;
import vn.ptit.procon.planner.AdaptivePlanner;
import vn.ptit.procon.protocol.JsonProtocol;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Prints the clear-traffic Patrol-versus-one-Tanker composition forecast for one setup. */
public final class RoleRolloutMain {
    private RoleRolloutMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: RoleRolloutMain <setup.json>");
        Model.Setup setup = JsonProtocol.setup(Files.readString(Path.of(args[0])));
        AdaptivePlanner.RoleRolloutSummary summary = new AdaptivePlanner(setup).roleRolloutSummary();
        System.out.printf("ROLE_ROLLOUT map=%dx%d agents=%d patrol=%s patrolRoles=%s tanker=%s tankerRoles=%s delta=%d tankerBetter=%s%n",
                setup.map().width(), setup.map().height(), setup.agentCount(), summary.patrol(),
                Arrays.toString(summary.patrolRoles()), summary.tanker(), Arrays.toString(summary.tankerRoles()),
                summary.portionDelta(), summary.tankerStrictlyBetter());
    }
}
