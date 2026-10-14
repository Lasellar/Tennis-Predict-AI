#!/usr/bin/env python3
"""Build app-importable ATP and WTA result archives.

Primary results source: Jeff Sackmann's tennis_atp / tennis_wta datasets as
preserved by Aneeshers/tennis-sackmann-archive (CC BY-NC-SA 4.0).
For the unfinished current season, matches after the latest available date in
the primary snapshot are supplemented from Valuebetennis open data (CC BY 4.0).
The combined exports inherit the primary source's non-commercial ShareAlike
restriction. Intended for non-commercial research and evaluation.
"""
from __future__ import annotations

import argparse
import csv
import io
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import date, datetime, timezone
from pathlib import Path

PRIMARY_URL = (
    "https://raw.githubusercontent.com/Aneeshers/tennis-sackmann-archive/"
    "main/{tour}/{tour}_matches_{year}.csv"
)
CURRENT_SEASON_URL = (
    "https://www.valuebetennis.com/datasets/valuebetennis-matchs-{year}.csv"
)
USER_AGENT = "Tennis-Predict-AI archive builder (non-commercial research)"
TOP_LEVEL_CATEGORIES = {"main tour", "masters", "grand slam"}


@dataclass(frozen=True)
class Match:
    date: str
    winner: str
    loser: str
    surface: str

    @property
    def key(self) -> tuple[str, str, str]:
        return (self.date, self.winner.casefold(), self.loser.casefold())


def request_bytes(url: str) -> bytes:
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
            return payload
        except (urllib.error.URLError, TimeoutError, RuntimeError) as exc:
            last_error = exc
            if attempt < 2:
                time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"Could not download {url}: {last_error}") from last_error


def parse_date(raw: object) -> str | None:
    value = str(raw or "").strip()
    if not value or value.lower() in {"nan", "none", "nat"}:
        return None
    if len(value) == 8 and value.isdigit():
        try:
            return datetime.strptime(value, "%Y%m%d").date().isoformat()
        except ValueError:
            return None
    try:
        # ISO date or timestamp; Valuebetennis timestamps are published in UTC.
        return date.fromisoformat(value[:10]).isoformat()
    except ValueError:
        return None


def normalized_surface(raw: object) -> str:
    value = str(raw or "").strip().casefold()
    if any(token in value for token in ("clay", "terre battue", "red clay")):
        return "clay"
    if any(token in value for token in ("grass", "gazon", "lawn")):
        return "grass"
    if "carpet" in value:
        return "carpet"
    if any(token in value for token in ("hard", "dur", "бетон")):
        return "hard"
    if "indoor" in value:
        return "carpet"
    return "other"


def cleaned_name(value: object) -> str:
    return " ".join(str(value or "").strip().split())


def normalized_id(value: object) -> str:
    value = str(value or "").strip()
    if value.endswith(".0"):
        value = value[:-2]
    return value


def primary_matches(
    tour: str,
    year: int,
    raw_dir: Path,
    cutoff: str,
) -> tuple[list[Match], str | None]:
    url = PRIMARY_URL.format(tour=tour, year=year)
    path = raw_dir / tour / f"{tour}_matches_{year}.csv"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(request_bytes(url))

    records: list[Match] = []
    latest_current_date: str | None = None
    with path.open("r", encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        required = {"tourney_date", "winner_name", "loser_name", "surface"}
        if not reader.fieldnames or not required.issubset(set(reader.fieldnames)):
            raise ValueError(f"{path} is missing columns {sorted(required)}")
        for row in reader:
            match_date = parse_date(row.get("tourney_date"))
            winner = cleaned_name(row.get("winner_name"))
            loser = cleaned_name(row.get("loser_name"))
            if not match_date or match_date > cutoff:
                continue
            if not winner or not loser or winner.casefold() == loser.casefold():
                continue
            records.append(
                Match(
                    date=match_date,
                    winner=winner,
                    loser=loser,
                    surface=normalized_surface(row.get("surface")),
                )
            )
            if year == int(cutoff[:4]) and match_date.startswith(cutoff[:4]):
                if latest_current_date is None or match_date > latest_current_date:
                    latest_current_date = match_date
    return records, latest_current_date


def current_season_supplement(
    tour: str,
    year: int,
    latest_primary_date: str | None,
    cutoff: str,
    raw_dir: Path,
) -> list[Match]:
    """Add top-level matches missing from the older current-season snapshot."""
    url = CURRENT_SEASON_URL.format(year=year)
    path = raw_dir / f"valuebetennis-matchs-{year}.csv"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(request_bytes(url))

    earliest_date = latest_primary_date or f"{year}-01-01"
    records: list[Match] = []
    with path.open("r", encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        required = {
            "date", "categorie", "genre", "surface",
            "joueur1", "joueur1_id", "joueur2", "joueur2_id", "vainqueur_id",
        }
        if not reader.fieldnames or not required.issubset(set(reader.fieldnames)):
            raise ValueError(
                f"{path} is missing supplement columns {sorted(required)}; "
                f"found {reader.fieldnames}"
            )
        for row in reader:
            row_tour = str(row.get("genre") or "").strip().casefold()
            if row_tour != tour:
                continue
            category = str(row.get("categorie") or "").strip().casefold()
            if category not in TOP_LEVEL_CATEGORIES:
                continue
            match_date = parse_date(row.get("date"))
            if not match_date or match_date > cutoff or match_date < earliest_date:
                continue

            player1 = cleaned_name(row.get("joueur1"))
            player2 = cleaned_name(row.get("joueur2"))
            id1 = normalized_id(row.get("joueur1_id"))
            id2 = normalized_id(row.get("joueur2_id"))
            winner_id = normalized_id(row.get("vainqueur_id"))
            if not player1 or not player2 or player1.casefold() == player2.casefold():
                continue
            if winner_id and winner_id == id1:
                winner, loser = player1, player2
            elif winner_id and winner_id == id2:
                winner, loser = player2, player1
            else:
                # The supplement contains only settled matches, but skip any row
                # whose winner cannot be mapped to one of the two player IDs.
                continue
            records.append(
                Match(
                    date=match_date,
                    winner=winner,
                    loser=loser,
                    surface=normalized_surface(row.get("surface")),
                )
            )
    return records


def deduplicate(records: list[Match]) -> list[Match]:
    unique: dict[tuple[str, str, str], Match] = {}
    for match in records:
        unique.setdefault(match.key, match)
    return sorted(unique.values(), key=lambda item: (item.date, item.winner.casefold(), item.loser.casefold()))


def write_archive(records: list[Match], output_path: Path) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow(["date", "player_a", "player_b", "winner", "surface"])
        for match in records:
            # Winner-first orientation is accepted by the Android importer and
            # leaves no ambiguity about the result target.
            writer.writerow([match.date, match.winner, match.loser, match.winner, match.surface])


def build_tour(
    tour: str,
    start: int,
    end: int,
    raw_dir: Path,
    output_dir: Path,
    cutoff: str,
) -> tuple[Path, int, str | None, int]:
    all_records: list[Match] = []
    latest_primary_date: str | None = None
    for year in range(start, end + 1):
        season_records, season_latest = primary_matches(tour, year, raw_dir, cutoff)
        all_records.extend(season_records)
        if season_latest and (
            latest_primary_date is None or season_latest > latest_primary_date
        ):
            latest_primary_date = season_latest
        print(f"{tour.upper()} {year}: downloaded {len(season_records):,} matches")

    supplement_count = 0
    if start <= int(cutoff[:4]) <= end:
        supplemental = current_season_supplement(
            tour, int(cutoff[:4]), latest_primary_date, cutoff, raw_dir
        )
        all_records.extend(supplemental)
        supplement_count = len(supplemental)

    records = deduplicate(all_records)
    output_path = output_dir / f"{tour}_{start}_{end}.csv"
    write_archive(records, output_path)
    latest_date = records[-1].date if records else None
    print(
        f"{tour.upper()}: {len(records):,} unique matches; "
        f"{supplement_count:,} current-season supplement rows before deduplication; "
        f"latest match date {latest_date or 'unknown'}; output {output_path}"
    )
    return output_path, len(records), latest_date, supplement_count


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
        help="Directory for downloaded raw yearly CSV files",
    )
    parser.add_argument(
        "--output-dir", type=Path, default=Path("data/benchmark"),
        help="Directory for importable output CSV files",
    )
    args = parser.parse_args()
    if args.start < 1968 or args.end > today.year or args.start > args.end:
        parser.error(f"Choose years within 1968–{today.year} and ensure --start <= --end.")

    cutoff = today.isoformat()
    outputs = []
    for tour in ("atp", "wta"):
        outputs.append(
            build_tour(
                tour=tour,
                start=args.start,
                end=args.end,
                raw_dir=args.raw_dir,
                output_dir=args.output_dir,
                cutoff=cutoff,
            )
        )

    print()
    print(f"Archive cutoff (UTC): {cutoff} inclusive")
    print("Primary attribution: Jeff Sackmann / Tennis Abstract, via")
    print("Aneeshers/tennis-sackmann-archive (CC BY-NC-SA 4.0).")
    print("Current-season supplement: Valuebetennis open dataset (CC BY 4.0).")
    print("Combined files remain non-commercial under CC BY-NC-SA 4.0.")
    for path, count, latest, _supplement_count in outputs:
        print(f"{path}: {count:,} matches; latest result date {latest or 'unknown'}")


if __name__ == "__main__":
    main()
