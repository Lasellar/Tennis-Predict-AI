package ru.tennispredictor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchPredictionEngineTest {
    private fun stored(
        id: Long,
        date: String,
        playerA: String,
        playerB: String,
        winner: String,
        surface: Surface = Surface.HARD
    ) = StoredMatch(
        id = id,
        record = MatchRecord(
            playerA = playerA,
            playerB = playerB,
            winner = winner,
            surface = surface,
            date = date
        )
    )

    @Test
    fun successfulRecentHistoryRaisesForecastAndIsShownInEvidence() {
        val history = (1L..10L).map { id ->
            stored(
                id = id,
                date = LocalDateFixture.date(id),
                playerA = "A. Player",
                playerB = "Opponent $id",
                winner = "A. Player",
                surface = if (id % 2L == 0L) Surface.HARD else Surface.CLAY
            )
        }

        val forecast = MatchPredictionEngine.calculate(
            history = history,
            playerA = "A. Player",
            playerB = "New Player",
            surface = Surface.HARD,
            matchDate = "2026-10-20"
        )

        assertTrue(forecast.probabilityA > 0.5)
        assertEquals(10, forecast.playerAEvidence.totalMatches)
        assertEquals(5, forecast.playerAEvidence.surfaceMatches)
        assertEquals("10/10", forecast.playerAEvidence.recentFormLabel)
        assertTrue(forecast.probabilityLow <= forecast.probabilityA)
        assertTrue(forecast.probabilityHigh >= forecast.probabilityA)
    }

    @Test
    fun resultsOnForecastDateDoNotLeakIntoPrediction() {
        val onMatchDay = listOf(
            stored(1, "2026-10-20", "A. Player", "B. Player", "B. Player")
        )

        val withMatchDayResult = MatchPredictionEngine.calculate(
            history = onMatchDay,
            playerA = "A. Player",
            playerB = "B. Player",
            surface = Surface.HARD,
            matchDate = "2026-10-20"
        )
        val withoutResult = MatchPredictionEngine.calculate(
            history = emptyList(),
            playerA = "A. Player",
            playerB = "B. Player",
            surface = Surface.HARD,
            matchDate = "2026-10-20"
        )

        assertEquals(withoutResult.probabilityA, withMatchDayResult.probabilityA, 1e-12)
        assertEquals(0, withMatchDayResult.playerAEvidence.totalMatches)
        assertEquals(0, withMatchDayResult.playerBEvidence.totalMatches)
        assertEquals(0, withMatchDayResult.headToHead.totalMatches)
    }

    @Test
    fun headToHeadShowsOnlyPastMeetingsAndSeparatesSelectedSurface() {
        val history = listOf(
            stored(1, "2026-01-01", "A. Player", "B. Player", "A. Player", Surface.HARD),
            stored(2, "2026-01-03", "B. Player", "A. Player", "B. Player", Surface.CLAY),
            stored(3, "2026-02-01", "A. Player", "B. Player", "B. Player", Surface.HARD),
            stored(4, "2026-10-20", "A. Player", "B. Player", "A. Player", Surface.HARD),
            stored(5, "2026-10-21", "B. Player", "A. Player", "B. Player", Surface.HARD)
        )

        val forecast = MatchPredictionEngine.calculate(
            history = history,
            playerA = "A. Player",
            playerB = "B. Player",
            surface = Surface.HARD,
            matchDate = "2026-10-20"
        )

        assertEquals(3, forecast.headToHead.totalMatches)
        assertEquals(1, forecast.headToHead.winsA)
        assertEquals(2, forecast.headToHead.winsB)
        assertEquals(2, forecast.headToHead.surfaceMatches)
        assertEquals(1, forecast.headToHead.surfaceWinsA)
        assertEquals(1, forecast.headToHead.surfaceWinsB)
        assertEquals(3, forecast.headToHead.recentMatches.size)
        assertEquals("2026-02-01", forecast.headToHead.recentMatches.first().date)
        assertEquals("B. Player", forecast.headToHead.recentMatches.first().winner)
        assertTrue(forecast.activeFeatureIndices.isEmpty())
    }

    @Test
    fun savedPredictionMetricsAreCalculatedFromResolvedForecastsOnly() {
        val predictions = listOf(
            SavedPrediction(
                id = 1,
                createdAt = 1,
                matchDate = "2026-10-20",
                playerA = "A. Player",
                playerB = "B. Player",
                surface = Surface.HARD,
                probabilityA = 0.8,
                overallEloA = 1600.0,
                overallEloB = 1500.0,
                surfaceEloA = 1600.0,
                surfaceEloB = 1500.0,
                formA = "8/10",
                formB = "4/10",
                totalMatchesA = 10,
                totalMatchesB = 10,
                surfaceMatchesA = 5,
                surfaceMatchesB = 5,
                source = "test",
                uncertaintyLabel = "Средняя",
                uncertaintyReason = "test",
                probabilityLow = 0.65,
                probabilityHigh = 0.90,
                resultWinner = "A. Player"
            ),
            SavedPrediction(
                id = 2,
                createdAt = 2,
                matchDate = "2026-10-21",
                playerA = "C. Player",
                playerB = "D. Player",
                surface = Surface.CLAY,
                probabilityA = 0.7,
                overallEloA = 1600.0,
                overallEloB = 1500.0,
                surfaceEloA = 1600.0,
                surfaceEloB = 1500.0,
                formA = "7/10",
                formB = "5/10",
                totalMatchesA = 10,
                totalMatchesB = 10,
                surfaceMatchesA = 4,
                surfaceMatchesB = 4,
                source = "test",
                uncertaintyLabel = "Средняя",
                uncertaintyReason = "test",
                probabilityLow = 0.5,
                probabilityHigh = 0.85
            )
        )

        val report = SavedPredictionEvaluator.evaluate(predictions)
        assertEquals(1, report.resolved)
        assertEquals(1, report.correct)
        assertEquals(1.0, report.accuracy, 1e-12)
        assertEquals(-kotlin.math.ln(0.8), report.logLoss, 1e-12)
        assertEquals(0.04, report.brierScore, 1e-12)
    }

    @Test
    fun playerSearchIsCaseInsensitiveAndDeduplicatesNames() {
        val history = listOf(
            stored(1, "2026-01-01", "A. Player", "B. Player", "A. Player"),
            stored(2, "2026-01-02", "a. player", "C. Player", "C. Player")
        )

        assertEquals(3, MatchPredictionEngine.availablePlayers(history).size)
    }
}

private object LocalDateFixture {
    fun date(day: Long): String = "2026-10-" + day.toString().padStart(2, '0')
}
