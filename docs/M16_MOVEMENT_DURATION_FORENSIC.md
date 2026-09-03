# M16 Movement-Duration / Traffic Forensic Result

## m-4992 authoritative failure

The supplied live evidence says that agent 3 had a local and wire-replay duration of 60, ended at
position 8 with patrol fuel 78, and was then rejected by the server at step 59 because a move needed
2 steps while only 1 remained. The historical request body for fingerprint `b64fa271` is not present
in the repository, so the exact rejected command cannot be reconstructed from that match.

## Protocol traffic audit

The setup contract contains the map and day budgets but no traffic thresholds. The state DTO contains
`traffics`, a list of objects with `pos` and `status`. `DayStateMapper` converts `status` codes
`0/1/2` to `CLEAR/CONGESTED/JAMMED` and stores each entry as `Position(pos) -> TrafficStatus`.
`DayState` rejects traffic attached to a non-ROAD cell.

No repository DTO, mapper, fixture, or live log contains a traffic time-step, day, edge, road-id, or
occupancy field. The current evidence supports a static sparse per-day position map only. It does not
support implementing dynamic traffic.

## Indexing and source-vs-destination audit

The mapping is direct by protocol position. It does not use the traffic array index, convert a row or
column, or shift for EVEN-R geometry. `HexMap` separately interprets `pos = row * width + column`
for terrain and neighbors. Movement code currently reads traffic from the source cell, while the
destination is checked only for map bounds and POND terrain. Tests cover sparse non-ordered positions,
row/column conversion, and a ROAD source whose destination has a different ROAD status.

There is no repository contract evidence proving destination-cell or edge traffic semantics. The
source-cell rule therefore remains unchanged and is reported as a local rule, not as an independently
proven server rule.

## Movement-duration provenance

`WireActionReplay` records command index, wire value, source and destination, source terrain, raw
traffic code, decoded traffic state, local start and end steps, local duration, and patrol fuel cost.
Its duration calculation shares `MovementRules` with `DaySimulator`; this is production-consistency
validation, not independent validation against a server oracle.

On an invalid action response, the runtime emits bounded `REJECTED_ACTION_ARRAY` records and
`WIRE_MOVEMENT_DURATION_TRACE` records. It safely extracts agent, server step, required steps, and
remaining steps from the reason when recognizable, while preserving the raw reason as authoritative.
No token or authorization header is included.

## ROAD alternate-cost mechanism

`WireMovementForensics` changes one ROAD status at a time among CLEAR, CONGESTED, and JAMMED for
ROAD moves already present in the replay. It reports local cost, alternate cost, alternate route
duration, and cumulative delta. It is bounded and diagnostic-only; it does not change planning,
replay, or submission semantics.

The exact-60 fixture demonstrates the mechanism: one CLEAR ROAD move costs 1 step and 2 fuel. The
remaining route costs 59 steps, for a local total of 60. Replaying that one ROAD move as CONGESTED
costs 2 steps and makes the route total 61, while fuel remains 2 for that move. This proves that
fuel parity cannot detect a traffic-duration disagreement; it does not prove that this caused m-4992.

## Traffic lifetime

`DayState` receives one traffic map for the current state, and both `DaySimulator` and wire replay
reuse it for the entire local day. No available protocol evidence indicates time-varying traffic,
simultaneous-car load, opponent occupancy, or another dynamic lifetime. No dynamic behavior was added.

## Root-cause status

**ROOT CAUSE UNRESOLVED:** m-4992 proves a local/server movement-duration divergence, but the
historical action array and authoritative server traffic interpretation are unavailable. The
repository does not prove a traffic index shift, destination/edge semantics, or dynamic traffic.

m-4703 may be the same failure family because it also reported an exact-local-boundary plan and a
server step-overflow rejection, but the incidents are not proven identical.

## Safety gates

The synchronized team REFUEL replay regression remains intact. M16 strategy and search settings are
unchanged, including 64/48/4 and 16/24/24. No budget reserve, JAMMED assumption, live request,
commit, or push was added.
