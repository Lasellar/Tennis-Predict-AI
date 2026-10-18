#!/usr/bin/env python3
"""Build importable ATP Challenger and ITF World Tennis Tour result archives.

The lower-tier files are read from the Jeff Sackmann / Tennis Abstract datasets
preserved by Aneeshers/tennis-sackmann-archive. This source is a research archive,
not a guaranteed live feed; recent results may be delayed or incomplete.
"""
from __future__ import annotations

import argparse
import csv
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import date, datetime, timezone
from pathlib import Path

BASE_URL = (
    "https://raw.githubusercontent.com/Aneeshers/tennis-sackmann-archive/"
    "main/{folder}/{filename}"
)
USER_AGENT = "Tennis-Predict-AI archive builder (non-commercial research)"
CSV_FIELDS = ["date", "player_a", "player_b", "winner", "surface", "circuit", "competition"]


@dataclass(frozen=True)
class SourceSpec:
    folder: str
    filename: str
    circuit: str
    competition: str


SOURCES = {
    # This file combines ATP-tour qualifying and Challenger main-draw matches.
    # Keep only tourney_level=C so ATP qualifying is not mislabeled as Challenger.
    "atp_challenger": SourceSpec(
        "atp", "atp_matches_qual_chall_{year}.csv", "men", "challenger"
    ),
    # The source file itself contains Futures / ITF men's matches.
    "itf_men": SourceSpec(
        "atp", "atp_matches_futures_{year}.csv", "men", "itf"
    ),
    # This file mixes WTA qualifying and ITF events. Numeric level codes represent
    # ITF prize-money tiers; alphabetic WTA-tour codes are deliberately excluded.
    "itf_women": SourceSpec(
        "wta", "wta_matches_qual_itf_{year}.csv", "women", "itf"
    ),
}


@dataclass(frozen=True)
class Match:
    date: str
    winner: str
    loser: str
    surface: str
    circuit: str
    competition: str

    @property
    def key(self) -> tuple[str, str, str]:
        return (self.date, self.winner.casefold(), self.loser.casefold())


def parse_date(raw: object) -> str | None:
    value = str(raw or "").strip()
    if not value or value.lower() in {"nan", "none", "nat"}:
        return None
    if re.fullmatch(r"\d{8}", value):
        try:
            return datetime.strptime(value, "%Y%m%d").date().isoformat()
        except ValueError:
            return None
    try:
        return date.fromisoformat(value[:10]).isoformat()
    except ValueError:
        return None


def normalized_surface(raw: object) -> str:
    value = str(raw or "").strip().casefold()
    if any(token in value for token in ("clay", "terre battue", "red clay")):
        return "clay"
    if any(token in value for token in ("grass", "gazon", "lawn")):
        return "grass"
    if "carpet" in value or "indoor" in value:
        return "carpet"
    if any(token in value for token in ("hard", "dur", "бетон")):
        return "hard"
    return "other"


def cleaned_name(value: object) -> str:
    return " ".join(str(value or "").strip().split())


def selected_tier(source: str, row: dict[str, str]) -> bool:
    level = str(row.get("tourney_level") or "").strip().upper()
    if source == "atp_challenger":
        return level == "C"
    if source == "itf_women":
        # The Sackmann WTA file uses numeric codes for ITF prize-money tiers.
        # Do not absorb WTA main-tour qualifying (G, PM, P, I, T1, etc.).
        return bool(re.fullmatch(r"\d{1,3}", level)) and 0 < int(level) <= 200
    return source == "itf_men"


def download_optional(url: str, destination: Path) -> bool:
    """Download a source file; return False for years absent from the source."""
    last_error: Exception | None = None
    for attempt in range(3):
        try:
            request = urllib.request.Request(
                url,
                headers={
                    "User-Agent": USER_AGENT,
                    "Accept": "text/csv,text/plain,*/*",
                },
            )
            with urllib.request.urlopen(request, timeout=90) as response:
                payload = response.read()
            if not payload:
                raise RuntimeError(f"Empty response from {url}")
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(payload)
            return True
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                return False
            last_error = exc
        except (urllib.error.URLError, TimeoutError, RuntimeError) as exc:
            last_error = exc
        if attempt < 2:
            time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"Could not download {url}: {last_error}") from last_error


def read_source_file(
    path: Path,
    source: str,
    cutoff: str,
) -> list[Match]:
    spec = SOURCES[source]
    matches: list[Match] = []
    with path.open("r", encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        required = {"tourney_date", "winner_name", "loser_name", "surface"}
        if source in {"atp_challenger", "itf_women"}:
            required.add("tourney_level")
        if not reader.fieldnames or not required.issubset(set(reader.fieldnames)):
            raise ValueError(
                f"{path} is missing columns {sorted(required)}; "
                f"found {reader.fieldnames}"
            )

        for row in reader:
            if not selected_tier(source, row):
                continue
            match_date = parse_date(row.get("tourney_date"))
            winner = cleaned_name(row.get("winner_name"))
            loser = cleaned_name(row.get("loser_name"))
            if (
                match_date is None
                or match_date > cutoff
                or not winner
                or not loser
                or winner.casefold() == loser.casefold()
            ):
                continue
            matches.append(
                Match(
                    date=match_date,
                    winner=winner,
                    loser=loser,
                    surface=normalized_surface(row.get("surface")),
                    circuit=spec.circuit,
                    competition=spec.competition,
                )
            )
    return matches


def deduplicate(matches: list[Match]) -> list[Match]:
    unique: dict[tuple[str, str, str], Match] = {}
    for match in matches:
        unique.setdefault(match.key, match)
    return sorted(
        unique.values(),
        key=lambda item: (item.date, item.winner.casefold(), item.loser.casefold()),
    )


def write_archive(matches: list[Match], destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow(CSV_FIELDS)
        for match in matches:
            # Winner-first orientation follows the existing compact app-import format.
            writer.writerow([
                match.date, match.winner, match.loser, match.winner,
                match.surface, match.circuit, match.competition,
            ])


def build_archive(
    source: str,
    start: int,
    end: int,
    raw_dir: Path,
    output_dir: Path,
    cutoff: str,
) -> tuple[Path, int, str | None]:
    spec = SOURCES[source]
    matches: list[Match] = []
    available_files = 0
    for year in range(start, end + 1):
        filename = spec.filename.format(year=year)
        source_path = raw_dir / spec.folder / filename
        url = BASE_URL.format(folder=spec.folder, filename=filename)
        if not download_optional(url, source_path):
            print(f"{source.upper()} {year}: no source file; skipped")
            continue
        available_files += 1
        year_matches = read_source_file(source_path, source, cutoff)
        matches.extend(year_matches)
        print(f"{source.upper()} {year}: {len(year_matches):,} eligible matches")

    if available_files == 0:
        raise RuntimeError(
            f"No source files found for {source} in seasons {start}–{end}. "
            "Try a later --start year or verify source availability."
        )

    unique = deduplicate(matches)
    destination = output_dir / f"{source}_{start}_{end}.csv"
    write_archive(unique, destination)
    latest = unique[-1].date if unique else None
    print(
        f"{source.upper()}: {len(unique):,} unique matches; "
        f"latest tournament date {latest or 'unknown'}; output {destination}"
    )
    return destination, len(unique), latest


def main() -> None:
    today = datetime.now(timezone.utc).date()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--start", type=int, default=2010, help="First season (default: 2010)")
    parser.add_argument(
        "--end", type=int, default=today.year,
        help=f"Last season inclusive (default: current year, {today.year})",
    )
    parser.add_argument(
        "--raw-dir", type=Path, default=Path("data/benchmark/raw"),
        help="Directory for cached raw source CSVs",
    )
    parser.add_argument(
        "--output-dir", type=Path, default=Path("data/benchmark"),
        help="Directory for app-importable CSV files",
    )
    parser.add_argument(
        "--cutoff", type=str, default=today.isoformat(),
        help="Inclusive UTC date cutoff (YYYY-MM-DD); defaults to today",
    )
    args = parser.parse_args()
    try:
        cutoff_date = date.fromisoformat(args.cutoff)
    except ValueError:
        parser.error("--cutoff must use YYYY-MM-DD")
    if args.start < 1968 or args.end > today.year or args.start > args.end:
        parser.error(f"Choose years within 1968–{today.year} and ensure --start <= --end.")
    if cutoff_date > today:
        parser.error("--cutoff cannot be in the future")

    print(f"Archive cutoff (UTC): {cutoff_date.isoformat()} inclusive")
    outputs = []
    for source in ("atp_challenger", "itf_men", "itf_women"):
        outputs.append(
            build_archive(
                source=source,
                start=args.start,
                end=args.end,
                raw_dir=args.raw_dir,
                output_dir=args.output_dir,
                cutoff=cutoff_date.isoformat(),
            )
        )
    print("\nPrimary data: Jeff Sackmann / Tennis Abstract, preserved by")
    print("Aneeshers/tennis-sackmann-archive; CC BY-NC-SA 4.0.")
    print("These exports inherit the non-commercial ShareAlike restriction.")
    for path, count, latest in outputs:
        print(f"{path}: {count:,} matches; latest tournament date {latest or 'unknown'}")


if __name__ == "__main__":
    main()
