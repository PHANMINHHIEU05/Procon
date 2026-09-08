#!/usr/bin/env python3
"""Append one autonomous-loop iteration record to /tmp/procon-autotune/history.jsonl.

PART 30. The history holds decisions and evidence only: match identifiers, corpus sizes, gate verdicts,
scorecard figures and the accept/reject outcome. It never holds a credential, and this script has no way to
read one — it takes its fields from the command line and from files under /tmp/procon-autotune.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time

HISTORY = "/tmp/procon-autotune/history.jsonl"

# Defence in depth: even though nothing here reads a secret, a value that LOOKS like one is refused.
FORBIDDEN = re.compile(r"(?i)(bot-[0-9a-f]{16,}|hexsession|authorization|bearer\s|set-cookie|"
                       r"[A-Za-z0-9+/]{40,}\.[0-9a-f]{40,})")


def clean(value: str) -> str:
    if FORBIDDEN.search(value or ""):
        print("AUTOTUNE_HISTORY_REFUSED credential-shaped value rejected")
        sys.exit(2)
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description="Append one iteration record to the history log.")
    parser.add_argument("--iteration", required=True)
    parser.add_argument("--phase", required=True, help="e.g. INFRA, CORPUS, HYPOTHESIS, GATE")
    parser.add_argument("--match-id", default="")
    parser.add_argument("--hypothesis", default="")
    parser.add_argument("--change", default="")
    parser.add_argument("--outcome", required=True,
                        choices=["INFRA_READY", "CORPUS_GROWN", "ACCEPT", "REJECT", "BLOCKED", "STOP"])
    parser.add_argument("--corpus-days", type=int, default=0)
    parser.add_argument("--large-days", type=int, default=0)
    parser.add_argument("--note", default="")
    parser.add_argument("--gates", default="", help="path to a file of GATE lines to embed verbatim")
    args = parser.parse_args()

    gates = []
    if args.gates and os.path.isfile(args.gates):
        with open(args.gates, "r", encoding="utf-8") as handle:
            gates = [clean(line.strip()) for line in handle if line.strip().startswith("GATE ")]

    record = {
        "epoch": int(time.time()),
        "utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "iteration": clean(args.iteration),
        "phase": clean(args.phase),
        "matchId": clean(args.match_id),
        "hypothesis": clean(args.hypothesis),
        "change": clean(args.change),
        "outcome": args.outcome,
        "corpusDays": args.corpus_days,
        "largeDays": args.large_days,
        "note": clean(args.note),
        "gates": gates,
    }
    os.makedirs(os.path.dirname(HISTORY), exist_ok=True)
    with open(HISTORY, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(record, sort_keys=True) + "\n")
    print("AUTOTUNE_HISTORY_APPENDED iteration=%s phase=%s outcome=%s corpusDays=%d largeDays=%d"
          % (record["iteration"], record["phase"], record["outcome"], record["corpusDays"],
             record["largeDays"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
