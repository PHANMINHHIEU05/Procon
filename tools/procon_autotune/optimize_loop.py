#!/usr/bin/env python3
"""Resumable 75-match minimax campaign for the adaptive PTIT Procon bot."""
from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import statistics
import subprocess
import sys
import urllib.request
from collections import defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
CREATE_AND_RUN = ROOT / "tools" / "procon_autotune" / "create_and_run.sh"
DEFAULT_HOME = Path(os.path.expanduser("~/.procon-autotune"))
PROFILES = ("P08", "P12", "P16", "P24", "P32")
OPPONENTS = (1, 2, 3)
PHASE_REPETITIONS = (("baseline", 2), ("challenger", 2), ("confirm", 1))
FINAL_RE = re.compile(r"^FINAL .* result=(\{.*\})$")
MATCH_RE = re.compile(r"matchId=(m-[A-Za-z0-9._-]+)")
PLANNING_RE = re.compile(r"wallPlanningMillis=(\d+)")
PROFILE_RE = re.compile(r"R3_ADAPTIVE_PROFILE .* profile=([^ ]+).* fingerprint=([^ ]+)")


@dataclass(frozen=True)
class Job:
    slot: int
    phase: str
    repetition: int
    profile: str
    opponents: int

    @property
    def key(self) -> str:
        return f"{self.slot:02d}-{self.phase}-{self.repetition}-{self.profile}-o{self.opponents}"


def schedule() -> list[Job]:
    jobs: list[Job] = []
    slot = 0
    for phase, repetitions in PHASE_REPETITIONS:
        for repetition in range(1, repetitions + 1):
            for profile in PROFILES:
                for opponents in OPPONENTS:
                    slot += 1
                    jobs.append(Job(slot, phase, repetition, profile, opponents))
    assert len(jobs) == 75
    return jobs


def atomic_json(path: Path, value: Any) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)


def load_json(path: Path, fallback: Any) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return fallback


def load_external_secrets(home: Path, environment: dict[str, str]) -> None:
    """Load simple shell assignments without ever printing or persisting their values."""
    path = home / ".secrets.env"
    if not path.is_file():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.removeprefix("export ").split("=", 1)
        key = key.strip()
        if key not in {"HEXSESSION", "PROCON_TOKEN"} or environment.get(key):
            continue
        parsed = shlex.split(value, comments=False, posix=True)
        if parsed:
            environment[key] = parsed[0]


def parse_result(output: str) -> tuple[str, dict[str, Any]]:
    match = MATCH_RE.search(output)
    finals = [item for line in output.splitlines() if (item := FINAL_RE.match(line.strip()))]
    if not match or not finals:
        raise RuntimeError("match completed without a parseable FINAL result")
    return match.group(1), json.loads(finals[-1].group(1))


def score(result: dict[str, Any], own_team_id: str) -> dict[str, Any]:
    standings = result.get("standings") or []
    own_rows = [row for row in standings if row.get("team_id") == own_team_id]
    if len(own_rows) != 1:
        raise RuntimeError(f"result has no unique standing for {own_team_id}")
    own_row = own_rows[0]
    opponents = [row for row in standings if row.get("team_id") != own_team_id]
    if not opponents:
        raise RuntimeError("result has no opponent standings")
    strongest = max(opponents, key=lambda row: int(row.get("udon_total", 0)))
    own = int(own_row.get("udon_total", 0))
    other = int(strongest.get("udon_total", 0))
    outcome = "WIN" if own > other else "DRAW" if own == other else "LOSS"
    points = 1.0 if outcome == "WIN" else 0.5 if outcome == "DRAW" else 0.0
    return {
        "outcome": outcome,
        "points": points,
        "own": own,
        "strongestOpponent": other,
        "strongestOpponentTeam": strongest.get("team_id"),
        "margin": own - other,
        "normalizedMargin": (own - other) / max(1, other),
        "rank": int(own_row.get("rank", len(standings))),
        "standings": standings,
    }


def inspect_runtime(log_path: Path, expected_days: int = 4) -> dict[str, Any]:
    text = log_path.read_text(encoding="utf-8", errors="replace") if log_path.is_file() else ""
    planning = [int(value) for value in PLANNING_RE.findall(text)]
    profiles = [{"name": name, "fingerprint": fingerprint}
                for name, fingerprint in PROFILE_RE.findall(text)]
    accepted = len(re.findall(r"\bACTIONS_ACCEPTED\b", text))
    rejected = len(re.findall(r"ACTION(?:S)?_(?:REJECTED|INVALID)|valid=false", text))
    fallbacks = len(re.findall(r"fallbackUsed=true", text))
    deadlines = len(re.findall(r"planningDeadlineTriggered=true|deadlinePhase=(?!NONE\b)[A-Z_]+", text))
    wait_only = len(re.findall(r"waitOnlyWasBest=true", text))
    selected_support = [int(value) for value in re.findall(r"selectedSupportServiceCount=(\d+)", text)]
    reposition = len(re.findall(r"STRATEGIC_REPOSITION", text))
    faults: list[str] = []
    if accepted != expected_days:
        faults.append("INCOMPLETE_ACCEPTANCE")
    if rejected:
        faults.append("ACTION_REJECTION")
    if deadlines:
        faults.append("DEADLINE")
    if fallbacks:
        faults.append("FALLBACK")
    return {
        "acceptedDays": accepted,
        "rejections": rejected,
        "fallbacks": fallbacks,
        "deadlines": deadlines,
        "waitOnlyDays": wait_only,
        "selectedSupportServices": selected_support,
        "repositionEvents": reposition,
        "planningMillis": planning,
        "profiles": profiles,
        "faults": faults,
    }


def classify_loss(scored: dict[str, Any], runtime: dict[str, Any]) -> list[str]:
    if scored["outcome"] != "LOSS":
        return []
    if runtime["deadlines"]:
        return ["DEADLINE"]
    if runtime["fallbacks"]:
        return ["FALLBACK"]
    if runtime["waitOnlyDays"]:
        return ["WAIT_HEAVY"]
    if any(value > 0 for value in runtime["selectedSupportServices"]):
        return ["REFUEL_OR_SUPPORT"]
    if runtime["repositionEvents"] == 0:
        return ["STAGING_OR_UNCLUMPING"]
    return ["MISSED_STOCK_OR_ALLOCATION"]


def walk(value: Any):
    yield value
    if isinstance(value, dict):
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def fetch_daily_margins(match_id: str, home: Path, environment: dict[str, str],
                         own_team_id: str) -> list[dict[str, Any]]:
    cookie = environment.get("HEXSESSION")
    if not cookie:
        return []
    request = urllib.request.Request(
        f"https://procon.ptit.edu.vn/view/matches/{match_id}",
        headers={"Cookie": "hexsession=" + cookie, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except Exception:  # Network diagnostics must never invalidate an otherwise completed match.
        return []
    replay_dir = home / "replays"
    replay_dir.mkdir(parents=True, exist_ok=True)
    atomic_json(replay_dir / f"{match_id}.json", payload)
    days: dict[int, dict[str, Any]] = {}
    for node in walk(payload):
        if not isinstance(node, dict) or not isinstance(node.get("standings"), list):
            continue
        day = node.get("day")
        if not isinstance(day, int):
            continue
        try:
            days[day] = {"day": day, **score({"standings": node["standings"]}, own_team_id)}
        except RuntimeError:
            continue
    return [days[key] for key in sorted(days)]


def percentile95(values: list[int]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return float(ordered[min(len(ordered) - 1, max(0, int(0.95 * len(ordered)) - 1))])


def phase_summary(records: list[dict[str, Any]], phases: set[str]) -> dict[str, Any]:
    selected = [row for row in records if row["phase"] in phases and row.get("status") == "complete"]
    cells: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in selected:
        cells[f'{row["profile"]}-o{row["opponents"]}'].append(row)
    cell_rows: dict[str, Any] = {}
    for key, rows in sorted(cells.items()):
        points = statistics.mean(row["points"] for row in rows)
        margin = statistics.mean(row["normalizedMargin"] for row in rows)
        cell_rows[key] = {
            "matches": len(rows),
            "wins": sum(row["outcome"] == "WIN" for row in rows),
            "draws": sum(row["outcome"] == "DRAW" for row in rows),
            "losses": sum(row["outcome"] == "LOSS" for row in rows),
            "points": points,
            "normalizedMargin": margin,
        }
    all_planning = [value for row in selected for value in row["runtime"]["planningMillis"]]
    return {
        "matches": len(selected),
        "cells": cell_rows,
        "worstCellPoints": min((row["points"] for row in cell_rows.values()), default=None),
        "worstCellNormalizedMargin": min(
            (row["normalizedMargin"] for row in cell_rows.values()), default=None),
        "overallPoints": statistics.mean((row["points"] for row in selected)) if selected else None,
        "p95PlanningMillis": percentile95(all_planning),
        "faults": sum(len(row["runtime"]["faults"]) for row in selected),
    }


def promotion_report(records: list[dict[str, Any]]) -> dict[str, Any]:
    baseline = phase_summary(records, {"baseline"})
    challenger = phase_summary(records, {"challenger", "confirm"})
    complete = baseline["matches"] == 30 and challenger["matches"] == 45
    no_faults = challenger["faults"] == 0
    timing = (challenger["p95PlanningMillis"] is not None
              and challenger["p95PlanningMillis"] <= 3600)
    comparable = baseline["worstCellPoints"] is not None and challenger["worstCellPoints"] is not None
    worst_not_lower = (comparable
                       and challenger["worstCellPoints"] >= baseline["worstCellPoints"])
    worst_better = (comparable
                    and (challenger["worstCellPoints"] > baseline["worstCellPoints"]
                         or challenger["worstCellNormalizedMargin"]
                         > baseline["worstCellNormalizedMargin"]))
    aggregate = (baseline["overallPoints"] is not None and challenger["overallPoints"] is not None
                 and challenger["overallPoints"] >= baseline["overallPoints"] - 0.02)
    promoted = complete and no_faults and timing and worst_not_lower and worst_better and aggregate
    return {
        "complete": complete,
        "promoted": promoted,
        "production": "adaptive-challenger" if promoted else "fixed-baseline",
        "gates": {
            "noFaults": no_faults,
            "p95AtMost3600ms": timing,
            "worstCellNotLower": worst_not_lower,
            "worstCellStrictlyBetterByPointsOrMargin": worst_better,
            "overallWithinTwoPoints": aggregate,
        },
        "baseline": baseline,
        "challenger": challenger,
    }


def candidate_environment(job: Job, base: dict[str, str]) -> dict[str, str]:
    environment = base.copy()
    environment["PROCON_ADAPTIVE_R3"] = "false" if job.phase == "baseline" else "true"
    environment["PROCON_AUTOTUNE_SESSION"] = environment["CAMPAIGN_SESSION"]
    return environment


def run_job(job: Job, campaign: str, home: Path, state: dict[str, Any],
            own_team_id: str) -> dict[str, Any]:
    attempts = int(state.get("attempts", 0))
    state["attempts"] = attempts + 1
    batch = attempts // 12
    state["activeJob"] = job.key
    state["activeSession"] = f"{campaign}-b{batch:02d}"
    atomic_json(home / "campaign.json", state)

    environment = os.environ.copy()
    load_external_secrets(Path(environment.get("PROCON_AUTOTUNE_HOME", DEFAULT_HOME)), environment)
    environment["CAMPAIGN_SESSION"] = state["activeSession"]
    environment = candidate_environment(job, environment)
    command = ["bash", str(CREATE_AND_RUN), "--profile", job.profile,
               "--opponents", str(job.opponents), "--difficulty", "hard",
               "--days", "4", "--response-ms", "5000"]
    completed = subprocess.run(command, cwd=ROOT, env=environment, text=True,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
    log_path = home / "runs" / f"{job.key}.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.write_text(completed.stdout, encoding="utf-8")
    match_id, result = parse_result(completed.stdout)
    scored = score(result, own_team_id)
    runtime_log = Path(environment.get("PROCON_AUTOTUNE_HOME", DEFAULT_HOME)) / "logs" / match_id / "runtime.log"
    runtime = inspect_runtime(runtime_log)
    record = {
        **asdict(job),
        "key": job.key,
        "status": "complete" if completed.returncode == 0 else "runtime-error",
        "exitCode": completed.returncode,
        "matchId": match_id,
        **scored,
        "runtime": runtime,
        "lossCauses": classify_loss(scored, runtime),
        "dailyMargins": fetch_daily_margins(match_id,
                                             Path(environment.get("PROCON_AUTOTUNE_HOME", DEFAULT_HOME)),
                                             environment, own_team_id),
        "log": str(log_path),
        "runtimeLog": str(runtime_log),
    }
    return record


def main() -> int:
    parser = argparse.ArgumentParser(description="Run/resume the 75-match adaptive minimax campaign")
    parser.add_argument("--campaign", required=True, help="durable campaign and session prefix")
    parser.add_argument("--home", help="campaign output directory; defaults under autotune home")
    parser.add_argument("--own-team-id", default="team-A")
    parser.add_argument("--max-new", type=int, default=75,
                        help="maximum new matches in this invocation; resume runs skip completed jobs")
    parser.add_argument("--report-only", action="store_true")
    args = parser.parse_args()

    campaign_home = Path(args.home) if args.home else DEFAULT_HOME / "optimizer" / args.campaign
    campaign_home.mkdir(parents=True, exist_ok=True)
    state_path = campaign_home / "campaign.json"
    records_path = campaign_home / "results.jsonl"
    state = load_json(state_path, {"campaign": args.campaign, "attempts": 0, "completed": []})
    records: list[dict[str, Any]] = []
    if records_path.is_file():
        for line in records_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                records.append(json.loads(line))
    completed_keys = {row["key"] for row in records if row.get("status") == "complete"}

    if not args.report_only:
        started = 0
        for job in schedule():
            if job.key in completed_keys:
                continue
            if started >= args.max_new:
                break
            try:
                record = run_job(job, args.campaign, campaign_home, state, args.own_team_id)
            except Exception as error:  # Persist progress and stop; resume is safe.
                state["lastError"] = f"{type(error).__name__}: {error}"
                atomic_json(state_path, state)
                print(f"CAMPAIGN_STOP job={job.key} reason={type(error).__name__}", file=sys.stderr)
                return 2
            with records_path.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(record, sort_keys=True) + "\n")
            records.append(record)
            completed_keys.add(job.key)
            state["completed"] = sorted(completed_keys)
            state["activeJob"] = None
            state.pop("lastError", None)
            atomic_json(state_path, state)
            atomic_json(campaign_home / "report.json", promotion_report(records))
            print("CAMPAIGN_RESULT job=%s matchId=%s outcome=%s own=%d strongest=%d margin=%d faults=%s"
                  % (job.key, record["matchId"], record["outcome"], record["own"],
                     record["strongestOpponent"], record["margin"],
                     ",".join(record["runtime"]["faults"]) or "NONE"), flush=True)
            started += 1

    report = promotion_report(records)
    atomic_json(campaign_home / "report.json", report)
    print("CAMPAIGN_SUMMARY " + json.dumps({
        "completed": len(completed_keys),
        "scheduled": 75,
        "promoted": report["promoted"],
        "production": report["production"],
        "report": str(campaign_home / "report.json"),
    }, separators=(",", ":")), flush=True)
    return 0 if len(completed_keys) == 75 or args.report_only or args.max_new < 75 else 2


if __name__ == "__main__":
    sys.exit(main())
