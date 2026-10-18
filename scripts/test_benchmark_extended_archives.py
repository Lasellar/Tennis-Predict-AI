import csv
import sys
import tempfile
import unittest
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from benchmark_extended_archives import (  # noqa: E402
    Match,
    Metrics,
    apply_date_batch,
    load_matches,
)


class ExtendedArchiveBenchmarkTests(unittest.TestCase):
    def test_loader_normalizes_players_without_losing_winner_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "matches.csv"
            with path.open("w", encoding="utf-8", newline="") as stream:
                writer = csv.writer(stream)
                writer.writerow(["date", "player_a", "player_b", "winner", "surface"])
                writer.writerow(["2024-01-08", "Zed Player", "Alpha Player", "Zed Player", "hard"])
            matches = load_matches(path)
        self.assertEqual(1, len(matches))
        self.assertEqual("alpha player", matches[0].player_a)
        self.assertEqual("zed player", matches[0].player_b)
        self.assertEqual("zed player", matches[0].winner)

    def test_same_date_batch_is_independent_of_row_order(self) -> None:
        matches = [
            Match(date(2024, 1, 8), "a", "b", "a"),
            Match(date(2024, 1, 8), "a", "c", "c"),
            Match(date(2024, 1, 8), "b", "c", "b"),
        ]
        first: dict[str, float] = {}
        second: dict[str, float] = {}
        apply_date_batch(first, matches)
        apply_date_batch(second, list(reversed(matches)))
        self.assertEqual(set(first), set(second))
        for player in first:
            self.assertAlmostEqual(first[player], second[player], places=10)

    def test_metrics_penalize_confident_wrong_predictions(self) -> None:
        good = Metrics()
        bad = Metrics()
        good.add(0.9, 1)
        bad.add(0.1, 1)
        self.assertLess(good.values()[0], bad.values()[0])
        self.assertLess(good.values()[1], bad.values()[1])


if __name__ == "__main__":
    unittest.main()
