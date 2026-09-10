#!/usr/bin/env python3
"""Authenticated PTIT practice-match factory.

The creation endpoint was NOT guessed. It was discovered from the team web interface:

  index.html            -> /assets/main-<hash>.js
  main-<hash>.js        -> imports ./presentbits-<hash>.js
  presentbits-<hash>.js -> const Qh = e => $("/practice", e)
                           async function $(e,t){ fetch(e,{method:"POST",
                               headers:{"Content-Type":"application/json"},
                               body:JSON.stringify(t)}) }
                           const Vh = () => O("/team/matches")

so a practice match is created with `POST https://<host>/practice` carrying a JSON body, and the
browser session cookie is the only credential. The option ranges below are read off the very same
bundle (the "Luyện tập với máy" card), so nothing here invents an unsupported value.

Credentials are read from the environment only, are never logged, never echoed and never written to
any file this script produces.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

HOST = os.environ.get("PROCON_BASE_URL", "https://procon.ptit.edu.vn").rstrip("/")
# ITERATION 6: /tmp is demonstrably volatile on this machine — an earlier wipe destroyed the captured
# corpus, every baseline log and this very budget counter. Authoritative state now lives under $HOME.
AUTOTUNE_HOME = os.environ.get("PROCON_AUTOTUNE_HOME", os.path.expanduser("~/.procon-autotune"))
STATE_FILE = os.path.join(AUTOTUNE_HOME, "factory-state.json")
MIN_SECONDS_BETWEEN_CREATIONS = 45
# SUPER SESSION: the ledger is cumulative across sessions, so the budget is counted per session tag
# rather than over the whole ledger. Entries created by this session carry session="super".
SESSION_TAG = os.environ.get("PROCON_AUTOTUNE_SESSION", "super")
MAX_MATCHES_PER_SESSION = 12

# Exactly the option ranges the real UI offers.
DIFFICULTIES = ("easy", "medium", "hard")
MAP_SIZES = (8, 12, 16, 24, 32)
OPPONENT_CHOICES = (1, 2, 3)

# Production calibration presets: size -> (steps_per_day, agent_count, franchises).
# The rebuilt bot is calibrated for six agents on the three competition-priority maps.
UI_PRESETS = {8: (30, 6, 4), 12: (60, 6, 4), 16: (60, 6, 4), 24: (100, 8, 6), 32: (100, 8, 6)}

# The UI's own default per-day answer window. Bigger maps need a wider one: the production V2/R3 planner
# spends more wall-clock on 24x24/8-agent days, and a day the server rejects for lateness produces no
# `accepted.json`, i.e. no admissible corpus evidence at all.
DEFAULT_RESPONSE_MS = 5000
MAX_RESPONSE_MS = 15000

# Full adaptive matrix. Legacy aliases A/B/C remain accepted for existing scripts and ledgers.
PROFILES = {
    "P08": {"label": "p08-6-agent-30-step", "size": 8, "cohort": "P08"},
    "P12": {"label": "p12-6-agent-60-step", "size": 12, "cohort": "P12"},
    "P16": {"label": "p16-6-agent-60-step", "size": 16, "cohort": "P16"},
    "P24": {"label": "p24-8-agent-100-step", "size": 24, "cohort": "P24"},
    "P32": {"label": "p32-8-agent-100-step", "size": 32, "cohort": "P32"},
}
PROFILE_ALIASES = {"A": "P08", "B": "P16", "C": "P24"}
PROFILE_CYCLE = tuple(PROFILES)


def canonical_profile(profile_key: str) -> str:
    return PROFILE_ALIASES.get(profile_key, profile_key)


def session_entries(state: dict) -> list[dict]:
    return [entry for entry in state.get("created", []) if entry.get("session") == SESSION_TAG]


def fail(code: str, detail: str = "") -> None:
    print(code + ((" " + detail) if detail else ""))
    sys.exit(2)


def session_cookie() -> str:
    value = os.environ.get("HEXSESSION", "")
    if not value:
        fail("AUTOTUNE_CREDENTIALS_MISSING", "HEXSESSION is not set")
    return value


def request(method: str, path: str, body: dict | None = None, timeout: int = 30):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(HOST + path, data=data, method=method)
    req.add_header("Cookie", "hexsession=" + session_cookie())
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return response.status, json.loads(response.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as error:
        payload = error.read().decode("utf-8", errors="replace")[:400]
        return error.code, {"error": payload}
    except (urllib.error.URLError, TimeoutError) as error:
        return 0, {"error": "transport: %s" % type(error).__name__}


def load_state() -> dict:
    try:
        with open(STATE_FILE, "r", encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return {"created": [], "cycleIndex": 0}


def save_state(state: dict) -> None:
    os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)
    with open(STATE_FILE, "w", encoding="utf-8") as handle:
        json.dump(state, handle, indent=2, sort_keys=True)


def match_ids() -> list[str]:
    """Non-sensitive view of the team's match list: identifiers and status only, never tokens."""
    status, payload = request("GET", "/team/matches")
    if status != 200:
        return []
    return [str(entry.get("id")) for entry in payload.get("matches", []) if entry.get("id")]


def body_for(profile_key: str, days: int, opponents: int, difficulty: str,
             response_ms: int = DEFAULT_RESPONSE_MS, size: int | None = None,
             steps: int | None = None, agents: int | None = None,
             brands: int | None = None, spots: int | None = None,
             fuel_mult: int | None = None) -> dict:
    profile_key = canonical_profile(profile_key)
    profile = PROFILES[profile_key]
    size = profile["size"] if size is None else size
    preset_steps, preset_agents, preset_franchises = UI_PRESETS[size]
    steps = preset_steps if steps is None else steps
    agents = preset_agents if agents is None else agents
    franchises = preset_franchises if brands is None else brands
    return {
        "difficulty": difficulty,
        "opponents": opponents,
        "days": days,
        "steps_per_day": steps,
        "response_time_ms": response_ms,
        "width": size,
        "height": size,
        "agent_count": agents,
        "franchises": franchises,
        "spots": 0 if spots is None else spots,
        "fuel_mult": 0 if fuel_mult is None else fuel_mult,
    }


def describe(profile_key: str, body: dict, match_id: str) -> str:
    profile_key = canonical_profile(profile_key)
    return ("PRACTICE_MATCH_CREATED matchId=%s profile=%s cohort=%s label=%s difficulty=%s bots=%d days=%d"
            " mapSize=%dx%d stepsPerDay=%d agents=%d franchises=%d responseMs=%d") % (
        match_id, profile_key, PROFILES[profile_key]["cohort"], PROFILES[profile_key]["label"],
        body["difficulty"], body["opponents"],
        body["days"], body["width"], body["height"], body["steps_per_day"], body["agent_count"],
        body["franchises"], body["response_time_ms"])


def create(profile_key: str, days: int, opponents: int, difficulty: str, dry_run: bool,
           response_ms: int = DEFAULT_RESPONSE_MS, size: int | None = None,
           steps: int | None = None, agents: int | None = None,
           brands: int | None = None, spots: int | None = None,
           fuel_mult: int | None = None) -> int:
    profile_key = canonical_profile(profile_key)
    state = load_state()
    body = body_for(profile_key, days, opponents, difficulty, response_ms,
                    size=size, steps=steps, agents=agents, brands=brands,
                    spots=spots, fuel_mult=fuel_mult)
    if dry_run:
        print("PRACTICE_MATCH_DRY_RUN profile=%s endpoint=POST %s/practice body=%s"
              % (profile_key, HOST, json.dumps(body, sort_keys=True)))
        return 0

    mine = session_entries(state)
    if len(mine) >= MAX_MATCHES_PER_SESSION:
        fail("AUTOTUNE_MATCH_BUDGET_EXHAUSTED",
             "%d practice matches already created in session=%s" % (len(mine), SESSION_TAG))

    last = state.get("lastCreatedEpoch", 0)
    waited = time.time() - last
    if last and waited < MIN_SECONDS_BETWEEN_CREATIONS:
        time.sleep(MIN_SECONDS_BETWEEN_CREATIONS - waited)

    before = set(match_ids())
    status, payload = request("POST", "/practice", body)
    if status in (200, 201) and payload.get("match_id"):
        match_id = str(payload["match_id"])
    else:
        # PART 26: an uncertain POST is never blindly retried. Re-read the authoritative match list
        # and adopt whatever fresh match the server actually created.
        time.sleep(4)
        fresh = [mid for mid in match_ids() if mid not in before]
        if len(fresh) == 1:
            match_id = fresh[0]
            print("PRACTICE_MATCH_RECOVERED httpStatus=%d recoveredFrom=/team/matches" % status)
        else:
            fail("PRACTICE_MATCH_CREATE_FAILED",
                 "httpStatus=%d freshMatches=%d detail=%s"
                 % (status, len(fresh), json.dumps(payload.get("error", ""))[:200]))
            return 2

    state["created"].append({"matchId": match_id, "profile": profile_key,
                             "cohort": PROFILES[profile_key]["cohort"],
                             "session": SESSION_TAG,
                             "body": body, "epoch": int(time.time())})
    state["lastCreatedEpoch"] = int(time.time())
    state["cycleIndex"] = (PROFILE_CYCLE.index(profile_key) + 1) % len(PROFILE_CYCLE)
    save_state(state)
    print(describe(profile_key, body, match_id))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Create one PTIT practice match.")
    parser.add_argument("--profile", choices=sorted(PROFILES) + sorted(PROFILE_ALIASES) + ["next"],
                        default="next")
    parser.add_argument("--days", type=int, default=4)
    parser.add_argument("--opponents", type=int, default=1, choices=OPPONENT_CHOICES)
    parser.add_argument("--difficulty", default="hard", choices=DIFFICULTIES)
    parser.add_argument("--response-ms", type=int, default=DEFAULT_RESPONSE_MS,
                        help="per-day answer window the server grants (200..%d)"
                             % MAX_RESPONSE_MS)
    parser.add_argument("--size", type=int, choices=MAP_SIZES,
                        help="override map width/height while retaining the selected profile label")
    parser.add_argument("--steps", type=int, help="override steps per day")
    parser.add_argument("--agents", type=int, help="override number of agents")
    parser.add_argument("--brands", type=int, help="override number of udon brands")
    parser.add_argument("--spots", type=int, help="override spot count; 0 delegates to the server")
    parser.add_argument("--fuel-mult", type=int, choices=(0, 1, 2, 3),
                        help="override fuel multiplier")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--list", action="store_true", help="print the session's created matches")
    parser.add_argument("--verify-session", action="store_true")
    args = parser.parse_args()

    if not 4 <= args.days <= 10:
        fail("PRACTICE_MATCH_BAD_OPTION", "days must be 4..10 (website bound)")
    if not 200 <= args.response_ms <= MAX_RESPONSE_MS:
        fail("PRACTICE_MATCH_BAD_OPTION",
             "response-ms must be 200..%d" % MAX_RESPONSE_MS)
    if args.steps is not None and not 1 <= args.steps <= 200:
        fail("PRACTICE_MATCH_BAD_OPTION", "steps must be 1..200")
    if args.agents is not None and not 1 <= args.agents <= 8:
        fail("PRACTICE_MATCH_BAD_OPTION", "agents must be 1..8")
    if args.brands is not None and not 1 <= args.brands <= 32:
        fail("PRACTICE_MATCH_BAD_OPTION", "brands must be 1..32")
    if args.spots is not None and not 0 <= args.spots <= 64:
        fail("PRACTICE_MATCH_BAD_OPTION", "spots must be 0..64")

    if args.list:
        state = load_state()
        mine = session_entries(state)
        print("AUTOTUNE_MATCHES_CREATED session=%s sessionCount=%d budget=%d ledgerTotal=%d"
              % (SESSION_TAG, len(mine), MAX_MATCHES_PER_SESSION, len(state["created"])))
        for entry in state["created"]:
            body = entry.get("body") or {}
            if body:
                print("  matchId=%s profile=%s cohort=%s session=%s mapSize=%dx%d steps=%d agents=%d days=%d"
                      % (entry["matchId"], entry["profile"], entry.get("cohort", "?"),
                         entry.get("session", "prior"), body["width"], body["height"],
                         body["steps_per_day"], body["agent_count"], body.get("days", 0)))
            else:
                print("  matchId=%s profile=%s config=%s"
                      % (entry["matchId"], entry["profile"],
                         entry.get("note", "unrecorded")))
        return 0

    if args.verify_session:
        status, payload = request("GET", "/team/matches")
        matches = payload.get("matches", []) if status == 200 else []
        running = [str(m.get("id")) for m in matches if m.get("status") not in ("done", "cancelled")]
        print("SESSION_OK httpStatus=%d matches=%d notDone=%s" % (status, len(matches), running))
        return 0 if status == 200 else 2

    profile_key = args.profile
    if profile_key == "next":
        profile_key = PROFILE_CYCLE[load_state().get("cycleIndex", 0) % len(PROFILE_CYCLE)]
    return create(profile_key, args.days, args.opponents, args.difficulty, args.dry_run,
                  args.response_ms, size=args.size, steps=args.steps,
                  agents=args.agents, brands=args.brands, spots=args.spots,
                  fuel_mult=args.fuel_mult)


if __name__ == "__main__":
    sys.exit(main())
