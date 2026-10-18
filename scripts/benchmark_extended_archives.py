#!/usr/bin/env python3
"""Chronological Elo benchmark: main-tour history vs. main-tour + lower-tier history.

The target set is always ATP/WTA main-tour matches. Challenger/ITF results are used
only as additional prior history. All matches sharing a tournament-start date are
scored from the same pre-date ratings and applied as a batch to avoid same-date
outcome leakage. No third-party archive is copied into the report.
"""
from __future__ import annotations

import argparse
import csv
import math
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, timezone
from pathlib import Path

K_FACTOR = 32.0
ELO_SCALE = 400.0
NO_WORSE_TOLERANCE = 0.0001
MINIMUM_GAIN = 0.0001


@dataclass(frozen=True)
class Match:
    day: date
    player_a: str
    player_b: str
    winner: str


@dataclass
class Metrics:
    count: int = 0
    log_loss: float = 0.0
    brier: float = 0.0
    correct: int = 0

    def add(self, probability_a: float, actual_a: int) -> None:
        p = min(0.999, max(0.001, probability_a))
        self.count += 1
        self.log_loss -= actual_a * math.log(p) + (1 - actual_a) * math.log(1 - p)
        self.brier += (p - actual_a) ** 2
        self.correct += int((p >= 0.5) == (actual_a == 1))

    def values(self) -> tuple[float, float, float]:
        if not self.count:
            return (float("nan"), float("nan"), float("nan"))
        return (
            self.log_loss / self.count,
            self.brier / self.count,
            self.correct / self.count,
        )


def normalize_name(raw: str) -> str:
    return " ".join((raw or "").strip().split()).casefold()


def parse_match_date(raw: str) -> date | None:
    value = (raw or "").strip()
    if not value:
        return None
    try:
        if len(value) == 8 and value.isdigit():
            return datetime.strptime(value, "%Y%m%d").date()
        return date.fromisoformat(value[:10])
    except ValueError:
        return None


def load_matches(path: Path) -> list[Match]:
    result: list[Match] = []
    seen: set[tuple[str, str, str, str]] = set()
    with path.open("r", encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        required = {"date", "player_a", "player_b", "winner"}
        if not reader.fieldnames or not required.issubset(set(reader.fieldnames)):
            raise ValueError(
                f"{path} must contain date, player_a, player_b and winner columns"
            )
        for row in reader:
            day = parse_match_date(row.get("date", ""))
            raw_a = normalize_name(row.get("player_a", ""))
            raw_b = normalize_name(row.get("player_b", ""))
            winner = normalize_name(row.get("winner", ""))
            if day is None or not raw_a or not raw_b or raw_a == raw_b:
                continue
            if winner not in {raw_a, raw_b}:
                continue
            player_a, player_b = sorted((raw_a, raw_b))
            key = (day.isoformat(), player_a, player_b, winner)
            if key in seen:
                continue
            seen.add(key)
            result.append(Match(day, player_a, player_b, winner))
    return sorted(result, key=lambda item: (item.day, item.player_a, item.player_b))


def probability(rating_a: float, rating_b: float) -> float:
    exponent = max(-35.0, min(35.0, (rating_b - rating_a) / ELO_SCALE * math.log(10.0)))
    return 1.0 / (1.0 + math.exp(exponent))


def apply_date_batch(ratings: dict[str, float], matches: list[Match]) -> None:
    deltas: dict[str, float] = defaultdict(float)
    for match in matches:
        rating_a = ratings.get(match.player_a, 1500.0)
        rating_b = ratings.get(match.player_b, 1500.0)
        actual_a = 1.0 if match.winner == match.player_a else 0.0
        change = K_FACTOR * (actual_a - probability(rating_a, rating_b))
        deltas[match.player_a] += change
        deltas[match.player_b] -= change
    for player, delta in deltas.items():
        ratings[player] = ratings.get(player, 1500.0) + delta


def validation_years(main_matches: list[Match], current_year: int) -> list[int]:
    counts: dict[int, int] = defaultdict(int)
    for match in main_matches:
        if match.day.year < current_year:
            counts[match.day.year] += 1
    complete_years = [year for year, count in sorted(counts.items()) if count >= 500]
    if not complete_years:
        raise ValueError("Not enough completed main-tour history for validation (need >=500 matches/year)")
    return complete_years[-3:]


def compare_circuit(
    circuit: str,
    main_path: Path,
    feeder_paths: list[Path],
    current_year: int,
) -> tuple[list[int], dict[int, tuple[Metrics, Metrics]]]:
    main = load_matches(main_path)
    if not main:
        raise ValueError(f"No valid matches found in {main_path}")
    feeders: list[Match] = []
    for path in feeder_paths:
        feeders.extend(load_matches(path))
    years = validation_years(main, current_year)

    main_by_date: dict[date, list[Match]] = defaultdict(list)
    all_by_date: dict[date, list[Match]] = defaultdict(list)
    for match in main:
        main_by_date[match.day].append(match)
        all_by_date[match.day].append(match)
    for match in feeders:
        all_by_date[match.day].append(match)

    # Remove repeats across main and feeder exports while retaining distinct results.
    for day, matches in list(all_by_date.items()):
        unique: dict[tuple[str, str, str, str], Match] = {}
        for match in matches:
            key = (day.isoformat(), match.player_a, match.player_b, match.winner)
            unique.setdefault(key, match)
        all_by_date[day] = list(unique.values())

    base_ratings: dict[str, float] = {}
    expanded_ratings: dict[str, float] = {}
    yearly: dict[int, tuple[Metrics, Metrics]] = {
        year: (Metrics(), Metrics()) for year in years
    }

    for day in sorted(set(main_by_date) | set(all_by_date)):
        target_matches = main_by_date.get(day, [])
        if day.year in yearly:
            baseline_metrics, expanded_metrics = yearly[day.year]
            for match in target_matches:
                actual_a = int(match.winner == match.player_a)
                base_p = probability(
                    base_ratings.get(match.player_a, 1500.0),
                    base_ratings.get(match.player_b, 1500.0),
                )
                expanded_p = probability(
                    expanded_ratings.get(match.player_a, 1500.0),
                    expanded_ratings.get(match.player_b, 1500.0),
                )
                baseline_metrics.add(base_p, actual_a)
                expanded_metrics.add(expanded_p, actual_a)

        # Predict every match on this tournament-start date before applying results.
        apply_date_batch(base_ratings, main_by_date.get(day, []))
        apply_date_batch(expanded_ratings, all_by_date.get(day, []))

    return years, yearly


def render_report(
    results: dict[str, tuple[list[int], dict[int, tuple[Metrics, Metrics]]]],
    current_year: int,
) -> str:
    lines = [
        "# Extended-tour archive benchmark",
        "",
        f"Generated (UTC): {datetime.now(timezone.utc).date().isoformat()}",
        "",
        "## Method",
        "",
        "- Target matches are ATP/WTA main-tour results only; lower-tier matches are never scored as targets.",
        "- For each circuit, compare chronological Elo (K=32) using main-tour history against the same Elo using main-tour plus that circuit's lower-tier history.",
        "- Each validation set is one of the last three calendar years with at least 500 main-tour matches, excluding the current year.",
        "- Results sharing the same tournament-start date are predicted before any results from that date update ratings.",
        "- Log Loss and Brier Score are primary; accuracy is descriptive. A smaller Log Loss/Brier Score is better.",
        "- The source's tourney_date is generally the tournament week start, not the exact played date. This limits the temporal precision of the benchmark.",
        "",
        "## Results",
        "",
        "| Circuit | Season | Matches | Main-only Log Loss | Expanded Log Loss | Δ Log Loss | Main-only Brier | Expanded Brier | Δ Brier |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for circuit, (years, yearly) in results.items():
        circuit_pass = True
        all_base = Metrics()
        all_expanded = Metrics()
        for year in years:
            base, expanded = yearly[year]
            base_ll, base_br, _ = base.values()
            exp_ll, exp_br, _ = expanded.values()
            lines.append(
                f"| {circuit} | {year} | {base.count:,} | {base_ll:.5f} | {exp_ll:.5f} "
                f"| {exp_ll - base_ll:+.5f} | {base_br:.5f} | {exp_br:.5f} "
                f"| {exp_br - base_br:+.5f} |"
            )
            circuit_pass = circuit_pass and (
                exp_ll <= base_ll + NO_WORSE_TOLERANCE
                and exp_br <= base_br + NO_WORSE_TOLERANCE
            )
            all_base.count += base.count
            all_expanded.count += expanded.count
            all_base.log_loss += base.log_loss
            all_base.brier += base.brier
            all_expanded.log_loss += expanded.log_loss
            all_expanded.brier += expanded.brier
        if all_base.count:
            base_ll, base_br, _ = all_base.values()
            exp_ll, exp_br, _ = all_expanded.values()
            aggregate_gain = (
                exp_ll <= base_ll + NO_WORSE_TOLERANCE
                and exp_br <= base_br + NO_WORSE_TOLERANCE
                and (
                    base_ll - exp_ll >= MINIMUM_GAIN
                    or base_br - exp_br >= MINIMUM_GAIN
                )
            )
            circuit_pass = circuit_pass and aggregate_gain
            lines += [
                "",
                f"**{circuit} aggregate:** {all_base.count:,} matches; "
                f"Log Loss {base_ll:.5f} → {exp_ll:.5f}; "
                f"Brier {base_br:.5f} → {exp_br:.5f}.",
                "",
                "**Benchmark gate:** " + (
                    "PASS — expanded history is non-worse on both metrics in every selected year and improves at least one aggregate metric."
                    if circuit_pass else
                    "NOT PASSED — evidence is insufficient to automatically prefer expanded history across all selected years."
                ),
            ]
    lines += [
        "",
        "## Interpretation",
        "",
        "This benchmark evaluates the simple equal-weight Elo baseline only. It does not test tier-specific K-factors or logistic-regression feature selection. No automatic model/weight change is made by this report; use the yearly results as a gate for follow-up validation.",
        "",
        "Archive source: Jeff Sackmann / Tennis Abstract datasets as preserved by Aneeshers/tennis-sackmann-archive, CC BY-NC-SA 4.0. Dates and missing coverage depend on the source snapshots.",
        "",
    ]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--atp-main", type=Path, required=True)
    parser.add_argument("--wta-main", type=Path, required=True)
    parser.add_argument("--challenger-men", type=Path, required=True)
    parser.add_argument("--itf-men", type=Path, required=True)
    parser.add_argument("--itf-women", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("benchmarks/extended_archive_benchmark.md"))
    args = parser.parse_args()
    current_year = datetime.now(timezone.utc).year

    results = {
        "ATP men": compare_circuit(
            "ATP men", args.atp_main, [args.challenger_men, args.itf_men], current_year
        ),
        "WTA women": compare_circuit(
            "WTA women", args.wta_main, [args.itf_women], current_year
        ),
    }
    report = render_report(results, current_year)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(report, encoding="utf-8")
    print(report)


if __name__ == "__main__":
    main()
