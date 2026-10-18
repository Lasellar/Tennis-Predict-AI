import csv
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from download_extended_tour_archives import (  # noqa: E402
    deduplicate,
    read_source_file,
    selected_tier,
    write_archive,
)


class ExtendedTourArchiveTests(unittest.TestCase):
    def write_source(self, directory: Path, filename: str, rows: list[dict[str, str]]) -> Path:
        path = directory / filename
        path.parent.mkdir(parents=True, exist_ok=True)
        headers = ["tourney_date", "winner_name", "loser_name", "surface", "tourney_level"]
        with path.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=headers)
            writer.writeheader()
            writer.writerows(rows)
        return path

    def row(
        self,
        day: str = "20240108",
        winner: str = "Winner Player",
        loser: str = "Loser Player",
        surface: str = "Hard",
        level: str = "C",
    ) -> dict[str, str]:
        return {
            "tourney_date": day,
            "winner_name": winner,
            "loser_name": loser,
            "surface": surface,
            "tourney_level": level,
        }

    def test_challenger_source_excludes_atp_qualifying(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = self.write_source(
                Path(temp),
                "challenger.csv",
                [
                    self.row(level="C"),
                    self.row(day="20240115", winner="Tour Winner", loser="Tour Loser", level="A"),
                ],
            )
            matches = read_source_file(path, "atp_challenger", "2024-12-31")
        self.assertEqual(1, len(matches))
        self.assertEqual("challenger", matches[0].competition)
        self.assertEqual("men", matches[0].circuit)

    def test_womens_itf_filter_includes_numeric_prize_tiers_only(self) -> None:
        self.assertTrue(selected_tier("itf_women", self.row(level="15")))
        self.assertTrue(selected_tier("itf_women", self.row(level="100")))
        self.assertFalse(selected_tier("itf_women", self.row(level="I")))
        self.assertFalse(selected_tier("itf_women", self.row(level="PM")))

    def test_cutoff_and_invalid_matches_are_excluded(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = self.write_source(
                Path(temp),
                "futures.csv",
                [
                    self.row(day="20240108", level="S"),
                    self.row(day="20250101", winner="Future Winner", loser="Future Loser", level="S"),
                    self.row(day="20240108", winner="Same Name", loser="Same Name", level="S"),
                    self.row(day="bad-date", winner="Invalid Date", loser="Player", level="S"),
                ],
            )
            matches = read_source_file(path, "itf_men", "2024-12-31")
        self.assertEqual(1, len(matches))
        self.assertEqual("clay", matches[0].surface if False else matches[0].surface)

    def test_deduplication_and_export_keep_circuit_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = self.write_source(
                Path(temp),
                "challenger.csv",
                [
                    self.row(),
                    self.row(),
                    self.row(day="20240109", winner="Other Winner", loser="Other Loser"),
                ],
            )
            matches = read_source_file(path, "atp_challenger", "2024-12-31")
            unique = deduplicate(matches)
            output = Path(temp) / "export.csv"
            write_archive(unique, output)
            with output.open("r", encoding="utf-8", newline="") as stream:
                rows = list(csv.DictReader(stream))
        self.assertEqual(2, len(rows))
        self.assertEqual("circuit", "circuit" if "circuit" in rows[0] else "")
        self.assertEqual("men", rows[0]["circuit"])
        self.assertEqual("challenger", rows[0]["competition"])
        self.assertEqual(rows[0]["winner"], rows[0]["player_a"])

    def test_surface_normalization_is_preserved_in_export(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = self.write_source(
                Path(temp), "women_itf.csv",
                [self.row(surface="Grass", level="35")]
            )
            matches = read_source_file(path, "itf_women", "2024-12-31")
        self.assertEqual("grass", matches[0].surface)


if __name__ == "__main__":
    unittest.main()
