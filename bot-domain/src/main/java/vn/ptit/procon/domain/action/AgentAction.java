package vn.ptit.procon.domain.action;

/** Domain action independent from protocol integer encoding. */
public sealed interface AgentAction permits MoveAction, WaitAction {
}