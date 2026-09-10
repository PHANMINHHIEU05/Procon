#!/usr/bin/env python3
"""Run a reproducible, resumable randomized PTIT Hard-Bot stability campaign.

One match is created and completed at a time, so a long local run never intentionally exceeds
the PTIT practice-match concurrency limit. Credentials are read only by the existing scripts from
the local secrets file; this runner neither accepts nor prints them.
"""
from __future__ import annotations

import argparse
import json
import os
import random
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import validation_matrix as matrix


@dataclass(frozen=True)
class Case:
    id: str
    profile: str
    size: int
    agents: int
    steps: int
    brands: int
    spots: int
    fuel_mult: int
    opponents: int
    days: int
    response_ms: int


# These ranges mix canonical competition shapes with safe generic fallbacks.  The server still
# generates a new random terrain/spot layout for every created match.
SHAPES: dict[str, dict[str, tuple[int, ...]]] = {
    "P08": {"size": (8,), "agents": (4, 6), "steps": (30, 45), "brands": (4, 6), "spots": (0, 8, 12)},
    "P12": {"size": (12,), "agents": (4, 6), "steps": (60, 80), "brands": (4, 6), "spots": (0, 10, 14)},
    "P16": {"size": (16,), "agents": (6, 8), "steps": (60, 80), "brands": (4, 6), "spots": (0, 12, 16)},
    "P24": {"size": (24,), "agents": (6, 8), "steps": (80, 100), "brands": (6, 8), "spots": (0, 12, 18)},
    "P32": {"size": (32,), "agents": (6, 8), "steps": (100, 120), "brands": (6, 8), "spots": (0, 16, 24)},
}
RESPONSE_WINDOWS = (200, 500, 1_000, 5_000)


def atomic_json(path: Path, value: Any) -> None:
    matrix.atomic_json(path, value)


def generate(count: int, seed: int) -> list[Case]:
    rng = random.Random(seed)
    profiles = tuple(SHAPES)
    cases: list[Case] = []
    for index in range(1, count + 1):
        profile = rng.choice(profiles)
        shape = SHAPES[profile]
        pick = lambda field: rng.choice(shape[field])
        cases.append(Case(
            id=f"random-{index:03d}", profile=profile, size=pick("size"), agents=pick("agents"),
            steps=pick("steps"), brands=pick("brands"), spots=pick("spots"),
            fuel_mult=rng.choice((0, 1, 2, 3)), opponents=rng.choice((1, 2, 3)),
            days=rng.choice((4, 4, 4, 5)), response_ms=rng.choice(RESPONSE_WINDOWS),
        ))
    return cases


def options(case: Case, args: argparse.Namespace) -> SimpleNamespace:
    return SimpleNamespace(
        days=case.days, opponents=case.opponents, response_ms=case.response_ms,
        fuel_mult=case.fuel_mult, size=case.size, agents=case.agents, steps=case.steps,
        brands=case.brands, spots=case.spots, url=args.url, transport=args.transport,
        role_mode="auto",
    )


def print_case(case: Case) -> None:
    print("RANDOM_CASE"
          f" id={case.id} profile={case.profile} size={case.size} agents={case.agents}"
          f" steps={case.steps} brands={case.brands} spots={case.spots or 'server'}"
          f" fuelMult={case.fuel_mult or 'server'} opponents={case.opponents} days={case.days}"
          f" responseMs={case.response_ms}")


def summary(state: dict[str, Any]) -> dict[str, int]:
    completed = [entry for entry in state["jobs"].values() if entry.get("status") == "completed"]
    return {
        "scheduled": len(state["jobs"]), "completed": len(completed),
        "wins": sum(entry["result"]["outcome"] == "WIN" for entry in completed),
        "draws": sum(entry["result"]["outcome"] == "DRAW" for entry in completed),
        "losses": sum(entry["result"]["outcome"] == "LOSS" for entry in completed),
        "faulted": sum(entry["result"]["faults"] != ["NONE"] for entry in completed),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Resumable randomized PTIT Hard-Bot stability runner.")
    parser.add_argument("--campaign", required=True, help="local campaign name; reuse it to resume")
    parser.add_argument("--matches", type=int, default=20, help="fixed number of seeded cases")
    parser.add_argument("--max-new", type=int, default=None, help="limit cases in this invocation (default: all)")
    parser.add_argument("--seed", type=int, default=20260910, help="random seed recorded in state")
    parser.add_argument("--transport", choices=("http", "auto", "ws"), default="http")
    parser.add_argument("--url", default="https://procon.ptit.edu.vn")
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args()
    if args.matches < 1 or (args.max_new is not None and args.max_new < 1):
        raise SystemExit("--matches and --max-new must be positive")

    campaign_dir = matrix.AUTOTUNE_HOME / "random-stability" / args.campaign
    state_path = campaign_dir / "state.json"
    state = matrix.load_json(state_path, {})
    if not state:
        cases = generate(args.matches, args.seed)
        state = {"campaign": args.campaign, "seed": args.seed, "cases": [asdict(case) for case in cases], "jobs": {}}
    elif state.get("seed") != args.seed or len(state.get("cases", [])) != args.matches:
        raise SystemExit("campaign already exists with a different --seed or --matches; choose a new --campaign")
    cases = [Case(**raw) for raw in state["cases"]]
    for case in cases:
        state["jobs"].setdefault(case.id, {"case": asdict(case), "status": "planned"})
    atomic_json(state_path, state)

    if not args.skip_build:
        build = matrix.run(["mvn", "-q", "package", "-DskipTests"])
        if build.returncode:
            print("RANDOM_STOP reason=BUILD_FAILED")
            print(build.stdout, end="")
            return 2

    limit = args.max_new if args.max_new is not None else len(cases)
    executed = 0
    for index, case in enumerate(cases):
        entry = state["jobs"][case.id]
        if entry.get("status") == "completed":
            continue
        if executed >= limit:
            break
        print_case(case)
        case_options = options(case, args)
        match_id = entry.get("matchId")
        if not match_id:
            # Keep each session below the factory's 12-match cap.
            session = f"random-{args.campaign}-{index // 12 + 1}"
            match_id, create_output = matrix.create_match(case, case_options, session)
            if match_id is None:
                entry.update({"status": "failed", "createOutput": create_output[-1_000:]})
                atomic_json(state_path, state)
                print(f"RANDOM_STOP case={case.id} reason=CREATE_FAILED")
                return 2
            entry.update({"status": "created", "matchId": match_id})
            atomic_json(state_path, state)

        existing = matrix.result_for(match_id, case.days, "")
        if existing is None:
            bot = matrix.run_bot(match_id, case_options)
            runtime_path = campaign_dir / f"{match_id}.runtime.log"
            runtime_path.write_text(bot.stdout, encoding="utf-8")
            existing = matrix.result_for(match_id, case.days, bot.stdout)
            if existing is None:
                entry.update({"status": "failed", "matchId": match_id, "botExit": bot.returncode})
                atomic_json(state_path, state)
                print(f"RANDOM_STOP case={case.id} matchId={match_id} reason=BOT_OR_RESULT_FAILED")
                return 2
        entry.update({"status": "completed", "matchId": match_id, "result": existing})
        atomic_json(state_path, state)
        matrix.print_result(case, match_id, existing)
        executed += 1

    report = {"campaign": args.campaign, "seed": state["seed"], "summary": summary(state),
              "cases": state["cases"], "jobs": state["jobs"]}
    atomic_json(campaign_dir / "report.json", report)
    print("RANDOM_SUMMARY " + json.dumps(report["summary"], sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
