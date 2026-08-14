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
- `bot-planner`: reserved for future planning algorithms
- `bot-protocol`: server protocol mappings
- `bot-runtime`: match lifecycle and submission management
- `bot-benchmark`: offline benchmarks and scenarios
- `bot-app`: application entry point

`bot-domain`, `bot-rules`, `bot-engine`, `bot-protocol`, `bot-runtime`, and
`bot-app` contain the current implementation. The live bot deliberately uses no
competitive planning in M3.

## Current Phase

`M3 — Official HTTP Protocol + Match Runtime`

This phase adds the official authenticated HTTP lifecycle, Jackson DTO/domain
mapping, fail-closed submission orchestration, local fake-match integration,
and sanitized parity observations. The default and only M3 live plan is an
explicit full-day `WAIT` for every agent; it is a protocol smoke test, not a
gameplay strategy.

## Build

```bash
mvn clean test
mvn clean verify
```

## Run the all-WAIT smoke bot

Build the runnable Java 21 application:

```bash
mvn clean package
```

Set a practice match and token (never commit either value):

```bash
export PROCON_MATCH_ID="m-example"
export PROCON_TOKEN="<your-token>"

java -jar bot-app/target/procon-bot.jar
```

Optional settings:

```bash
export PROCON_BASE_URL="https://procon.ptit.edu.vn"
export PROCON_POLL_INTERVAL_MS="250"       # minimum 200
export PROCON_HTTP_TIMEOUT_SECONDS="15"
```

The token is sent only as `Authorization: Bearer <token>` and is redacted from
configuration output. Maven tests use an in-process fake HTTP server and never
contact the practice server.

Setup Udon `stocks` is mapped as each spot's capacity and, because the official
day state does not expose per-team stock and stock replenishes at each day's
start, that capacity is used as beginning-of-day team stock. Current ROAD
traffic always comes from `state.traffics`; it is never recomputed locally.
The live `/setup` payload does not provide traffic thresholds, so they are not
part of `StaticMatchData`. `TrafficThresholds` remains a standalone rules input
for future prediction or offline analysis when authoritative values exist.

## M3 parity limits

All-WAIT can observe only position persistence and PATROL fuel persistence.
Multi-step movement occupancy, start-of-day Udon collection, REFUEL timing,
ROAD stopped-step accounting, and simultaneous Udon stock ties remain
explicitly untested until controlled later practice probes.
