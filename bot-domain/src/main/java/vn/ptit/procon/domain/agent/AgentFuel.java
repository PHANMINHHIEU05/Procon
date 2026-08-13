package vn.ptit.procon.domain.agent;

/** Explicitly distinguishes finite PATROL fuel from REFUEL's unlimited fuel. */
public sealed interface AgentFuel permits FiniteFuel, UnlimitedFuel {
}