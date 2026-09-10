package vn.ptit.procon.planner;

import vn.ptit.procon.model.Model.Setup;

public record MatchContext(Setup setup, int[] roles, int opponents) {
    public MatchContext { roles = roles == null ? new int[0] : roles.clone(); }
}
