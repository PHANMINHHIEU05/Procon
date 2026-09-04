package vn.ptit.procon.runtime;

import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.planner.v3.V3ShadowEvaluation;

/**
 * The shadow boundary, as narrow as it can be made: a day state and the plan already submitted go in,
 * numbers come out.
 *
 * <p>There is deliberately no way to express a submission through this interface. It cannot see the HTTP
 * client, the encoder, the runtime state machine or {@code lastSubmittedDay}, and its return type carries
 * no plan, so no implementation — real, fake or future — can act on the match.
 *
 * <p>Implementations may throw anything; {@link V3ShadowRunner} catches at this boundary.
 */
@FunctionalInterface
public interface V3ShadowEvaluator {

    V3ShadowEvaluation evaluate(DayState state, TeamPlan submittedPlan) throws Exception;
}
