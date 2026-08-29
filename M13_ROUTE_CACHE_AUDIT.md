# M13/M13.1 Route Cache Audit

`FutureReadinessCalculator` builds one reverse opportunity table for each static
Udon spot during `forState(DayState)`. The outer cache key is the opportunity
spot position. Each table maps a projected PATROL start position to the best
known `(stepCost, patrolFuelCost)` pair under the bounded reverse search.

For a planning day with `S` configured Udon spots, the calculator performs
exactly `S` reverse searches. The number of cached entries is the sum of the
reachable start-position labels across those tables. Evaluating a complete
challenger plan only reads those cached entries using the simulator's final
PATROL positions and fuel; it does not invoke a route finder or perform a new
path search. Re-evaluating another challenger on the same calculator therefore
does not increase the pathfinding execution count.

Future ROAD timing uses optimistic `TrafficStatus.CLEAR` only. PATROL fuel is
still calculated from the official terrain source-cell movement cost via
`MovementRules.costFromSource`; the optimistic traffic assumption changes ROAD
step timing, not terrain-derived PATROL fuel semantics.

## M13.1 next-day harvest capacity

`NextDayHarvestCapacityCalculator.forState(DayState)` also builds exactly one
reverse Pareto search per static Udon opportunity. Its outer key is the goal
opportunity position. Each entry maps a traversable start position to its
non-dominated `(stepCost, patrolFuelCost)` labels. Opportunity-to-opportunity
legs are read from the same tables.

For each projected PATROL, complete-plan evaluation runs a deterministic sparse
subset DP over `(visitedMask,lastSpot)`. The raw state-shape bound is
`O(2^S * S)` for `S` static opportunities, plus cached-leg transitions. The
production exact-DP guard is `S <= 16`; all live-shaped M13.1 fixtures use
`S=8`, whose raw state-shape bound is `2^8 * 8 = 2048` per PATROL before Pareto
pruning. No Dijkstra is executed per complete challenger, per PATROL, or per DP
state.

Diagnostics report `routeCostCacheEntries`, `pathfindingExecutions`, and the
total DP-state visits. In the eight-opportunity fixtures,
`pathfindingExecutions=8` regardless of the number of complete plans evaluated.