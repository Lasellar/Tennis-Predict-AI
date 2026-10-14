package ru.tennispredictor.app

import org.junit.Assert.assertTrue
import org.junit.Test

class RatingCalculatorTest {
    @Test
    fun surfaceRatingsAreIndependentFromOtherSurfaces() {
        val matches = listOf(
            StoredMatch(1, MatchRecord("A", "B", "A", Surface.HARD, date = "2024-01-01")),
            StoredMatch(2, MatchRecord("A", "B", "A", Surface.HARD, date = "2024-01-02")),
            StoredMatch(3, MatchRecord("A", "B", "B", Surface.CLAY, date = "2024-01-03")),
            StoredMatch(4, MatchRecord("A", "B", "B", Surface.CLAY, date = "2024-01-04"))
        )

        val snapshot = RatingCalculator.calculate(matches)
        val hardA = snapshot.hard.first { it.name == "A" }.rating
        val hardB = snapshot.hard.first { it.name == "B" }.rating
        val clayA = snapshot.clay.first { it.name == "A" }.rating
        val clayB = snapshot.clay.first { it.name == "B" }.rating

        assertTrue(hardA > hardB)
        assertTrue(clayA < clayB)
    }

    @Test
    fun emptyHistoryProducesEmptyRankings() {
        val snapshot = RatingCalculator.calculate(emptyList())
        assertTrue(snapshot.overall.isEmpty())
        assertTrue(snapshot.hard.isEmpty())
        assertTrue(snapshot.clay.isEmpty())
        assertTrue(snapshot.grass.isEmpty())
    }
}
