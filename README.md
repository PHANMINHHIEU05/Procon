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

Only `bot-domain` and `bot-rules` contain game logic in the current development
phase. The remaining modules establish build boundaries for later phases.

## Current Phase

`M1 — Core Game Domain + Movement/Fuel/Traffic Rules`

This phase provides agent and action domain primitives, movement and fuel
rules, exact traffic classification, day configuration, and static match
concepts. The validated EVEN-R map foundation from M0 remains unchanged.

## Build

```bash
mvn clean test
```
