package ru.tennispredictor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealMatchPredictionTest {
    @Test
    fun playerSearchIsCaseInsensitiveAndPrefersPrefixMatches() {
        val names = listOf("Alexander Zverev", "Alex de Minaur", "Novak Djokovic", "Alexei Popyrin")
        val found = RealMatchPredictor.searchPlayers("alex", names)

        assertEquals("Alex de Minaur", found.first())
        assertTrue(found.contains("Alexander Zverev"))
        assertTrue(found.contains("Alexei Popyrin"))
        assertFalse(found.contains("Novak Djokovic"))
    }

    @Test
    fun playerDirectoryDeduplicatesNamesIgnoringCaseAndWhitespace() {
        val history = listOf(
            StoredMatch(1, MatchRecord("  Player   One ", "Player Two", "Player One", date = "2024-01-01")),
            StoredMatch(2, MatchRecord("player one", "Player Three", "Player Three", date = "2024-01-02"))
        )

        val names = RealMatchPredictor.playerNames(history)
        assertEquals(3, names.size)
        assertEquals(1, names.count { it.equals("Player One", ignoreCase = true) })
    }

    @Test
    fun latestResultsAndSurfaceFormAreSeparatedCorrectly() {
        val history = listOf(
            StoredMatch(1, MatchRecord("A", "B", "A", Surface.HARD, date = "2024-01-01")),
            StoredMatch(2, MatchRecord("B", "A", "B", Surface.CLAY, date = "2024-01-02")),
            StoredMatch(3, MatchRecord("C", "A", "A", Surface.HARD, date = "2024-01-03"))
        )

        val result = RealMatchPredictor.assess(history, "A", "B", Surface.HARD, model = null)

        assertEquals(3, result.formA.totalMatches)
        assertEquals(3, result.formA.recentOverallCount)
        assertEquals(2, result.formA.recentOverallWins)
        assertEquals("В · П · В", result.formA.recentOverallResults)
        assertEquals(2, result.formA.surfaceMatches)
        assertEquals(2, result.formA.recentSurfaceWins)
        assertEquals("В · В", result.formA.recentSurfaceResults)
    }

    @Test
    fun unknownPlayersReceiveWideUncertaintyInterval() {
        val estimate = RealMatchPredictor.assess(
            history = emptyList(),
            playerA = "Player A",
            playerB = "Player B",
            surface = Surface.HARD,
            model = null
        )

        assertEquals(0.5, estimate.probabilityA, 1e-9)
        assertTrue(estimate.lowerProbabilityA < estimate.probabilityA)
        assertTrue(estimate.upperProbabilityA > estimate.probabilityA)
        assertTrue(estimate.upperProbabilityA - estimate.lowerProbabilityA > 0.7)
        assertEquals("Слабая опора на историю", estimate.supportLabel)
    }

    @Test
    fun swappedPlayerOrderProducesComplementaryEloProbabilities() {
        val history = listOf(
            StoredMatch(1, MatchRecord("A", "B", "A", Surface.HARD, date = "2024-01-01")),
            StoredMatch(2, MatchRecord("A", "B", "A", Surface.HARD, date = "2024-01-02")),
            StoredMatch(3, MatchRecord("B", "A", "A", Surface.HARD, date = "2024-01-03"))
        )
        val forward = RealMatchPredictor.assess(history, "A", "B", Surface.HARD, model = null)
        val reverse = RealMatchPredictor.assess(history, "B", "A", Surface.HARD, model = null)

        assertEquals(1.0, forward.probabilityA + reverse.probabilityA, 1e-9)
    }
}
