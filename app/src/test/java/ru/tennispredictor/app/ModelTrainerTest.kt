package ru.tennispredictor.app

import java.time.LocalDate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTrainerTest {
    @Test
    fun holdoutUsesOnlyLaterDatesAndReportsBothModels() {
        val report = ModelTrainer.trainAndEvaluate(syntheticHistory())

        assertTrue(report.trainEndDate < report.testStartDate)
        assertTrue(report.trainMatches >= 10)
        assertTrue(report.testMatches >= 5)
        assertEquals(report.testMatches, report.eloMetrics.matches)
        assertEquals(report.testMatches, report.logisticMetrics.matches)
        assertTrue(report.eloMetrics.logLoss.isFinite())
        assertTrue(report.logisticMetrics.logLoss.isFinite())
        assertTrue(report.eloMetrics.brierScore in 0.0..1.0)
        assertTrue(report.logisticMetrics.brierScore in 0.0..1.0)
        assertEquals(report.model.featureIndices.size + 1, report.model.weights.size)
        assertTrue(report.model.weights.all { it.isFinite() })
        assertEquals(16, report.candidateResults.size)
        assertEquals(3, report.candidateResults.first().folds.size)
        assertEquals(12, report.featureEffects.size)
        assertTrue(report.featureEffects.all { it.folds.size == 3 })
        assertTrue(report.featureEffects.any { it.factorName == "Недавняя форма" })
        assertTrue(report.featureEffects.any { it.factorName == "Личные встречи" })
        assertTrue(report.candidateResults.zipWithNext().all { (a, b) ->
            a.meanLogLoss <= b.meanLogLoss + 1e-12
        })
        assertTrue(report.candidateResults.all { candidate ->
            !candidate.eligibleForPrediction ||
                (candidate.passesEloTemporalGate && candidate.featureEvidencePassed)
        })
    }

    @Test
    fun trainedModelPredictsFutureMatchAndRejectsStaleHistory() {
        val history = syntheticHistory()
        val model = ModelTrainer.trainAndEvaluate(history).model.copy(recommendedForPrediction = true)

        val probability = ModelTrainer.predictFutureProbability(
            history = history,
            playerA = "A",
            playerB = "B",
            surface = Surface.HARD,
            eloA = 1600.0,
            eloB = 1500.0,
            model = model
        )
        assertTrue(probability != null && probability in 0.001..0.999)

        val newerResult = StoredMatch(
            99_999,
            MatchRecord("A", "B", "A", Surface.HARD, date = "2025-01-01")
        )
        assertNull(
            ModelTrainer.predictFutureProbability(
                history + newerResult,
                "A",
                "B",
                Surface.HARD,
                1600.0,
                1500.0,
                model
            )
        )
    }

    @Test
    fun reversingPlayersProducesComplementaryFutureProbabilities() {
        val history = syntheticHistory()
        val model = ModelTrainer.trainAndEvaluate(history).model.copy(recommendedForPrediction = true)

        val probabilityAB = ModelTrainer.predictFutureProbability(
            history, "A", "B", Surface.HARD, 1600.0, 1500.0, model
        )
        val probabilityBA = ModelTrainer.predictFutureProbability(
            history, "B", "A", Surface.HARD, 1500.0, 1600.0, model
        )

        assertTrue(probabilityAB != null && probabilityBA != null)
        assertEquals(1.0, probabilityAB!! + probabilityBA!!, 1e-9)
    }

    @Test
    fun sameDayMatchesDoNotUpdateFeaturesForEachOther() {
        val date = "2024-01-10"
        val history = listOf(
            StoredMatch(1, MatchRecord("A", "B", "A", Surface.HARD, date = date)),
            StoredMatch(2, MatchRecord("B", "C", "C", Surface.CLAY, date = date))
        )

        val samples = ModelTrainer.prepareSamples(history).samples
        assertEquals(2, samples.size)
        val emptyHistoryFeatures = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        samples.forEach { assertArrayEquals(emptyHistoryFeatures, it.features, 1e-9) }
    }

    @Test
    fun headToHeadFeatureIsSmoothedAndUsesOnlyEarlierDates() {
        val sameDay = "2024-01-01"
        val nextDay = "2024-01-02"
        val history = listOf(
            StoredMatch(1, MatchRecord("A", "B", "A", Surface.HARD, date = sameDay)),
            StoredMatch(2, MatchRecord("B", "A", "A", Surface.HARD, date = sameDay)),
            StoredMatch(3, MatchRecord("A", "B", "B", Surface.HARD, date = nextDay))
        )

        val samples = ModelTrainer.prepareSamples(history).samples
        assertEquals(3, samples.size)
        // Both same-day samples must ignore each other's outcomes.
        assertEquals(0.0, samples[0].features[5], 1e-12)
        assertEquals(0.0, samples[1].features[5], 1e-12)
        // Two earlier wins for canonical player A produce a shrunk 2 / (2 + 4) signal.
        assertEquals(1.0 / 3.0, samples[2].features[5], 1e-12)
        assertEquals(0, samples[2].label)
    }

    @Test
    fun addingFutureResultsDoesNotChangePastFeatureSnapshots() {
        val history = syntheticHistory()
        val before = ModelTrainer.prepareSamples(history).samples
        val future = StoredMatch(
            10_000,
            MatchRecord(
                playerA = "B",
                playerB = "A",
                winner = "B",
                surface = Surface.HARD,
                date = "2025-01-01"
            )
        )
        val after = ModelTrainer.prepareSamples(history + future).samples.take(before.size)

        assertEquals(before.size, after.size)
        before.indices.forEach { index ->
            assertEquals(before[index].date, after[index].date)
            assertEquals(before[index].label, after[index].label)
            assertArrayEquals(before[index].features, after[index].features, 1e-12)
            assertEquals(before[index].eloDifference, after[index].eloDifference, 1e-12)
        }
    }

    @Test
    fun winnerFirstArchiveRowsAreReorientedForLearning() {
        val history = listOf(
            StoredMatch(1, MatchRecord("A", "B", "A", Surface.HARD, date = "2024-01-01")),
            StoredMatch(2, MatchRecord("B", "A", "B", Surface.HARD, date = "2024-01-02"))
        )

        val labels = ModelTrainer.prepareSamples(history).samples.map { it.label }.toSet()
        assertEquals(setOf(0, 1), labels)
    }

    @Test(expected = IllegalStateException::class)
    fun trainingRejectsHistoryWithoutReliableDates() {
        val history = (1L..25L).map { id ->
            StoredMatch(id, MatchRecord("A", "B", "A", Surface.HARD, date = ""))
        }
        ModelTrainer.trainAndEvaluate(history)
    }

    private fun syntheticHistory(): List<StoredMatch> {
        val start = LocalDate.of(2020, 1, 1)
        val history = mutableListOf<StoredMatch>()
        var id = 1L
        repeat(60) { day ->
            val date = start.plusDays(day.toLong()).toString()
            val winnerAB = if (day % 5 == 0) "B" else "A"
            val winnerCD = if (day % 4 == 0) "D" else "C"
            val winnerAC = if (day % 3 == 0) "C" else "A"
            history += StoredMatch(id++, MatchRecord("A", "B", winnerAB, Surface.HARD, date = date))
            history += StoredMatch(id++, MatchRecord("C", "D", winnerCD, if (day % 2 == 0) Surface.HARD else Surface.CLAY, date = date))
            history += StoredMatch(id++, MatchRecord("A", "C", winnerAC, Surface.CLAY, date = date))
        }
        return history
    }
}
