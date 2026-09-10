#!/usr/bin/env python3
"""Resumable live validation matrix for the rebuilt PTIT Procon production JAR.

This tool deliberately validates one frozen JAR rather than mutating strategy parameters.  It
creates exactly one practice match per unfinished matrix cell, runs the JAR, and records official
rank/score evidence.  Browser credentials and bot tokens stay in the secrets file: neither is
accepted on the command line or printed in output.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
AUTOTUNE_HOME = Path(os.environ.get("PROCON_AUTOTUNE_HOME", Path.home() / ".procon-autotune"))
BOT_HOME = Path(os.environ.get("PROCON_BOT_HOME", Path.home() / ".procon-bot"))
CREATE = ROOT / "tools" / "procon_autotune" / "create_match.sh"
RUN = ROOT / "tools" / "procon_autotune" / "run_bot.sh"
MATCH_RE = re.compile(r"\bmatchId=(m-\d+)\b")
OWN_TEAM = "team-A"  # Practice API fixes the submitting team to team-A.


@dataclass(frozen=True)
class Job:
    id: str
    profile: str
    round: int


def atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
        json.dump(value, handle, indent=2, sort_keys=True)
        handle.write("\n")
        temporary = Path(handle.name)
    temporary.replace(path)


def load_json(path: Path, fallback: Any) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return fallback


def parse_profiles(value: str) -> list[str]:
    profiles = [profile.strip().upper() for profile in value.split(",") if profile.strip()]
    allowed = {"P08", "P12", "P16", "P24", "P32"}
    unknown = sorted(set(profiles) - allowed)
    if not profiles or unknown:
        raise ValueError("profiles must be a comma-separated subset of P08,P12,P16,P24,P32")
    return profiles


def make_jobs(profiles: list[str], rounds: int) -> list[Job]:
    return [Job(f"{profile}-r{round_index}", profile, round_index)
            for round_index in range(1, rounds + 1) for profile in profiles]


def run(command: list[str], *, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=ROOT, env=env, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, check=False)


def create_match(job: Job, args: argparse.Namespace, session: str) -> tuple[str | None, str]:
    env = os.environ.copy()
    env["PROCON_AUTOTUNE_SESSION"] = session
    command = ["bash", str(CREATE), "--profile", job.profile, "--opponents", str(args.opponents),
               "--days", str(args.days), "--difficulty", "hard", "--response-ms", str(args.response_ms),
               "--fuel-mult", str(args.fuel_mult)]
    for option, value in (("--size", args.size), ("--agents", args.agents), ("--steps", args.steps),
                          ("--brands", args.brands), ("--spots", args.spots)):
        if value is not None:
            command.extend((option, str(value)))
    result = run(command, env=env)
    match = MATCH_RE.search(result.stdout)
    return (match.group(1) if result.returncode == 0 and match else None), result.stdout


def run_bot(match_id: str, args: argparse.Namespace) -> subprocess.CompletedProcess[str]:
    secrets = Path(os.environ.get("SECRETS_FILE", AUTOTUNE_HOME / ".secrets.env"))
    # Source only the local secrets file in a child process; the token is neither parsed into this
    # Python process nor passed on a command line.  create_match.sh separately loads HEXSESSION.
    wrapper = 'set -a; . "$1"; set +a; shift; exec "$@"'
    return run(["bash", "-c", wrapper, "validation-runner", str(secrets), str(RUN),
                "--url", args.url, "--match", match_id, "--transport", args.transport,
                "--response-ms", str(args.response_ms), "--role-mode", args.role_mode])


def score_key(standing: dict[str, Any]) -> tuple[int, int, int, int]:
    return (int(standing.get("globalTypes", 0)), int(standing.get("dailyTypesSum", 0)),
            int(standing.get("portions", 0)), -int(standing.get("responseMillis", 0)))


def result_for(match_id: str, days: int, runtime: str) -> dict[str, Any] | None:
    journal = BOT_HOME / "matches" / match_id
    result = load_json(journal / "result.json", {})
    standings = result.get("standings", []) if isinstance(result, dict) else []
    own = next((entry for entry in standings if entry.get("teamId") == OWN_TEAM), None)
    opponents = [entry for entry in standings if entry.get("teamId") != OWN_TEAM]
    if own is None or not opponents:
        return None
    strongest = max(opponents, key=score_key)
    own_key, opponent_key = score_key(own), score_key(strongest)
    outcome = "WIN" if own_key > opponent_key else "DRAW" if own_key == opponent_key else "LOSS"
    accepted = []
    for day in range(days):
        ack = load_json(journal / f"day-{day}-accepted.json", {})
        accepted.append(bool(ack.get("valid")))
    faults = []
    if not all(accepted):
        faults.append("MISSING_OR_REJECTED_DAY")
    if "BOT_FALLBACK" in runtime:
        faults.append("FALLBACK")
    if "BOT_FATAL" in runtime:
        faults.append("BOT_FATAL")
    return {
        "outcome": outcome,
        "rank": own.get("rank"),
        "own": own,
        "strongestOpponent": strongest,
        "portionMargin": int(own.get("portions", 0)) - int(strongest.get("portions", 0)),
        "acceptedDays": accepted,
        "faults": faults or ["NONE"],
    }


def print_result(job: Job, match_id: str, result: dict[str, Any]) -> None:
    own, opponent = result["own"], result["strongestOpponent"]
    print("VALIDATION_RESULT"
          f" job={job.id} matchId={match_id} outcome={result['outcome']} rank={result['rank']}"
          f" own={own['globalTypes']}/{own['dailyTypesSum']}/{own['portions']}/{own['responseMillis']}"
          f" strongest={opponent['globalTypes']}/{opponent['dailyTypesSum']}/{opponent['portions']}/{opponent['responseMillis']}"
          f" portionsMargin={result['portionMargin']} faults={','.join(result['faults'])}")


def summary(state: dict[str, Any]) -> dict[str, int]:
    completed = [job for job in state["jobs"].values() if job.get("status") == "completed"]
    return {
        "scheduled": len(state["jobs"]),
        "completed": len(completed),
        "wins": sum(job["result"]["outcome"] == "WIN" for job in completed),
        "draws": sum(job["result"]["outcome"] == "DRAW" for job in completed),
        "losses": sum(job["result"]["outcome"] == "LOSS" for job in completed),
        "faulted": sum(job["result"]["faults"] != ["NONE"] for job in completed),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Run a resumable PTIT Hard-Bot validation matrix.")
    parser.add_argument("--campaign", required=True, help="local report directory name")
    parser.add_argument("--profiles", default="P08,P12,P16,P24,P32")
    parser.add_argument("--rounds", type=int, default=1, help="matches per profile")
    parser.add_argument("--max-new", type=int, default=1, help="maximum unfinished jobs to run this invocation")
    parser.add_argument("--opponents", type=int, choices=(1, 2, 3), default=3)
    parser.add_argument("--days", type=int, default=4)
    parser.add_argument("--response-ms", type=int, default=5000)
    parser.add_argument("--fuel-mult", type=int, choices=(0, 1, 2, 3), default=0,
                        help="factory fuel multiplier; 0 delegates to the server default")
    parser.add_argument("--role-mode", choices=("auto", "five-one"), default="auto",
                        help="assignment composition for an isolated live A/B")
    parser.add_argument("--size", type=int, choices=(8, 12, 16, 24, 32),
                        help="optional factory map-size override")
    parser.add_argument("--agents", type=int, choices=range(1, 9),
                        help="optional factory agent-count override")
    parser.add_argument("--steps", type=int, choices=range(1, 201),
                        help="optional factory steps-per-day override")
    parser.add_argument("--brands", type=int, choices=range(1, 33),
                        help="optional factory franchise-count override")
    parser.add_argument("--spots", type=int, choices=range(0, 65),
                        help="optional factory spot-count override; 0 delegates to server")
    parser.add_argument("--transport", choices=("http", "auto", "ws"), default="http")
    parser.add_argument("--url", default="https://procon.ptit.edu.vn")
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args()
    if args.rounds < 1 or args.max_new < 1:
        raise SystemExit("--rounds and --max-new must be positive")
    profiles = parse_profiles(args.profiles)
    jobs = make_jobs(profiles, args.rounds)
    campaign_dir = AUTOTUNE_HOME / "validation" / args.campaign
    state_path = campaign_dir / "state.json"
    state = load_json(state_path, {"campaign": args.campaign, "jobs": {}})
    for job in jobs:
        state["jobs"].setdefault(job.id, {"job": asdict(job), "status": "planned"})
    atomic_json(state_path, state)

    if not args.skip_build:
        build = run(["mvn", "-q", "package", "-DskipTests"])
        if build.returncode != 0:
            print("VALIDATION_STOP reason=BUILD_FAILED")
            print(build.stdout, end="")
            return 2

    executed = 0
    for job in jobs:
        entry = state["jobs"][job.id]
        if entry.get("status") == "completed":
            continue
        if executed >= args.max_new:
            break
        # Factory limits each tag to twelve matches.  Partition long campaigns automatically.
        session = f"validation-{args.campaign}-{jobs.index(job) // 12 + 1}"
        match_id = entry.get("matchId")
        if not match_id:
            match_id, create_output = create_match(job, args, session)
            if match_id is None:
                entry.update({"status": "failed", "createOutput": create_output[-1_000:]})
                atomic_json(state_path, state)
                print(f"VALIDATION_STOP job={job.id} reason=CREATE_FAILED")
                return 2
            entry.update({"status": "created", "matchId": match_id})
            entry.pop("createOutput", None)
            atomic_json(state_path, state)

        # A previous process can have completed the match but died before updating the campaign
        # state.  The journal/result is authoritative in that case; re-running the JAR would try
        # to submit an assignment to an already-started match and produce E_STALE_DAY.
        existing = result_for(match_id, args.days, "")
        if existing is not None:
            entry.update({"status": "completed", "matchId": match_id, "result": existing})
            atomic_json(state_path, state)
            print_result(job, match_id, existing)
            executed += 1
            continue

        bot = run_bot(match_id, args)
        runtime_path = campaign_dir / f"{match_id}.runtime.log"
        runtime_path.write_text(bot.stdout, encoding="utf-8")
        parsed = result_for(match_id, args.days, bot.stdout)
        # Match result beats a late process error: the server's accepted journal proves the four
        # actions used for scoring.  This also makes resume safe around a process interruption.
        if parsed is not None:
            entry.update({"status": "completed", "matchId": match_id, "result": parsed})
            atomic_json(state_path, state)
            print_result(job, match_id, parsed)
            executed += 1
            continue
        if bot.returncode != 0:
            entry.update({"status": "failed", "matchId": match_id, "botExit": bot.returncode})
            atomic_json(state_path, state)
            print(f"VALIDATION_STOP job={job.id} matchId={match_id} reason=BOT_OR_RESULT_FAILED")
            return 2
        entry.update({"status": "failed", "matchId": match_id, "botExit": bot.returncode})
        atomic_json(state_path, state)
        print(f"VALIDATION_STOP job={job.id} matchId={match_id} reason=RESULT_MISSING")
        return 2

    report = {"campaign": args.campaign, "summary": summary(state), "jobs": state["jobs"]}
    atomic_json(campaign_dir / "report.json", report)
    print("VALIDATION_SUMMARY " + json.dumps(report["summary"], sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
