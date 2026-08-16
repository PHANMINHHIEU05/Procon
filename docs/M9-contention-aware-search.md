# M9 Current-Day Contention-Aware Search

## Evidence boundary

Match `m-2418` live-observed one stable outer group (`rawId=1`) with four
agents on days 0 through 3. Every observed agent supplied `pos`, `kind`, and
`fuel`; all positions were in bounds. Raw kinds were `[0,0,0,1]`. Kind-zero
fuel varied while kind-one remained 60, which supports but does not prove own
PATROL/REFUEL semantics. M9 therefore considers every valid observed agent and
keeps `rawId`, `rawKind`, and `fuel` neutral.

## Domain and mapping

`DayState` now contains immutable `observedOthers`. The protocol parser maps
the live shape to domain records with `Position`, and `DayStateMapper` drops
malformed or out-of-map observations without weakening strict own-agent
mapping. Missing observations map to an empty list.

## Geometry

`ContentionAnalyzer` converts EVEN-R offsets to cube coordinates:

```text
x = column - (row + (row & 1)) / 2
z = row
y = -x - z
distance = max(abs(dx), abs(dy), abs(dz))
```

For each Udon position it reports our nearest PATROL hex distance, nearest
observed-other hex distance, other counts within radii 1 and 2, and
`distanceAdvantage = otherDistance - ourDistance`. Classification is SAFE when
ours is smaller, TIED when equal, CONTESTED when ours is greater, and
UNOBSERVED when there are no valid observed agents. These are current-snapshot
geometric descriptions, not ETA or action prediction.

## Route guidance

Each candidate route examines every unique stocked Udon position encountered
by the route. The current search state's stock and the PATROL's visited set are
used, so consumed spots are not counted later. Route metrics count safe, tied,
contested, and strongly contested (`distanceAdvantage <= -2`) projected
collections.

Coverage ordering is team-new-brand, safe collections, total gain,
uncontested gain, density, steps, fuel, and deterministic identity. Harvest
ordering is uncontested gain, total gain, fewer strongly-contested
collections, density, steps, fuel, resulting fuel, and deterministic identity.
Top-K retention also preserves a low-contention candidate.

The frontier adds projected safe collections ahead of total collections while
retaining team brands, optimistic harvest potential, resources, and bounded
deterministic ordering. Final complete-plan comparison remains the unchanged
simulator-backed `PlanEvaluation`.

## Scope

There is no opponent route simulation, future action prediction, opponent stock
subtraction, minimax, Monte Carlo, parallel search, or multi-day optimization.
`ANYTIME_CONTENTION` falls back exactly to `ANYTIME_HARVEST` when there are no
valid observed agents.

## M9.1 attribution audit

The normal `CONTENTION_SUMMARY` line reports direct current-day spot
classification from `ContentionAnalyzer`: `safeSpots`, `tiedSpots`,
`contestedSpots`, and `unobservedSpots`. It is independent of search expansion
and therefore distinguishes an all-TIED or all-UNOBSERVED snapshot from a lost
search metric.

With `PROCON_CONTENTION_DIAGNOSTICS=true`, output is additionally bounded to
the first eight deterministically ordered spots and four retained candidates
for the whole planning call. It contains only public positions, distances,
classifications, and route metrics. The flag is false by default.

The contention metric path is:

```text
DayState.observedOthers
  -> ContentionAnalyzer.analyze
  -> ContentionMetrics
  -> ContentionAnalyzer.analyzeRoute
  -> RouteContentionMetrics
  -> ContentionCandidateMetrics
  -> CONTENTION candidate comparator and top-K retention
  -> SearchState child safe/tied/contested/strongly-contested totals
  -> CONTENTION frontier ordering
  -> completed TeamPlan
```

Route metrics are used only for search guidance. The completed plan remains
evaluated by the unchanged simulator-backed `PlanEvaluation`.

`ANYTIME_CONTENTION_DONE` now reports final returned-plan attribution. The
`safeProjected`, `tiedProjected`, `contestedProjected`,
`unobservedProjected`, and `stronglyContestedProjected` fields classify the
`UdonCollectedEvent` positions produced by simulating the returned plan against
the same current-day snapshot. They do not mean the last expanded state or
the last strict incumbent replacement.

The live zero-counter observation was therefore a logging/attribution bug:
the old counters started at zero and were updated only when a searched state
strictly replaced the M8.1 incumbent. A valid returned fallback plan could
collect contested or safe Udon while producing `safeProjected=0` and
`contestedProjected=0`. This did not demonstrate all-TIED behavior and did not
mean the contention comparator was unused.

The audit also corrected a state-propagation mismatch: the field named
`safeProjectedCollections` previously accumulated SAFE plus TIED route counts.
Search states now retain SAFE, TIED, CONTESTED, and strongly-contested counts
separately, while the unchanged contention frontier tuple compares true SAFE
collections at its existing priority position.