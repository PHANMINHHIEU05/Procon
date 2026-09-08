package vn.ptit.procon.benchmark;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.planner.Route;
import vn.ptit.procon.planner.v2.JointTeamBeamR3Planner;
import vn.ptit.procon.planner.v3.CachedTrajectoryEffect;
import vn.ptit.procon.planner.v3.FrozenObjectiveEvaluator;
import vn.ptit.procon.planner.v3.OpportunityRegion;
import vn.ptit.procon.planner.v3.StrategicAllocation;
import vn.ptit.procon.planner.v3.StrategicAllocationGenerator;
import vn.ptit.procon.planner.v3.StrategicChronologyReplay;
import vn.ptit.procon.planner.v3.StrategicOpportunity;
import vn.ptit.procon.planner.v3.StrategicOpportunityGraph;
import vn.ptit.procon.planner.v3.StrategicOpportunityGraphBuilder;
import vn.ptit.procon.planner.v3.StrategicOracleEvaluation;
import vn.ptit.procon.planner.v3.StrategicRouteCandidate;
import vn.ptit.procon.planner.v3.StrategicRoutePortfolio;
import vn.ptit.procon.planner.v3.StrategicSearchConfig;
import vn.ptit.procon.planner.v3.StrategicSearchObserver;
import vn.ptit.procon.planner.v3.StrategicSearchState;
import vn.ptit.procon.planner.v3.StrategicTeamComposition;
import vn.ptit.procon.planner.v3.StrategicTerminalSnapshot;
import vn.ptit.procon.planner.v3.StrategicTeamSearch;
import vn.ptit.procon.planner.v3.StrategicTrajectoryCache;
import vn.ptit.procon.planner.v3.SupportAwareSchedule;
import vn.ptit.procon.planner.v3.SupportAwareTrajectoryScheduler;
import vn.ptit.procon.planner.v3.TeamCompositionState;
import vn.ptit.procon.planner.v3.V2BaselineWitness;
import vn.ptit.procon.planner.v3.V2BaselineWitnessCapture;
import vn.ptit.procon.planner.v3.V3EdgeRetentionPolicy;
import vn.ptit.procon.planner.v3.V3ShadowPlanner;
import vn.ptit.procon.planner.v3.V3SupportPlanMaterializer;
import vn.ptit.procon.planner.v3.V3SupportRootContext;
import vn.ptit.procon.planner.v3.V3SupportRootUniverse;

public final class V3DivergenceDeepDive {

    private static class Prefix {
        final List<Position> targets;
        final List<Route> legs;
        final List<BrandId> brandSequence;
        final List<Integer> regionSequence;
        final List<Integer> arrivals;
        final int elapsed;
        final int fuel;
        final Set<Position> visited;
        final Set<BrandId> brands;
        final int collections;
        final boolean supportDependent;
        final String signature;

        Prefix(List<Position> targets, List<Route> legs, List<BrandId> brandSequence,
               List<Integer> regionSequence, List<Integer> arrivals, int elapsed, int fuel,
               Set<Position> visited, Set<BrandId> brands, int collections, boolean supportDependent,
               String signature) {
            this.targets = targets; this.legs = legs; this.brandSequence = brandSequence;
            this.regionSequence = regionSequence; this.arrivals = arrivals; this.elapsed = elapsed;
            this.fuel = fuel; this.visited = visited; this.brands = brands; this.collections = collections;
            this.supportDependent = supportDependent; this.signature = signature;
        }

        Position position(AgentState patrol) {
            return targets.isEmpty() ? patrol.position() : targets.getLast();
        }

        Prefix extend(DayState state, Map<Position, StrategicOpportunity> byPosition,
                      Map<Position, Integer> regions, Position target, Route leg, CachedTrajectoryEffect effect,
                      SupportAwareSchedule schedule) {
            List<Position> nextTargets = new ArrayList<>(targets); nextTargets.add(target);
            Set<Position> nextVisited = new LinkedHashSet<>(visited);
            effect.encounters().forEach(encounter -> nextVisited.add(encounter.position()));
            Set<BrandId> nextBrands = new LinkedHashSet<>(brands);
            BrandId brand = byPosition.containsKey(target) ? byPosition.get(target).brand() : null;
            if (brand != null) nextBrands.add(brand);
            int settled = (int) nextVisited.stream()
                    .filter(position -> state.spotStock().getOrDefault(position, 0) > 0).count();
            List<Route> nextLegs = new ArrayList<>(legs); nextLegs.add(leg);
            List<BrandId> nextBrandSeq = brand == null ? brandSequence : new ArrayList<>(brandSequence);
            if (brand != null) nextBrandSeq.add(brand);
            List<Integer> nextRegions = new ArrayList<>(regionSequence);
            nextRegions.add(regions.getOrDefault(target, -1));
            List<Integer> nextArrivals = new ArrayList<>(arrivals);
            nextArrivals.add(schedule.scheduledEndStep());
            return new Prefix(List.copyOf(nextTargets), List.copyOf(nextLegs), List.copyOf(nextBrandSeq),
                    List.copyOf(nextRegions), List.copyOf(nextArrivals), schedule.scheduledEndStep(),
                    schedule.fuelAfter(), Set.copyOf(nextVisited), Set.copyOf(nextBrands), settled,
                    supportDependent || schedule.usedSupport() || schedule.waited(),
                    nextTargets.stream().map(p -> Integer.toString(p.value())).reduce((a, b) -> a + ">" + b).orElse(""));
        }

        StrategicRouteCandidate candidate(AgentId patrolId, Position start) {
            return new StrategicRouteCandidate(patrolId, targets, legs, brandSequence, regionSequence,
                    arrivals, elapsed, fuel, start, targets.getLast(), collections, supportDependent,
                    signature);
        }
    }

    private static Comparator<Prefix> prefixOrder() {
        return Comparator.comparingInt((Prefix value) -> value.collections).reversed()
                .thenComparing(Comparator.comparingInt((Prefix value) -> value.brands.size()).reversed())
                .thenComparing(Comparator.comparingInt((Prefix value) -> value.targets.size()).reversed())
                .thenComparingInt(value -> value.elapsed)
                .thenComparing(value -> value.signature);
    }

    private static Comparator<StrategicRouteCandidate> routeOrder() {
        return Comparator.comparingInt(StrategicRouteCandidate::soloPotential).reversed()
                .thenComparing(Comparator.comparingInt((StrategicRouteCandidate value) -> value.brands().size()).reversed())
                .thenComparingInt(StrategicRouteCandidate::stepsUsed)
                .thenComparing(Comparator.comparingInt(StrategicRouteCandidate::endFuel).reversed())
                .thenComparing(Comparator.comparingInt(StrategicRouteCandidate::length).reversed())
                .thenComparing(StrategicRouteCandidate::signature);
    }

    private static boolean dominates(StrategicRouteCandidate left, StrategicRouteCandidate right) {
        return !left.signature().equals(right.signature())
                && left.soloPotential() >= right.soloPotential()
                && left.stepsUsed() <= right.stepsUsed()
                && left.endFuel() >= right.endFuel()
                && left.endPosition().equals(right.endPosition())
                && left.brands().containsAll(right.brands())
                && left.targetSet().containsAll(right.targetSet());
    }

    public static StrategicRoutePortfolio.Portfolio buildDiversifiedPortfolio(DayState state,
            StrategicOpportunityGraph graph, AgentState patrol, V3SupportRootContext root,
            SupportAwareTrajectoryScheduler scheduler, StrategicTrajectoryCache cache,
            int maxLength, int maxExpansions, int maxRetained) {
        Map<AgentId, Map<Position, Route>> entries = graph.entryRoutes(root.present());
        Map<Position, StrategicOpportunity> byPosition = new LinkedHashMap<>();
        graph.opportunities().forEach(value -> byPosition.put(value.position(), value));
        Map<Position, Integer> regions = new LinkedHashMap<>();
        for (OpportunityRegion region : graph.regions()) {
            region.members().forEach(member -> regions.putIfAbsent(member.position(), region.regionId()));
        }

        int startFuel = ((FiniteFuel) patrol.fuel()).amount();
        int startCollections = state.spotStock().getOrDefault(patrol.position(), 0) > 0 ? 1 : 0;
        Prefix origin = new Prefix(List.of(), List.of(), List.of(), List.of(), List.of(), 0, startFuel,
                Set.of(patrol.position()), Set.of(), startCollections, false, "");

        Map<Position, Route> myEntries = entries.getOrDefault(patrol.id(), Map.of());
        List<Position> entryTargets = new ArrayList<>(myEntries.keySet());

        List<StrategicRouteCandidate> generated = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(Set.of(""));
        int expansions = 0;

        List<Prefix> entryPrefixes = new ArrayList<>();
        for (Position target : entryTargets) {
            Route leg = myEntries.get(target);
            CachedTrajectoryEffect effect = cache.effect(patrol.id(), leg);
            SupportAwareSchedule schedule = scheduler.schedule(origin.position(patrol), origin.elapsed, origin.fuel, effect);
            if (!schedule.feasible()) continue;
            Prefix child = origin.extend(state, byPosition, regions, target, leg, effect, schedule);
            if (seen.add(child.signature)) {
                entryPrefixes.add(child);
                generated.add(child.candidate(patrol.id(), patrol.position()));
            }
        }

        int numBranches = entryPrefixes.size();
        int branchBudget = numBranches == 0 ? 0 : Math.max(1, maxExpansions / numBranches);
        List<Prefix> globalFrontier = new ArrayList<>();

        for (Prefix entryPrefix : entryPrefixes) {
            List<Prefix> branchFrontier = new ArrayList<>(List.of(entryPrefix));
            int branchExpansions = 0;
            while (!branchFrontier.isEmpty() && branchExpansions < branchBudget && expansions < maxExpansions) {
                branchFrontier.sort(prefixOrder());
                Prefix node = branchFrontier.removeFirst();
                expansions++;
                branchExpansions++;
                if (node.targets.size() >= maxLength) continue;
                List<Position> succs = graph.outgoing().getOrDefault(node.targets.getLast(), List.of()).stream()
                        .map(edge -> edge.to().position()).distinct().toList();
                for (Position target : succs) {
                    if (node.targets.contains(target)) continue;
                    Route leg = graph.opportunityRoutes().getOrDefault(node.targets.getLast(), Map.of()).get(target);
                    if (leg == null) continue;
                    CachedTrajectoryEffect legEffect = cache.effect(patrol.id(), leg);
                    SupportAwareSchedule legSchedule = scheduler.schedule(node.position(patrol), node.elapsed, node.fuel, legEffect);
                    if (!legSchedule.feasible()) continue;
                    Prefix child = node.extend(state, byPosition, regions, target, leg, legEffect, legSchedule);
                    if (!seen.add(child.signature)) continue;
                    branchFrontier.add(child);
                    generated.add(child.candidate(patrol.id(), patrol.position()));
                }
            }
            globalFrontier.addAll(branchFrontier);
        }

        while (!globalFrontier.isEmpty() && expansions < maxExpansions) {
            globalFrontier.sort(prefixOrder());
            Prefix node = globalFrontier.removeFirst();
            expansions++;
            if (node.targets.size() >= maxLength) continue;
            List<Position> succs = graph.outgoing().getOrDefault(node.targets.getLast(), List.of()).stream()
                    .map(edge -> edge.to().position()).distinct().toList();
            for (Position target : succs) {
                if (node.targets.contains(target)) continue;
                Route leg = graph.opportunityRoutes().getOrDefault(node.targets.getLast(), Map.of()).get(target);
                if (leg == null) continue;
                CachedTrajectoryEffect legEffect = cache.effect(patrol.id(), leg);
                SupportAwareSchedule legSchedule = scheduler.schedule(node.position(patrol), node.elapsed, node.fuel, legEffect);
                if (!legSchedule.feasible()) continue;
                Prefix child = node.extend(state, byPosition, regions, target, leg, legEffect, legSchedule);
                if (!seen.add(child.signature)) continue;
                globalFrontier.add(child);
                generated.add(child.candidate(patrol.id(), patrol.position()));
            }
        }

        List<StrategicRouteCandidate> ordered = new ArrayList<>(generated);
        ordered.sort(routeOrder());
        List<StrategicRouteCandidate> front = new ArrayList<>();
        int dominated = 0;
        for (StrategicRouteCandidate candidate : ordered) {
            if (front.stream().anyMatch(kept -> dominates(kept, candidate))) {
                dominated++;
                continue;
            }
            front.add(candidate);
        }

        List<StrategicRouteCandidate> retained = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (StrategicRouteCandidate candidate : front) {
            if (retained.size() >= maxRetained) break;
            if (keys.add(candidate.diversityKey())) retained.add(candidate);
        }
        for (StrategicRouteCandidate candidate : front) {
            if (retained.size() >= maxRetained) break;
            if (!retained.contains(candidate)) retained.add(candidate);
        }

        long keptKeys = retained.stream().map(StrategicRouteCandidate::diversityKey).distinct().count();
        long frontKeys = front.stream().map(StrategicRouteCandidate::diversityKey).distinct().count();
        return new StrategicRoutePortfolio.Portfolio(patrol.id(), retained, generated.size(), expansions, dominated,
                (int) Math.max(0, frontKeys - keptKeys), Math.max(0, front.size() - retained.size()), ordered);
    }

    public static void inspect(V3CorpusDay day) {
        System.out.println("================================================================================");
        System.out.printf("TESTING DIVERSIFIED PORTFOLIO COMPOSITION ON %s DAY %d%n", day.matchId(), day.day());
        System.out.println("================================================================================");

        DayState state = day.rebuild();
        TeamPlan incumbent = new JointTeamBeamR3Planner(new vn.ptit.procon.planner.v2.JointTeamBeamR3Config(
                30_000, 750, 64, 16, 24, 24, 4, 216, 48, 24, 12)).plan(state);
        V3SupportRootUniverse universe = V3SupportRootUniverse.of(state);
        StrategicSearchConfig config = V3ShadowPlanner.shadowConfig(V3CorpusReplay.UNCAPPED_BUDGET_MILLIS);
        StrategicOpportunityGraph graph = new StrategicOpportunityGraphBuilder()
                .build(state, V3EdgeRetentionPolicy.DIVERSE_GRAPH);

        List<AgentState> patrols = state.agents().stream()
                .filter(a -> a.kind() == AgentKind.PATROL)
                .sorted(Comparator.comparingInt(a -> a.id().value()))
                .toList();

        V2BaselineWitness witness = new V2BaselineWitnessCapture().capture(state, incumbent);
        String v2Sig = witness.support().rootSignature();
        System.out.printf("V2 WITNESS ROOT SIG: %s (in universe? %s)%n",
                v2Sig, universe.bySignature(v2Sig) != null);

        System.out.println("Spot stocks (position -> stock):");
        state.spotStock().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByValue(java.util.Comparator.reverseOrder()))
                .forEach(e -> System.out.printf("  pos=%d stock=%d%n", e.getKey().value(), e.getValue()));

        StrategicTeamComposition.Outcome outcome = new StrategicTeamComposition().run(
                state, config.withCompositionSearch(true), List.of(), 0, 0,
                StrategicSearchObserver.NONE, universe);
        System.out.printf("V3 RAW WINNER: Own=%d, Brands=%d, Hybrid=%d, Root=%s%n",
                outcome.search().rawWinner().ownSemiCollections(),
                outcome.search().rawWinner().ownSemiBrands(),
                outcome.search().rawWinner().hybridMarginScore4(),
                outcome.search().rawSupportRootSignature());
        var v2RootPortfolios = outcome.portfoliosByRoot().get(v2Sig);
        if (v2RootPortfolios != null) {
            System.out.println("Portfolios under V2 root " + v2Sig + ":");
            for (var pw : witness.patrols()) {
                String v2SigForPatrol = pw.claimedPositions().stream()
                        .map(p -> Integer.toString(p.value())).reduce((a, b) -> a + ">" + b).orElse("");
                var pf = v2RootPortfolios.get(pw.patrolId());
                boolean generated = pf != null && pf.generatedContains(v2SigForPatrol);
                boolean retained = pf != null && pf.contains(v2SigForPatrol);
                System.out.printf("  Patrol %d V2 witness route: sig=%s (claims=%d, gen=%b, ret=%b)%n",
                        pw.patrolId().value(), v2SigForPatrol, pw.claims().size(), generated, retained);
            }
        }

        DaySimulationResult v2Sim = new DaySimulator().simulate(state, incumbent);
        DaySimulationResult v3Sim = new DaySimulator().simulate(state, outcome.search().rawWinner().plan());
        if (v2Sim instanceof ValidDaySimulationResult v2Valid && v3Sim instanceof ValidDaySimulationResult v3Valid) {
            System.out.println("Per-patrol collections (Simulator):");
            for (AgentState p : patrols) {
                int c2 = v2Valid.portionsCollectedByAgent().getOrDefault(p.id(), 0);
                int c3 = v3Valid.portionsCollectedByAgent().getOrDefault(p.id(), 0);
                System.out.printf("  Patrol %d: V2=%d, V3=%d (delta=%+d)%n", p.id().value(), c2, c3, c3 - c2);
            }
            var evaluator = new FrozenObjectiveEvaluator(state);
            var v2Eval = evaluator.evaluate(incumbent).orElseThrow();
            var v3Eval = evaluator.evaluate(outcome.search().rawWinner().plan()).orElseThrow();
            var forecast = vn.ptit.procon.planner.OpponentCommitmentForecast.annotate(
                    new vn.ptit.procon.planner.OpponentIntentForecaster().forecast(state));
            var semiEval = new vn.ptit.procon.planner.SemiCommitmentForecastEvaluator();
            var weights = vn.ptit.procon.planner.SemiCommitmentAdjustmentWeights.defaults();
            var v2Attr = semiEval.evaluate(state, v2Valid, forecast, weights);
            var v3Attr = semiEval.evaluate(state, v3Valid, forecast, weights);
            System.out.printf("V2 Eval: ownSemiCollections=%d, hybridMarginScore4=%d%n",
                    v2Eval.ownSemiCollections(), v2Eval.hybridMarginScore4());
            System.out.printf("V3 Eval: ownSemiCollections=%d, hybridMarginScore4=%d%n",
                    v3Eval.ownSemiCollections(), v3Eval.hybridMarginScore4());
            for (var a : v3Attr.assessments()) {
                if (!a.semiCommitmentRealizable()) {
                    System.out.printf("  V3 Unrealized: spot=%d, step=%d, class=%s%n",
                            a.spot().value(), a.ourCollectionStep(), a.classification());
                }
            }
            for (var a : v2Attr.assessments()) {
                if (!a.semiCommitmentRealizable()) {
                    System.out.printf("  V2 Unrealized: spot=%d, step=%d, class=%s%n",
                            a.spot().value(), a.ourCollectionStep(), a.classification());
                }
            }

            System.out.println("V2 Claims:");
            for (var ev : v2Valid.events()) {
                if (ev instanceof vn.ptit.procon.engine.UdonCollectedEvent c) {
                    System.out.printf("  V2 Claim: agent=%d pos=%d step=%d brand=%s%n",
                            c.agentId().value(), c.position().value(), c.step(), c.brand().value());
                }
            }
            System.out.println("V3 Claims:");
            for (var ev : v3Valid.events()) {
                if (ev instanceof vn.ptit.procon.engine.UdonCollectedEvent c) {
                    System.out.printf("  V3 Claim: agent=%d pos=%d step=%d brand=%s%n",
                            c.agentId().value(), c.position().value(), c.step(), c.brand().value());
                }
            }
        }

        System.out.printf("Portfolios built for %d roots:%n", outcome.portfoliosByRoot().size());
        outcome.portfoliosByRoot().forEach((sig, ports) -> {
            System.out.printf("  Root: %s%n", sig);
            ports.forEach((id, p) -> {
                System.out.printf("    Patrol %d: gen=%d, ret=%d, keys=%d, maxColl=%d%n",
                        id.value(), p.generated(), p.retained(), p.distinctDiversityKeys(),
                        p.routes().stream().mapToInt(StrategicRouteCandidate::soloPotential).max().orElse(0));
                if (id.value() == 4 || id.value() == 0) {
                    System.out.printf("    Patrol %d ALL %d routes:%n", id.value(), p.routes().size());
                    for (int ri = 0; ri < p.routes().size(); ri++) {
                        var r = p.routes().get(ri);
                        System.out.printf("      [%d] sig=%s coll=%d elapsed=%d%n",
                                ri, r.signature(), r.soloPotential(), r.stepsUsed());
                    }
                }
            });
        });

        Map<String, List<StrategicTeamComposition.ComposedTeam>> completedByRoot = new LinkedHashMap<>();
        for (var ct : outcome.completed()) {
            completedByRoot.computeIfAbsent(ct.team().supportRoot().signature(), k -> new ArrayList<>()).add(ct);
        }
        System.out.printf("Completed teams by root:%n");
        completedByRoot.forEach((sig, list) -> {
            int maxColl = list.stream().mapToInt(c -> c.team().securedCollections()).max().orElse(0);
            System.out.printf("  Root %s: %d teams, maxCollections=%d%n", sig, list.size(), maxColl);
        });

        System.out.printf("Total completed teams: %d%n", outcome.completed().size());
        Map<String, Integer> routesCount = new LinkedHashMap<>();
        for (var ct : outcome.completed()) {
            routesCount.merge(ct.team().routeSignatures(), 1, Integer::sum);
        }
        System.out.printf("Distinct routeSignatures across all completed: %d%n", routesCount.size());

        List<StrategicTeamComposition.ComposedTeam> ranked = outcome.completed().stream()
                .sorted(Comparator.comparing(StrategicTeamComposition.ComposedTeam::team, StrategicTeamComposition.compositionOrder())).toList();

        System.out.printf("Top 15 completed teams by compositionOrder (Overlap/Dup):%n");
        for (int i = 0; i < Math.min(15, ranked.size()); i++) {
            var ct = ranked.get(i);
            System.out.printf("  [%d] Root=%s, StratColl=%d, Overlap=%d, Dup=%d%n    Routes: %s%n",
                    i, ct.team().supportRoot().signature(), ct.team().securedCollections(),
                    ct.team().routeOverlap(), ct.team().duplicatedStockDemand(),
                    ct.team().assigned().stream()
                            .map(r -> r.patrolId().value() + ":" + r.signature() + "(coll=" + r.soloPotential() + ")").toList());
        }

        System.out.printf("Materialized %d teams:%n", outcome.materialized().size());
        List<StrategicTerminalSnapshot> terminals = outcome.search().terminalSnapshots();
        for (int i = 0; i < Math.min(20, outcome.materialized().size()); i++) {
            var ct = outcome.materialized().get(i);
            int evalOwn = i < terminals.size() ? terminals.get(i).evaluation().ownSemiCollections() : -1;
            int evalBrands = i < terminals.size() ? terminals.get(i).evaluation().ownSemiBrands() : -1;
            int evalHybrid = i < terminals.size() ? terminals.get(i).evaluation().hybridMarginScore4() : -1;
            System.out.printf("  Mat #%d: Root=%s, StratColl=%d, EvalOwn=%d, EvalBrands=%d, EvalHybrid=%d, Dup=%d, Overlap=%d, Elapsed=%d%n",
                    i, ct.team().supportRoot().signature(), ct.team().securedCollections(),
                    evalOwn, evalBrands, evalHybrid,
                    ct.team().duplicatedStockDemand(), ct.team().routeOverlap(), ct.team().totalElapsed());
            System.out.println("    Routes: " + ct.team().assigned().stream()
                    .map(r -> r.patrolId().value() + ":" + r.signature() + "(coll=" + r.soloPotential() + ")").toList());
        }
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(V3CorpusReplay.DEFAULT_CORPUS_DIRECTORY);
        String targetMatch = args.length > 0 ? args[0] : "m-11800";
        int targetDay = args.length > 1 ? Integer.parseInt(args[1]) : 0;

        List<V3CorpusDay> days = V3CorpusDay.loadAll(root, true);
        for (V3CorpusDay day : days) {
            if (day.matchId().equals(targetMatch) && day.day() == targetDay) {
                inspect(day);
                return;
            }
        }
    }
}
