#!/usr/bin/env python3
"""Backward-compatible ATP-only entry point for the current archive builder.

The generated CSV is directly importable into the Android app. For both tours,
run scripts/download_tour_archives.py instead.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from pathlib import Path

from download_tour_archives import build_tour


def main() -> None:
    current_year = datetime.now(timezone.utc).year
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--start", type=int, default=2010, help="First season (default: 2010)")
    parser.add_argument("--end", type=int, default=current_year, help=f"Last season (default: {current_year})")
    parser.add_argument("--raw-dir", type=Path, default=Path("data/benchmark/raw"))
    parser.add_argument("--output", type=Path, default=None, help="Output CSV path (default: atp_START_END.csv)")
    args = parser.parse_args()
    if args.start > args.end:
        parser.error("--start must be less than or equal to --end")

    output_dir = args.output.parent if args.output else Path("data/benchmark")
    generated, count, latest_date, _supplemented = build_tour(
        tour="atp",
        start=args.start,
        end=args.end,
        raw_dir=args.raw_dir,
        output_dir=output_dir,
        cutoff=datetime.now(timezone.utc).date().isoformat(),
    )
    desired = args.output or (output_dir / f"atp_{args.start}_{args.end}.csv")
    if generated.resolve() != desired.resolve():
        desired.parent.mkdir(parents=True, exist_ok=True)
        generated.replace(desired)
    print(f"Importable ATP CSV: {desired.resolve()}")
    print(f"Matches: {count:,}; latest result date: {latest_date or 'unknown'}")
    print("License: combined archive is non-commercial CC BY-NC-SA 4.0.")
    print("Sources: Jeff Sackmann / Tennis Abstract via Aneeshers; current-season supplement: Valuebetennis.")


if __name__ == "__main__":
    main()
