# PTIT PROCON 2026 — HEXUDON Bot

Competition bot for PTIT PROCON 2026 — HEXUDON, organized as a Java 21 Maven
multi-module project.

## Requirements

- Java 21
- Maven 3.9 or later

## Modules

- `bot-domain`: immutable game and map domain models
- `bot-rules`: official game rules
- `bot-engine`: deterministic simulation
- `bot-planner`: pathfinding and planning algorithms
- `bot-protocol`: server protocol mappings
- `bot-runtime`: match lifecycle and submission management
- `bot-benchmark`: offline benchmarks and scenarios
- `bot-app`: application entry point

`bot-domain`, `bot-rules`, and `bot-engine` contain the current implementation.
The remaining modules establish build boundaries for later phases.

## Current Phase

`M2 — Exact Deterministic Day Simulator + Plan Validator`

This phase adds exact day simulation, fail-closed plan validation, Udon and
REFUEL execution, road occupancy contribution, and an immutable timeline
trace. Live server parity is deferred until protocol integration.

## Build

```bash
mvn clean test
```
