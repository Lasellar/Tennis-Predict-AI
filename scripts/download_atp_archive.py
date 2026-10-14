#!/usr/bin/env python3
"""Download the public ATP archive and create one CSV importable by Tennis Predict AI.

Source data is licensed CC BY-NC-SA 4.0. This script downloads, but does not
bundle or commit, the third-party archive. It is intended for non-commercial
research/evaluation unless the data owner grants additional permission.
"""
from __future__ import annotations

import argparse
import csv
import io
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

BASE_URL = (
    "https://raw.githubusercontent.com/farhadGithub/tennis-atp-data/"
    "main/data/raw/atp_matches_{year}.csv"
)
USER_AGENT = "Tennis-Predict-AI historical evaluation downloader"  # archive workflow


def download_year(year: int, raw_dir: Path) -> Path:
    url = BASE_URL.format(year=year)
    target = raw_dir / f"atp_matches_{year}.csv"
    target.parent.mkdir(parents=True, exist_ok=True)

    last_error: Exception | None = None
    for attempt in range(3):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=60) as response:
                payload = response.read()
            if not payload:
                raise RuntimeError(f"Empty response for {year}: {url}")
            target.write_bytes(payload)
            return target
        except (urllib.error.URLError, TimeoutError, RuntimeError) as exc:
            last_error = exc
            if attempt < 2:
                time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"Could not download {year} from {url}: {last_error}") from last_error


def normalized_surface(value: str) -> str:
    value = (value or "").strip().lower()
    if value == "hard":
        return "hard"
    if value == "clay":
        return "clay"
    if value == "grass":
        return "grass"
    if value == "carpet":
        return "carpet"
    return "other"


def export_compact_archive(raw_paths: list[Path], output_path: Path) -> tuple[int, int]:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    exported = 0
    skipped = 0
    with output_path.open("w", encoding="utf-8", newline="") as output:
        writer = csv.writer(output, lineterminator="\n")
        writer.writerow(["date", "player_a", "player_b", "winner", "surface"])

        for path in raw_paths:
            text = path.read_text(encoding="utf-8-sig")
            reader = csv.DictReader(io.StringIO(text))
            required = {"tourney_date", "winner_name", "loser_name", "surface"}
            if not reader.fieldnames or not required.issubset(set(reader.fieldnames)):
                raise ValueError(
                    f"{path} does not contain required columns: {sorted(required)}"
                )

            for row in reader:
                raw_date = (row.get("tourney_date") or "").strip()
                winner = (row.get("winner_name") or "").strip()
                loser = (row.get("loser_name") or "").strip()
                if not raw_date or not winner or not loser or winner.casefold() == loser.casefold():
                    skipped += 1
                    continue
                try:
                    match_date = datetime.strptime(raw_date, "%Y%m%d").date().isoformat()
                except ValueError:
                    skipped += 1
                    continue

                # Winner-first ordering matches the source schema. The app's model
                # training canonicalizes player orientation to avoid target leakage.
                writer.writerow([
                    match_date,
                    winner,
                    loser,
                    winner,
                    normalized_surface(row.get("surface", "")),
                ])
                exported += 1
    return exported, skipped


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--start", type=int, default=2010, help="First season (default: 2010)")
    parser.add_argument("--end", type=int, default=2024, help="Last season inclusive (default: 2024)")
    parser.add_argument(
        "--raw-dir",
        type=Path,
        default=Path("data/benchmark/raw"),
        help="Directory for original yearly CSV files",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("data/benchmark/atp_2010_2024.csv"),
        help="Combined compact CSV to import into the Android app",
    )
    args = parser.parse_args()
    if args.start < 1968 or args.end > 2024 or args.start > args.end:
        parser.error("Choose years within 1968–2024 and ensure --start <= --end.")

    raw_paths = []
    for year in range(args.start, args.end + 1):
        raw_paths.append(download_year(year, args.raw_dir))
        print(f"Downloaded ATP season {year}")

    exported, skipped = export_compact_archive(raw_paths, args.output)
    print(f"Exported matches: {exported}")
    print(f"Rows skipped for missing/invalid player or date: {skipped}")
    print(f"Importable CSV: {args.output.resolve()}")
    print("Attribution: Jeff Sackmann / Tennis Abstract; mirror: farhadGithub/tennis-atp-data")
    print("License: CC BY-NC-SA 4.0 — non-commercial use only without separate permission.")


if __name__ == "__main__":
    main()
