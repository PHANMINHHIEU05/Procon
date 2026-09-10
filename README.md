# PTIT PROCON 2026 bot

This repository contains one Java 21 shaded production JAR. The runtime defaults to the stable HTTP match API; setting `PROCON_WS_FIRST=true` enables an experimental WebSocket-first transport with HTTP fallback. Credentials are read only from `PROCON_TOKEN`.

## Build and offline checks

```bash
mvn test
mvn package -DskipTests
java -cp target/procon-bot.jar vn.ptit.procon.benchmark.CorpusReplayMain
java -cp target/procon-bot.jar vn.ptit.procon.benchmark.PlannerBenchmarkMain
```

The replay harness reads the accepted corpus from `~/.procon-autotune/corpus` by default and reports position/fuel parity. The benchmark reports p50/p95/max planner latency without opening a network connection.

## Run a match

```bash
PROCON_TOKEN=... tools/procon_autotune/run_bot.sh \
  --url https://procon.ptit.edu.vn --match m-xxxxx --transport auto --response-ms 5000
```

`--role-mode auto` is production and uses six Patrols. `--role-mode five-one` is an
experimental 5-Patrol/1-Tanker A/B challenger; do not use it in production unless it
beats `auto` on the target match matrix.

For the validated 8x8, 6-agent, 30-step shape, the production selector uses a fuel-paced
harvest arm: it keeps a small per-Patrol reserve on days 0–2 so the final day remains productive.
It won all five three-Hard-Bot holdout matches; the former portfolio is retained automatically for
other 8x8 shapes, including a four-agent configuration. Set `PROCON_P08_FUEL_PACED=false` only for
an explicit baseline comparison.

The exact factory P24 shape (24x24, eight Patrols, four 100-step days, fuel 200) also includes a
separate fuel-paced arm with a 40-unit future-day reserve. It improved four of fourteen P24 journal
replays without regressing the other ten and completed a fault-free 5/5 three-Hard-Bot confirmation.
This arm is deliberately not inherited by arbitrary P24-tier maps; set `PROCON_P24_FUEL_PACED=false`
only for an explicit emergency rollback.

P32 adds a fourth coverage-first construction to the otherwise density/proximity/chain portfolio.
It cleared a five-match, three-Hard-Bot P32 confirmation at 5/5 rank one after the preceding matrix
was 3/5. The addition is intentionally scoped to P32 so it cannot perturb the independently validated
P24 portfolio; set `PROCON_P32_EXTRA_LARGE_ARM=false` for an emergency rollback.

The canonical P12/P16 (12x12 or 16x16, six Patrols, fuel 120) and P32 (32x32, eight Patrols, fuel 200)
shapes use a stock-capacity tie-break bonus of 25 in their joint auction. P12 improved or tied all six
independent journal replays and cleared a fault-free 5/5 three-Hard-Bot confirmation; P16 and P32 also
cleared fault-free 5/5 confirmations. Set `PROCON_CAPACITY_SLOT_BONUS=0` for an emergency rollback.

The exact P24 24×24/six-agent/five-day/80-step/fuel-160 long-horizon shape starts one tanker. Its
matched live baseline was 0/5 while 5+1 tanker reached 2/5 and retained full daily coverage in all five
trials. The normal eight-agent/four-day P24 champion is unaffected. Set
`PROCON_P24_LONG_HORIZON_TANKER=false` for an emergency rollback.

P24 also includes a capacity-aware density arm beside (not in place of) its champion portfolio. Exact
replay can retain the ordinary arm on maps where capacity weighting is harmful; the isolated arm cleared
a fault-free 5/5 three-Hard-Bot confirmation. Set `PROCON_P24_CAPACITY_DIVERSIFIER=false` to roll back.

The canonical P12 shape (12x12, six Patrols, 60 steps/day) uses a bounded exact-DP suffix repair
on the final day. It is exact-replayed before acceptance, so it can only replace the incumbent on a
strict score improvement; its 29-journal gate had no regression and its five three-Hard-Bot holdout
matches all won. Set `PROCON_P12_FINAL_DAY_EXACT_DP=false` only for an explicit emergency rollback.

For four-or-more-agent multi-day matches where fuel capacity is no larger than one day's step budget,
the assignment policy reserves one tanker automatically. This low-fuel rule changed a five-profile
fuel-multiplier holdout from 0/5 to 5/5 wins, and P08 four-agent low-fuel from a coverage loss to
4/5 wins, while restoring daily coverage; the normal fuel presets remain all-Patrol. Set
`PROCON_LOW_FUEL_TANKER=false` only for an explicit emergency rollback.

The factory P08 four-agent shape uses the same final-day exact-DP safeguard, separately calibrated
from P08 six-agent. It improved two of five replayed maps without a regression and won all five
three-Hard-Bot holdout matches; set `PROCON_P08_4_FINAL_DAY_EXACT_DP=false` only for rollback.

Runtime journals contain parsed setup/state/plan/result data under `~/.procon-bot/matches/<matchId>/`; tokens are never written there.

## Create practice configurations

The factory remains a separate utility and does not require the bot JAR:

```bash
tools/procon_autotune/create_match.sh --profile P08 --size 8 --agents 6 --steps 30 --opponents 3 --days 4 --difficulty hard --dry-run
tools/procon_autotune/create_match.sh --profile P12 --size 12 --agents 6 --steps 60 --opponents 3 --days 4 --difficulty hard --dry-run
tools/procon_autotune/create_match.sh --profile P16 --size 16 --agents 6 --steps 60 --opponents 3 --days 4 --difficulty hard --dry-run
```

When the website session expires, update only its cookie without exposing it in shell history or
logs:

```bash
bash tools/procon_autotune/update_session.sh
```

The script reads the fresh `HEXSESSION` through a hidden prompt, preserves `PROCON_TOKEN`, and
keeps the secrets file mode at `0600`. It also accepts a copied `hexsession:"…"` or
`hexsession=…` wrapper and saves only the actual cookie value.
`create_match.sh` uses that managed file by default even if the current terminal still has an old
`HEXSESSION`; set `PROCON_USE_ENV_HEXSESSION=true` only when intentionally testing an override.

To validate one frozen production JAR across the whole practice matrix, use the resumable runner.
It respects the factory cooldown/session cap, records only non-sensitive runtime logs, and ranks
against the strongest opponent by the official lexicographic tuple:

```bash
python3 tools/procon_autotune/validation_matrix.py \
  --campaign global-hardening-v1 \
  --profiles P08,P12,P16,P24,P32 \
  --rounds 5 --opponents 3 --max-new 25
```

The Java planner has frozen profiles P08 (6-agent/30-step), P08_4 (4-agent/30-step), P12/P16/P24/P32 and a bounded portfolio of independent route constructors: coverage-first DP, stock-density, nearest-first and two-hop rolling-horizon harvest. The rolling-horizon arm additionally spends a bounded budget on multi-label Pareto Dijkstra, exposing fast and fuel-sparing alternatives for its two best first-hop targets. Their plans are exact-replayed and compared by the official tuple: global types, daily type sum, portions, then response time. At score ties, the planner favors fewer unopened types and a healthier minimum Patrol fuel reserve.

`PROCON_TIGHT_RESPONSE_PLANNING=true` is an unpromoted low-latency trial. It only changes maps
at least 16×16 with a 750–1,000ms window, raising route-search time while retaining a 350ms network
reserve; all P08/P12 and five-second production matches retain the established timing policy.

The exact P16 16×16/six-agent/five-day/80-step/fuel-240 and P32
32×32/eight-agent/five-day/120-step/fuel-360 shapes each cleared a 3/3 one-second live holdout,
so they receive this timing policy by default. It has no effect on the canonical four-day profiles.

When a six-Patrol portfolio plan leaves daily stock uncollected, a final bounded destroy/repair neighbourhood may rebuild one route suffix around higher-stock targets. Every repair is exact-replayed and can replace the incumbent only on a strict lexicographic projection improvement; full-harvest plans and tanker compositions skip this work entirely.

For the promoted P08 6-agent/30-step shape, that local repair is a bounded exact route-DP over the six best harvest targets and four hops. It is not a full global ALNS: its output is still exact-replayed and may replace the incumbent only on strict projection improvement.
