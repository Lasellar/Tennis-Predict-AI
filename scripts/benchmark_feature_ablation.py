#!/usr/bin/env python3
"""Replay date-safe ATP feature ablations for Tennis Predict AI v0.6.

The compact input must have date, player_a, player_b, winner, surface columns.
Only NumPy is required: python -m pip install numpy
"""
from __future__ import annotations

import argparse
import csv
import json
import math
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date, datetime
from pathlib import Path

try:
    import numpy as np
except ImportError as exc:
    raise SystemExit("Missing NumPy. Install it with: python -m pip install numpy") from exc

K_FACTOR = 32.0
ELO_SCALE = 400.0
RECENT_WINDOW = 10
H2H_PRIOR_MATCHES = 4.0
ITERATIONS = 140
LEARNING_RATE = 0.08
L2_STRENGTH = 0.01
NO_WORSE_TOLERANCE = 1e-4
MINIMUM_GAIN = 1e-4

PROFILES = (
    ("Elo only", (0,)),
    ("Elo + surface Elo", (0, 1)),
    ("Elo + recent form", (0, 2)),
    ("Elo + surface Elo + recent form", (0, 1, 2)),
    ("Elo + surface Elo + head-to-head", (0, 1, 5)),
    ("Elo + surface Elo + recent form + head-to-head", (0, 1, 2, 5)),
    ("Elo + surface Elo + experience", (0, 1, 3, 4)),
    ("Elo + surface Elo + experience + head-to-head", (0, 1, 3, 4, 5)),
    ("All 5 features", (0, 1, 2, 3, 4)),
    ("All 5 features + head-to-head", (0, 1, 2, 3, 4, 5)),
)

COMPARISONS = (
    ("Recent form", "Elo only", "Elo + recent form"),
    ("Recent form", "Elo + surface Elo", "Elo + surface Elo + recent form"),
    ("Recent form", "Elo + surface Elo + head-to-head",
     "Elo + surface Elo + recent form + head-to-head"),
    ("Recent form", "Elo + surface Elo + experience", "All 5 features"),
    ("Recent form", "Elo + surface Elo + experience + head-to-head",
     "All 5 features + head-to-head"),
    ("Head-to-head", "Elo + surface Elo", "Elo + surface Elo + head-to-head"),
    ("Head-to-head", "Elo + surface Elo + recent form",
     "Elo + surface Elo + recent form + head-to-head"),
    ("Head-to-head", "Elo + surface Elo + experience",
     "Elo + surface Elo + experience + head-to-head"),
    ("Head-to-head", "All 5 features", "All 5 features + head-to-head"),
)


@dataclass(frozen=True)
class Match:
    row_id: int
    match_date: date
    player_a: str
    player_b: str
    winner: str
    surface: str


@dataclass
class PlayerState:
    elo: float = 1500.0
    surface_elo: dict[str, float] = field(default_factory=dict)
    matches: int = 0
    surface_matches: dict[str, int] = field(default_factory=dict)
    recent: list[int] = field(default_factory=list)


@dataclass
class H2HState:
    wins_a: int = 0
    wins_b: int = 0


def name_key(raw: str) -> str:
    return " ".join((raw or "").strip().split()).lower()


def parse_date(raw: str) -> date | None:
    value = (raw or "").strip()
    if not value:
        return None
    try:
        if len(value) == 8 and value.isdigit():
            return datetime.strptime(value, "%Y%m%d").date()
        return date.fromisoformat(value[:10])
    except ValueError:
        return None


def surface_key(raw: str) -> str:
    value = (raw or "").strip().lower()
    if "clay" in value or "terre battue" in value or "red clay" in value:
        return "clay"
    if "grass" in value or "gazon" in value or "lawn" in value:
        return "grass"
    if "carpet" in value or "indoor" in value:
        return "indoor"
    if "hard" in value or "dur" in value:
        return "hard"
    return "other"


def tracked(surface: str) -> bool:
    return surface in {"hard", "clay", "grass"}


def probability(elo_a: float, elo_b: float) -> float:
    return 1.0 / (1.0 + math.exp(max(-35.0, min(35.0,
        (elo_b - elo_a) / ELO_SCALE * math.log(10.0)))))


def load_matches(path: Path) -> list[Match]:
    accepted: list[Match] = []
    seen: set[tuple[str, str, str]] = set()
    with path.open("r", encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        required = {"date", "player_a", "player_b", "winner", "surface"}
        if not reader.fieldnames or not required.issubset(reader.fieldnames):
            raise ValueError("CSV must contain date, player_a, player_b, winner, surface")
        for row_id, row in enumerate(reader, 1):
            dt = parse_date(row.get("date", ""))
            a = name_key(row.get("player_a", ""))
            b = name_key(row.get("player_b", ""))
            winner = name_key(row.get("winner", ""))
            if dt is None or not a or not b or a == b or winner not in {a, b}:
                continue
            a, b = sorted((a, b))
            # Match the archive builder's deduplication key: date, winner, loser.
            loser = b if winner == a else a
            dedupe = (dt.isoformat(), winner, loser)
            if dedupe in seen:
                continue
            seen.add(dedupe)
            accepted.append(Match(row_id, dt, a, b, winner, surface_key(row.get("surface", ""))))
    return sorted(accepted, key=lambda m: (m.match_date, m.row_id))


def prepare_samples(matches: list[Match]) -> tuple[list[date], np.ndarray, np.ndarray, np.ndarray]:
    by_date: dict[date, list[Match]] = defaultdict(list)
    for match in matches:
        by_date[match.match_date].append(match)

    players: dict[str, PlayerState] = {}
    meetings: dict[tuple[str, str], H2HState] = {}
    rows: list[tuple[date, list[float], int, float]] = []

    for day in sorted(by_date):
        day_matches = by_date[day]
        # Each row sees the same pre-day snapshot; no outcomes from this date leak in.
        for match in day_matches:
            a = players.setdefault(match.player_a, PlayerState())
            b = players.setdefault(match.player_b, PlayerState())
            use_surface = tracked(match.surface)
            sa = a.surface_elo.get(match.surface, 1500.0) if use_surface else 1500.0
            sb = b.surface_elo.get(match.surface, 1500.0) if use_surface else 1500.0
            form_a = sum(a.recent) / len(a.recent) if a.recent else 0.5
            form_b = sum(b.recent) / len(b.recent) if b.recent else 0.5
            h2h = meetings.get((match.player_a, match.player_b))
            h2h_diff = ((h2h.wins_a - h2h.wins_b) /
                        (h2h.wins_a + h2h.wins_b + H2H_PRIOR_MATCHES)) if h2h else 0.0
            diff = a.elo - b.elo
            features = [
                diff / ELO_SCALE,
                (sa - sb) / ELO_SCALE if use_surface else 0.0,
                form_a - form_b,
                (math.log1p(a.matches) - math.log1p(b.matches)) / 4.0,
                (math.log1p(a.surface_matches.get(match.surface, 0)) -
                 math.log1p(b.surface_matches.get(match.surface, 0))) / 4.0 if use_surface else 0.0,
                h2h_diff,
            ]
            rows.append((day, features, 1 if match.winner == match.player_a else 0, diff))

        # Aggregate Elo changes from the rating state frozen at the start of this date.
        overall_delta: dict[str, float] = defaultdict(float)
        surface_delta: dict[tuple[str, str], float] = defaultdict(float)
        for match in day_matches:
            a, b = players[match.player_a], players[match.player_b]
            actual = 1.0 if match.winner == match.player_a else 0.0
            delta = K_FACTOR * (actual - probability(a.elo, b.elo))
            overall_delta[match.player_a] += delta
            overall_delta[match.player_b] -= delta
            if tracked(match.surface):
                ra = a.surface_elo.get(match.surface, 1500.0)
                rb = b.surface_elo.get(match.surface, 1500.0)
                d = K_FACTOR * (actual - probability(ra, rb))
                surface_delta[(match.player_a, match.surface)] += d
                surface_delta[(match.player_b, match.surface)] -= d

        for player, delta in overall_delta.items():
            players[player].elo += delta
        for (player, surface), delta in surface_delta.items():
            state = players[player]
            state.surface_elo[surface] = state.surface_elo.get(surface, 1500.0) + delta

        for match in day_matches:
            a, b = players[match.player_a], players[match.player_b]
            a.matches += 1
            b.matches += 1
            if tracked(match.surface):
                a.surface_matches[match.surface] = a.surface_matches.get(match.surface, 0) + 1
                b.surface_matches[match.surface] = b.surface_matches.get(match.surface, 0) + 1
            a_won = match.winner == match.player_a
            a.recent.append(1 if a_won else 0)
            b.recent.append(0 if a_won else 1)
            del a.recent[:-RECENT_WINDOW]
            del b.recent[:-RECENT_WINDOW]

        # Update H2H only after all today's features were frozen.
        for match in day_matches:
            h = meetings.setdefault((match.player_a, match.player_b), H2HState())
            if match.winner == match.player_a:
                h.wins_a += 1
            else:
                h.wins_b += 1

    return (
        [row[0] for row in rows],
        np.asarray([row[1] for row in rows], dtype=float),
        np.asarray([row[2] for row in rows], dtype=float),
        np.asarray([row[3] for row in rows], dtype=float),
    )


def fit(x: np.ndarray, y: np.ndarray, indices: tuple[int, ...],
        iterations: int = ITERATIONS, lr: float = LEARNING_RATE,
        l2: float = L2_STRENGTH) -> np.ndarray:
    selected = x[:, indices]
    weights = np.zeros(len(indices) + 1, dtype=float)
    for _ in range(iterations):
        logits = weights[0] + selected @ weights[1:]
        p = 1.0 / (1.0 + np.exp(-np.clip(logits, -35.0, 35.0)))
        error = p - y
        grad = np.concatenate(([error.mean()], selected.T @ error / len(y)))
        grad[1:] += l2 * weights[1:]
        weights -= lr * grad
    return weights


def predict(x: np.ndarray, weights: np.ndarray, indices: tuple[int, ...]) -> np.ndarray:
    logits = weights[0] + x[:, indices] @ weights[1:]
    return 1.0 / (1.0 + np.exp(-np.clip(logits, -35.0, 35.0)))


def metrics(y: np.ndarray, raw: np.ndarray) -> dict[str, float | int]:
    p = np.clip(raw, 1e-6, 1.0 - 1e-6)
    return {
        "matches": int(len(y)),
        "accuracy": float(np.mean((p >= 0.5) == (y == 1.0))),
        "log_loss": float(np.mean(-(y * np.log(p) + (1.0 - y) * np.log(1.0 - p)))),
        "brier_score": float(np.mean((p - y) ** 2)),
    }


def no_worse(fold_deltas: list[dict[str, float]]) -> bool:
    good_each_fold = bool(fold_deltas) and all(
        d["delta_log_loss"] <= NO_WORSE_TOLERANCE and
        d["delta_brier"] <= NO_WORSE_TOLERANCE for d in fold_deltas
    )
    gain_ll = sum(d["delta_log_loss"] for d in fold_deltas) / len(fold_deltas) < -MINIMUM_GAIN
    gain_brier = sum(d["delta_brier"] for d in fold_deltas) / len(fold_deltas) < -MINIMUM_GAIN
    return good_each_fold and (gain_ll or gain_brier)


def run(path: Path) -> dict[str, object]:
    matches = load_matches(path)
    dates, x, y, elo_diff = prepare_samples(matches)
    unique_dates = sorted(set(dates))
    if len(y) < 20 or len(unique_dates) < 20:
        raise ValueError("At least 20 valid matches and 20 distinct dates are required.")
    c40, c55, c70, c85 = (int(len(unique_dates) * frac) for frac in (.40, .55, .70, .85))
    bounds = [c40, c55, c70, c85]
    if any(i <= 0 or i >= len(unique_dates) for i in bounds) or any(b <= a for a, b in zip(bounds, bounds[1:])):
        raise ValueError("Not enough unique dates for temporal folds.")

    windows = [(unique_dates[c40], unique_dates[c55]),
               (unique_dates[c55], unique_dates[c70]),
               (unique_dates[c70], unique_dates[c85])]
    folds = []
    for start, end in windows:
        tr = np.asarray([d < start for d in dates])
        te = np.asarray([(d >= start and d < end) for d in dates])
        if int(tr.sum()) < 10 or int(te.sum()) < 5:
            raise ValueError("A validation fold has too few matches.")
        folds.append((start, end, tr, te))

    candidates: dict[str, dict[str, object]] = {}
    for name, indices in PROFILES:
        fold_results = []
        for start, end, tr, te in folds:
            weights = fit(x[tr], y[tr], indices)
            model_metrics = metrics(y[te], predict(x[te], weights, indices))
            elo_metrics = metrics(y[te], 1.0 / (1.0 + np.exp(
                -np.clip(elo_diff[te] / ELO_SCALE * math.log(10.0), -35.0, 35.0))))
            fold_results.append({
                "start": start.isoformat(),
                "end_exclusive": end.isoformat(),
                "train_matches": int(tr.sum()),
                "test_matches": int(te.sum()),
                "model": model_metrics,
                "elo": elo_metrics,
            })
        candidates[name] = {
            "name": name,
            "indices": list(indices),
            "folds": fold_results,
            "mean_log_loss": float(np.mean([f["model"]["log_loss"] for f in fold_results])),
            "mean_brier_score": float(np.mean([f["model"]["brier_score"] for f in fold_results])),
            "passes_elo_gate": no_worse([{
                "delta_log_loss": f["model"]["log_loss"] - f["elo"]["log_loss"],
                "delta_brier": f["model"]["brier_score"] - f["elo"]["brier_score"],
            } for f in fold_results]),
        }

    effects = []
    for factor, baseline_name, candidate_name in COMPARISONS:
        baseline, candidate = candidates[baseline_name], candidates[candidate_name]
        paired = []
        for before, after in zip(baseline["folds"], candidate["folds"]):
            paired.append({
                "start": before["start"],
                "end_exclusive": before["end_exclusive"],
                "baseline_log_loss": before["model"]["log_loss"],
                "candidate_log_loss": after["model"]["log_loss"],
                "delta_log_loss": after["model"]["log_loss"] - before["model"]["log_loss"],
                "baseline_brier": before["model"]["brier_score"],
                "candidate_brier": after["model"]["brier_score"],
                "delta_brier": after["model"]["brier_score"] - before["model"]["brier_score"],
            })
        effect = {
            "factor": factor,
            "baseline": baseline_name,
            "candidate": candidate_name,
            "baseline_mean_log_loss": float(np.mean([f["model"]["log_loss"] for f in baseline["folds"]])),
            "candidate_mean_log_loss": float(np.mean([f["model"]["log_loss"] for f in candidate["folds"]])),
            "baseline_mean_brier": float(np.mean([f["model"]["brier_score"] for f in baseline["folds"]])),
            "candidate_mean_brier": float(np.mean([f["model"]["brier_score"] for f in candidate["folds"]])),
            "folds": paired,
        }
        effect["delta_mean_log_loss"] = effect["candidate_mean_log_loss"] - effect["baseline_mean_log_loss"]
        effect["delta_mean_brier"] = effect["candidate_mean_brier"] - effect["baseline_mean_brier"]
        effect["passes"] = no_worse(paired)
        effects.append(effect)

    return {
        "dataset": {
            "input": str(path),
            "matches": len(y),
            "unique_dates": len(unique_dates),
            "first_date": unique_dates[0].isoformat(),
            "last_date": unique_dates[-1].isoformat(),
            "folds": [{
                "start": start.isoformat(),
                "end_exclusive": end.isoformat(),
                "train_matches": int(tr.sum()),
                "test_matches": int(te.sum()),
            } for start, end, tr, te in folds],
        },
        "protocol": {
            "optimizer": "standard",
            "iterations": ITERATIONS,
            "learning_rate": LEARNING_RATE,
            "l2_strength": L2_STRENGTH,
            "per_fold_tolerance": NO_WORSE_TOLERANCE,
            "minimum_mean_gain": MINIMUM_GAIN,
            "holdout_note": "The latest 15% of unique dates is not used for candidate selection.",
        },
        "candidates": list(candidates.values()),
        "feature_effects": effects,
    }


def markdown(result: dict[str, object]) -> str:
    data = result["dataset"]
    protocol = result["protocol"]
    lines = [
        "ATP feature-ablation benchmark",
        "================================",
        "",
        "Reference replay for Tennis Predict AI v0.6. Lower Log Loss and Brier Score are better.",
        "",
        "Dataset and protocol",
        "--------------------",
        "",
        "Input: " + str(data["input"]),
        "Valid dated matches: " + format(data["matches"], ","),
        "Date span: " + str(data["first_date"]) + " through " + str(data["last_date"]),
        "Distinct tournament dates: " + str(data["unique_dates"]),
        "Optimizer: standard, " + str(protocol["iterations"]) + " iterations, learning rate " +
        str(protocol["learning_rate"]) + ", L2 " + str(protocol["l2_strength"]),
        "All matches on the same tournament date share a pre-date feature snapshot.",
        "H2H feature is (prior wins A minus prior wins B) divided by (prior meetings + 4), which shrinks small samples toward neutral.",
        "Threshold: neither metric may worsen by more than 0.0001 on any fold, and at least one mean metric must improve by 0.0001.",
        "The latest 15% of unique dates is reserved and is not used for candidate selection.",
        "",
        "Validation windows",
        "------------------",
        "",
        "Start | End (exclusive) | Train | Test",
        "--- | --- | ---: | ---:",
    ]
    for fold in data["folds"]:
        lines.append(str(fold["start"]) + " | " + str(fold["end_exclusive"]) + " | " +
                     format(fold["train_matches"], ",") + " | " + format(fold["test_matches"], ","))
    lines.extend([
        "",
        "Candidate profiles",
        "------------------",
        "",
        "Profile | Features | Mean Log Loss | Mean Brier | Fold Log Loss | Fold Brier | Elo gate",
        "--- | ---: | ---: | ---: | --- | --- | ---",
    ])
    for item in result["candidates"]:
        ll = " / ".join(f'{f["model"]["log_loss"]:.6f}' for f in item["folds"])
        br = " / ".join(f'{f["model"]["brier_score"]:.6f}' for f in item["folds"])
        lines.append(f'{item["name"]} | {len(item["indices"])} | {item["mean_log_loss"]:.6f} | ' +
                     f'{item["mean_brier_score"]:.6f} | {ll} | {br} | ' +
                     ("yes" if item["passes_elo_gate"] else "no"))
    lines.extend([
        "",
        "Paired ablation tests",
        "---------------------",
        "",
        "Delta is candidate minus baseline; a negative value is better. The factor passes only if both metrics remain non-worse within tolerance on every fold and one mean metric improves meaningfully.",
        "",
        "Factor | Baseline | Candidate | Mean Δ Log Loss | Mean Δ Brier | Per-fold Δ LL | Per-fold Δ Brier | Pass",
        "--- | --- | --- | ---: | ---: | --- | --- | ---",
    ])
    for item in result["feature_effects"]:
        ll = " / ".join(f'{f["delta_log_loss"]:+.6f}' for f in item["folds"])
        br = " / ".join(f'{f["delta_brier"]:+.6f}' for f in item["folds"])
        lines.append(f'{item["factor"]} | {item["baseline"]} | {item["candidate"]} | ' +
                     f'{item["delta_mean_log_loss"]:+.6f} | {item["delta_mean_brier"]:+.6f} | ' +
                     f'{ll} | {br} | ' + ("yes" if item["passes"] else "no"))
    lines.extend([
        "",
        "Interpretation",
        "--------------",
        "",
        "An incremental feature may help relative to a matched model without it while the full model still loses to plain Elo. The app enables a model for forecasting only if both metrics pass the Elo comparison on all three intervals and every included form/H2H feature passes its exact-context paired ablation.",
        "If no candidate passes all gates, the forecast uses Elo only; form, surface results and H2H remain visible evidence, not unvalidated probability adjustments.",
        "",
        "Source and licence",
        "------------------",
        "",
        "Jeff Sackmann / Tennis Abstract ATP match data, preserved by Aneeshers/tennis-sackmann-archive. Source data is CC BY-NC-SA 4.0: attribute the source and do not use or redistribute the downloaded archive commercially without separate permission.",
        "",
    ])
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=Path("data/benchmark/atp_2010_2024.csv"))
    parser.add_argument("--output", type=Path, help="Optional Markdown report destination")
    parser.add_argument("--json-output", type=Path, help="Optional JSON metrics destination")
    args = parser.parse_args()
    result = run(args.input)
    report = markdown(result)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(report, encoding="utf-8")
    else:
        print(report)
    if args.json_output:
        args.json_output.parent.mkdir(parents=True, exist_ok=True)
        args.json_output.write_text(json.dumps(result, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
