package ru.tennispredictor.app

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PlayerEvidence(
    val displayName: String,
    val overallElo: Double,
    val surfaceElo: Double,
    val totalMatches: Int,
    val surfaceMatches: Int,
    val recentWins: Int,
    val recentMatches: Int,
    val surfaceRecentWins: Int,
    val surfaceRecentMatches: Int,
    val lastMatchDate: String,
    val daysSinceLastMatch: Long?,
    val usedUndatedMatches: Int
) {
    val recentWinRate: Double
        get() = if (recentMatches == 0) 0.5 else recentWins.toDouble() / recentMatches

    val surfaceRecentWinRate: Double
        get() = if (surfaceRecentMatches == 0) 0.5 else surfaceRecentWins.toDouble() / surfaceRecentMatches

    val recentFormLabel: String
        get() = if (recentMatches == 0) "нет данных" else "$recentWins/$recentMatches"
}

data class HeadToHeadMatch(
    val date: String,
    val surface: Surface,
    val winner: String,
    val playerAWon: Boolean
)

data class HeadToHeadEvidence(
    val totalMatches: Int,
    val winsA: Int,
    val winsB: Int,
    val surfaceMatches: Int,
    val surfaceWinsA: Int,
    val surfaceWinsB: Int,
    val undatedMatches: Int,
    /** Last five meetings in the engine's pre-match history order, newest first when dates are complete. */
    val recentMatches: List<HeadToHeadMatch>
)

data class MatchForecast(
    val matchDate: String,
    val playerA: String,
    val playerB: String,
    val surface: Surface,
    val probabilityA: Double,
    val probabilityB: Double,
    val overallRatingDifference: Double,
    val adjustedRatingDifference: Double,
    val playerAEvidence: PlayerEvidence,
    val playerBEvidence: PlayerEvidence,
    val headToHead: HeadToHeadEvidence,
    /** Canonical ModelTrainer feature indices actually used; empty means Elo/form fallback. */
    val activeFeatureIndices: List<Int>,
    val uncertaintyLabel: String,
    val uncertaintyReason: String,
    val probabilityLow: Double,
    val probabilityHigh: Double,
    val source: String
)

data class SavedPrediction(
    val id: Long = 0L,
    val createdAt: Long,
    val matchDate: String,
    val playerA: String,
    val playerB: String,
    val surface: Surface,
    val probabilityA: Double,
    val overallEloA: Double,
    val overallEloB: Double,
    val surfaceEloA: Double,
    val surfaceEloB: Double,
    val formA: String,
    val formB: String,
    val totalMatchesA: Int,
    val totalMatchesB: Int,
    val surfaceMatchesA: Int,
    val surfaceMatchesB: Int,
    val source: String,
    val uncertaintyLabel: String,
    val uncertaintyReason: String,
    val probabilityLow: Double,
    val probabilityHigh: Double,
    val resultWinner: String? = null
)

data class PredictionAccuracy(
    val resolved: Int,
    val correct: Int,
    val accuracy: Double,
    val logLoss: Double,
    val brierScore: Double
)

object SavedPredictionEvaluator {
    fun evaluate(predictions: List<SavedPrediction>): PredictionAccuracy {
        val resolved = predictions.filter { !it.resultWinner.isNullOrBlank() }
        if (resolved.isEmpty()) return PredictionAccuracy(0, 0, 0.0, 0.0, 0.0)

        var correct = 0
        var logLoss = 0.0
        var brier = 0.0
        resolved.forEach { item ->
            val pA = item.probabilityA.coerceIn(0.001, 0.999)
            val actualA = item.resultWinner!!.trim().equals(item.playerA.trim(), ignoreCase = true)
            val actual = if (actualA) 1.0 else 0.0
            if ((pA >= 0.5) == actualA) correct++
            logLoss += if (actualA) -kotlin.math.ln(pA) else -kotlin.math.ln(1.0 - pA)
            brier += (pA - actual) * (pA - actual)
        }
        return PredictionAccuracy(
            resolved = resolved.size,
            correct = correct,
            accuracy = correct.toDouble() / resolved.size,
            logLoss = logLoss / resolved.size,
            brierScore = brier / resolved.size
        )
    }
}

/**
 * Builds forecasts only from results dated before the requested match date.
 * When the selected logistic model is current and its training history is fully dated,
 * it is used; otherwise the transparent Elo + surface + recent-form fallback is used.
 */
object MatchPredictionEngine {
    private const val K_FACTOR = 32.0
    private const val DEFAULT_ELO = 1500.0
    private const val MAX_RECENT = 10

    private data class HistoricalResult(
        val date: String,
        val surface: Surface,
        val won: Boolean,
        val opponent: String
    )

    private data class PlayerState(
        var overallElo: Double = DEFAULT_ELO,
        val surfaceElos: MutableMap<Surface, Double> = mutableMapOf(),
        val results: MutableList<HistoricalResult> = mutableListOf(),
        var lastMatchDate: String = "",
        var undatedMatches: Int = 0
    )

    fun availablePlayers(history: List<StoredMatch>): List<String> {
        val names = linkedMapOf<String, String>()
        history.sortedBy { it.id }.forEach { stored ->
            listOf(stored.record.playerA, stored.record.playerB).forEach { raw ->
                val display = collapseWhitespace(raw)
                if (display.isNotBlank()) {
                    val key = normalizeName(display)
                    if (!names.containsKey(key)) names[key] = display
                }
            }
        }
        return names.values.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun calculate(
        history: List<StoredMatch>,
        playerA: String,
        playerB: String,
        surface: Surface,
        matchDate: String,
        model: LogisticRegressionModel? = null
    ): MatchForecast {
        val cleanA = collapseWhitespace(playerA)
        val cleanB = collapseWhitespace(playerB)
        require(cleanA.isNotBlank() && cleanB.isNotBlank()) { "Укажи обоих игроков." }
        require(normalizeName(cleanA) != normalizeName(cleanB)) { "Игроки должны различаться." }
        val cutoff = parseDate(matchDate)
            ?: throw IllegalArgumentException("Дата матча должна быть в формате ГГГГ-ММ-ДД.")
        require(surface in listOf(Surface.HARD, Surface.CLAY, Surface.GRASS, Surface.INDOOR)) {
            "Выбери конкретное покрытие корта."
        }

        // Never let a match on the prediction date (or a later date) leak into the forecast.
        val relevant = history.filter { stored ->
            val parsed = parseDate(stored.record.date)
            parsed == null || parsed.isBefore(cutoff)
        }
        val ordered = if (history.all { parseDate(it.record.date) != null }) {
            relevant.sortedWith(compareBy<StoredMatch> { parseDate(it.record.date) }.thenBy { it.id })
        } else {
            // The project import policy preserves insertion order when dates are mixed/missing.
            relevant.sortedBy { it.id }
        }

        val states = mutableMapOf<String, PlayerState>()
        val displayNames = linkedMapOf<String, String>()
        val validMatches = ordered.mapNotNull { stored ->
            val record = stored.record
            val a = normalizeName(record.playerA)
            val b = normalizeName(record.playerB)
            val winner = normalizeName(record.winner)
            if (a.isBlank() || b.isBlank() || a == b || (winner != a && winner != b)) {
                null
            } else {
                if (!displayNames.containsKey(a)) displayNames[a] = collapseWhitespace(record.playerA)
                if (!displayNames.containsKey(b)) displayNames[b] = collapseWhitespace(record.playerB)
                Triple(stored, a, b)
            }
        }

        for ((stored, keyA, keyB) in validMatches) {
            val record = stored.record
            val stateA = states.getOrPut(keyA) { PlayerState() }
            val stateB = states.getOrPut(keyB) { PlayerState() }
            val aWon = normalizeName(record.winner) == keyA
            val ratingA = stateA.overallElo
            val ratingB = stateB.overallElo
            val delta = K_FACTOR * ((if (aWon) 1.0 else 0.0) - TennisPredictor.probability(ratingA, ratingB))
            stateA.overallElo = ratingA + delta
            stateB.overallElo = ratingB - delta

            if (record.surface != Surface.OTHER) {
                val surfaceA = stateA.surfaceElos.getOrDefault(record.surface, DEFAULT_ELO)
                val surfaceB = stateB.surfaceElos.getOrDefault(record.surface, DEFAULT_ELO)
                val surfaceDelta = K_FACTOR * ((if (aWon) 1.0 else 0.0) - TennisPredictor.probability(surfaceA, surfaceB))
                stateA.surfaceElos[record.surface] = surfaceA + surfaceDelta
                stateB.surfaceElos[record.surface] = surfaceB - surfaceDelta
            }

            val parsedDate = parseDate(record.date)
            if (parsedDate == null) {
                stateA.undatedMatches++
                stateB.undatedMatches++
            } else {
                stateA.lastMatchDate = record.date
                stateB.lastMatchDate = record.date
            }
            stateA.results += HistoricalResult(record.date, record.surface, aWon, keyB)
            stateB.results += HistoricalResult(record.date, record.surface, !aWon, keyA)
        }

        val keyA = normalizeName(cleanA)
        val keyB = normalizeName(cleanB)
        val stateA = states[keyA] ?: PlayerState()
        val stateB = states[keyB] ?: PlayerState()
        val evidenceA = evidence(cleanA, stateA, surface, cutoff, displayNames[keyA])
        val evidenceB = evidence(cleanB, stateB, surface, cutoff, displayNames[keyB])

        // H2H is gathered from the same pre-match history snapshot as the forecast.
        // It is displayed as context, but does not change the model probability.
        val headToHeadMatches = validMatches.mapNotNull { (stored, historyA, historyB) ->
            val isMeeting =
                (historyA == keyA && historyB == keyB) ||
                    (historyA == keyB && historyB == keyA)
            if (!isMeeting) {
                null
            } else {
                val winnerIsA = normalizeName(stored.record.winner) == keyA
                HeadToHeadMatch(
                    date = stored.record.date,
                    surface = stored.record.surface,
                    winner = if (winnerIsA) cleanA else cleanB,
                    playerAWon = winnerIsA
                )
            }
        }
        val surfaceMeetings = headToHeadMatches.filter { it.surface == surface }
        val headToHead = HeadToHeadEvidence(
            totalMatches = headToHeadMatches.size,
            winsA = headToHeadMatches.count { it.playerAWon },
            winsB = headToHeadMatches.count { !it.playerAWon },
            surfaceMatches = surfaceMeetings.size,
            surfaceWinsA = surfaceMeetings.count { it.playerAWon },
            surfaceWinsB = surfaceMeetings.count { !it.playerAWon },
            undatedMatches = headToHeadMatches.count { parseDate(it.date) == null },
            recentMatches = headToHeadMatches.takeLast(5).reversed()
        )

        val overallDifference = evidenceA.overallElo - evidenceB.overallElo
        val surfaceDifference = evidenceA.surfaceElo - evidenceB.surfaceElo
        val commonSurfaceHistory = minOf(evidenceA.surfaceMatches, evidenceB.surfaceMatches)
        val surfaceWeight = 0.35 * commonSurfaceHistory.toDouble() / (commonSurfaceHistory + 8.0)
        val commonRecentHistory = minOf(evidenceA.recentMatches, evidenceB.recentMatches)
        val recentWeight = (commonRecentHistory.toDouble() / MAX_RECENT).coerceIn(0.0, 1.0)
        val recentFormDifference = effectiveForm(evidenceA) - effectiveForm(evidenceB)
        val adjustedDifference = overallDifference * (1.0 - surfaceWeight) +
            surfaceDifference * surfaceWeight +
            100.0 * recentFormDifference * recentWeight

        val modelSafe = model != null &&
            model.recommendedForPrediction &&
            history.all { parseDate(it.record.date) != null } &&
            (history.mapNotNull { parseDate(it.record.date) }.maxOrNull()?.isBefore(cutoff) ?: true)

        fun learnedProbability(eloA: Double, eloB: Double): Double? {
            if (!modelSafe || model == null) return null
            return ModelTrainer.predictFutureProbability(
                history = history,
                playerA = cleanA,
                playerB = cleanB,
                surface = surface,
                eloA = eloA,
                eloB = eloB,
                model = model
            )
        }

        val learned = learnedProbability(evidenceA.overallElo, evidenceB.overallElo)
        val probability = learned ?: TennisPredictor.probability(DEFAULT_ELO + adjustedDifference, DEFAULT_ELO)
        val lowSensitivity = learned?.let {
            learnedProbability(evidenceA.overallElo - 100.0, evidenceB.overallElo + 100.0)
        } ?: TennisPredictor.probability(DEFAULT_ELO + adjustedDifference - 200.0, DEFAULT_ELO)
        val highSensitivity = learned?.let {
            learnedProbability(evidenceA.overallElo + 100.0, evidenceB.overallElo - 100.0)
        } ?: TennisPredictor.probability(DEFAULT_ELO + adjustedDifference + 200.0, DEFAULT_ELO)
        val lower = minOf(lowSensitivity ?: probability, highSensitivity ?: probability, probability)
        val upper = maxOf(lowSensitivity ?: probability, highSensitivity ?: probability, probability)

        val minimumTotal = minOf(evidenceA.totalMatches, evidenceB.totalMatches)
        val minimumSurface = minOf(evidenceA.surfaceMatches, evidenceB.surfaceMatches)
        val minimumRecent = minOf(evidenceA.recentMatches, evidenceB.recentMatches)
        val longestRecentGap = listOfNotNull(evidenceA.daysSinceLastMatch, evidenceB.daysSinceLastMatch).maxOrNull()
        val uncertainty = when {
            minimumTotal >= 30 && minimumSurface >= 10 && minimumRecent >= 8 &&
                evidenceA.usedUndatedMatches == 0 && evidenceB.usedUndatedMatches == 0 &&
                (longestRecentGap == null || longestRecentGap <= 90) -> "Низкая"
            minimumTotal >= 10 && minimumSurface >= 3 && minimumRecent >= 5 &&
                (longestRecentGap == null || longestRecentGap <= 180) -> "Средняя"
            else -> "Высокая"
        }
        val uncertaintyReason = buildList {
            if (minimumTotal < 10) add("мало общей истории у одного из игроков")
            if (minimumSurface < 3) add("мало матчей на этом покрытии")
            if (minimumRecent < 5) add("недостаточно недавних результатов")
            if (longestRecentGap != null && longestRecentGap > 180) add("давно не было матчей")
            if (evidenceA.usedUndatedMatches + evidenceB.usedUndatedMatches > 0) add("часть использованной истории без дат")
            if (isEmpty()) add("достаточный объём недавней и поверхностной истории")
        }.joinToString("; ")

        return MatchForecast(
            matchDate = cutoff.toString(),
            playerA = cleanA,
            playerB = cleanB,
            surface = surface,
            probabilityA = probability.coerceIn(0.001, 0.999),
            probabilityB = (1.0 - probability).coerceIn(0.001, 0.999),
            overallRatingDifference = overallDifference,
            adjustedRatingDifference = adjustedDifference,
            playerAEvidence = evidenceA,
            playerBEvidence = evidenceB,
            headToHead = headToHead,
            activeFeatureIndices = if (learned != null && model != null) model.featureIndices.toList() else emptyList(),
            uncertaintyLabel = uncertainty,
            uncertaintyReason = uncertaintyReason,
            probabilityLow = lower.coerceIn(0.001, 0.999),
            probabilityHigh = upper.coerceIn(0.001, 0.999),
            source = if (learned != null && model != null) {
                "Логистическая регрессия: ${model.profileName}"
            } else {
                "Elo + последние результаты + покрытие"
            }
        )
    }

    private fun evidence(
        requestedName: String,
        state: PlayerState,
        surface: Surface,
        cutoff: LocalDate,
        displayName: String?
    ): PlayerEvidence {
        val recent = state.results.takeLast(MAX_RECENT)
        val surfaceRecent = state.results.filter { it.surface == surface }.takeLast(MAX_RECENT)
        val lastDate = state.lastMatchDate
        val daysSince = parseDate(lastDate)?.let { java.time.temporal.ChronoUnit.DAYS.between(it, cutoff) }
        return PlayerEvidence(
            displayName = displayName ?: requestedName,
            overallElo = state.overallElo,
            surfaceElo = state.surfaceElos.getOrDefault(surface, DEFAULT_ELO),
            totalMatches = state.results.size,
            surfaceMatches = state.results.count { it.surface == surface },
            recentWins = recent.count { it.won },
            recentMatches = recent.size,
            surfaceRecentWins = surfaceRecent.count { it.won },
            surfaceRecentMatches = surfaceRecent.size,
            lastMatchDate = lastDate,
            daysSinceLastMatch = daysSince,
            usedUndatedMatches = state.undatedMatches
        )
    }

    private fun effectiveForm(evidence: PlayerEvidence): Double {
        if (evidence.recentMatches == 0) return 0.5
        val overall = evidence.recentWinRate
        return if (evidence.surfaceRecentMatches >= 3) {
            0.7 * overall + 0.3 * evidence.surfaceRecentWinRate
        } else {
            overall
        }
    }

    private fun parseDate(value: String): LocalDate? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        return runCatching {
            if (trimmed.matches(Regex("\\d{8}"))) {
                LocalDate.parse(trimmed, DateTimeFormatter.BASIC_ISO_DATE)
            } else {
                LocalDate.parse(trimmed.take(10))
            }
        }.getOrNull()
    }

    private fun normalizeName(value: String): String =
        collapseWhitespace(value).lowercase(Locale.ROOT)

    private fun collapseWhitespace(value: String): String =
        value.trim().replace(Regex("\\s+"), " ")
}
