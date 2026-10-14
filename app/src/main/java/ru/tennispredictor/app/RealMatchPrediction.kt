package ru.tennispredictor.app

import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Compact form summary derived only from matches already stored on the device. */
data class PlayerFormSnapshot(
    val totalMatches: Int,
    val surfaceMatches: Int,
    val recentOverallWins: Int,
    val recentOverallCount: Int,
    val recentOverallResults: String,
    val recentSurfaceWins: Int,
    val recentSurfaceCount: Int,
    val recentSurfaceResults: String
)

/**
 * A future-match estimate. The probability is the central estimate; the 80% band is
 * a transparent heuristic based on the amount of player/surface history, not a
 * statistically calibrated confidence interval.
 */
data class RealMatchAssessment(
    val playerA: String,
    val playerB: String,
    val surface: Surface,
    val probabilityA: Double,
    val probabilityB: Double,
    val lowerProbabilityA: Double,
    val upperProbabilityA: Double,
    val ratingA: Double,
    val ratingB: Double,
    val formA: PlayerFormSnapshot,
    val formB: PlayerFormSnapshot,
    val source: String,
    val supportLabel: String
)

object RealMatchPredictor {
    private const val RATING_SCALE = 400.0
    private const val EIGHTY_PERCENT_Z = 1.2815515655
    private const val MIN_PROBABILITY = 0.001
    private const val MAX_PROBABILITY = 0.999

    /** Returns one display name per normalized name, sorted for predictable search. */
    fun playerNames(history: List<StoredMatch>): List<String> {
        val unique = linkedMapOf<String, String>()
        history.forEach { stored ->
            listOf(stored.record.playerA, stored.record.playerB).forEach { rawName ->
                val name = normalizeName(rawName)
                if (name.isNotEmpty() && name !in unique) unique[name] = rawName.trim().replace(Regex("\\s+"), " ")
            }
        }
        return unique.values.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun searchPlayers(query: String, names: List<String>, limit: Int = 8): List<String> {
        val needle = normalizeName(query)
        if (needle.isEmpty()) return emptyList()
        return names.asSequence()
            .filter { normalizeName(it).contains(needle) }
            .sortedWith(
                compareBy<String> { if (normalizeName(it).startsWith(needle)) 0 else 1 }
                    .thenBy(String.CASE_INSENSITIVE_ORDER)
            )
            .take(limit.coerceAtLeast(1))
            .toList()
    }

    fun assess(
        history: List<StoredMatch>,
        playerA: String,
        playerB: String,
        surface: Surface,
        model: LogisticRegressionModel?
    ): RealMatchAssessment {
        require(playerA.isNotBlank() && playerB.isNotBlank()) { "Укажи имена обоих игроков." }
        require(normalizeName(playerA) != normalizeName(playerB)) { "Игроки должны различаться." }

        val snapshot = RatingCalculator.calculate(history)
        val formA = formFor(history, playerA, surface)
        val formB = formFor(history, playerB, surface)
        val ratingA = ratingFor(snapshot, playerA, surface)
        val ratingB = ratingFor(snapshot, playerB, surface)

        val modelProbability = model?.let {
            ModelTrainer.predictFutureProbability(
                history = history,
                playerA = playerA.trim(),
                playerB = playerB.trim(),
                surface = surface,
                eloA = ratingA,
                eloB = ratingB,
                model = it
            )
        }
        val fallback = TennisPredictor.predict(playerA.trim(), playerB.trim(), ratingA, ratingB)
        val probabilityA = (modelProbability ?: fallback.probabilityA).coerceIn(
            MIN_PROBABILITY,
            MAX_PROBABILITY
        )
        val source = when {
            modelProbability != null ->
                "Логистическая регрессия · ${model?.profileName ?: "обученная модель"}"
            model == null -> "Elo · модель ещё не обучена"
            !model.recommendedForPrediction -> "Elo · модель не улучшила качество временной проверки"
            else -> "Elo · история изменилась после обучения; модель требует переобучения"
        }

        val lower = uncertaintyBound(
            probability = probabilityA,
            evidenceA = evidenceWeight(formA),
            evidenceB = evidenceWeight(formB),
            direction = -1
        )
        val upper = uncertaintyBound(
            probability = probabilityA,
            evidenceA = evidenceWeight(formA),
            evidenceB = evidenceWeight(formB),
            direction = 1
        )
        val weakestPlayerEvidence = minOf(evidenceWeight(formA), evidenceWeight(formB))
        val support = when {
            weakestPlayerEvidence < 5.0 -> "Слабая опора на историю"
            weakestPlayerEvidence < 20.0 -> "Умеренная опора на историю"
            else -> "Хорошая опора на историю"
        }

        return RealMatchAssessment(
            playerA = playerA.trim(),
            playerB = playerB.trim(),
            surface = surface,
            probabilityA = probabilityA,
            probabilityB = 1.0 - probabilityA,
            lowerProbabilityA = minOf(lower, probabilityA),
            upperProbabilityA = maxOf(upper, probabilityA),
            ratingA = ratingA,
            ratingB = ratingB,
            formA = formA,
            formB = formB,
            source = source,
            supportLabel = support
        )
    }

    private fun ratingFor(snapshot: RatingSnapshot, player: String, surface: Surface): Double {
        val playerKey = normalizeName(player)
        val rankings = snapshot.playersFor(surface)
        // For hard/clay/grass, a player with no results on that court starts at 1500 Elo.
        // Indoor/unknown surfaces use the overall Elo because there is no separate table.
        return rankings.firstOrNull { normalizeName(it.name) == playerKey }?.rating ?: 1500.0
    }

    private fun formFor(
        history: List<StoredMatch>,
        playerName: String,
        surface: Surface
    ): PlayerFormSnapshot {
        val key = normalizeName(playerName)
        val playerMatches = history.filter { stored ->
            val match = stored.record
            (normalizeName(match.playerA) == key || normalizeName(match.playerB) == key) &&
                isValidRecord(match)
        }
        val ordered = if (playerMatches.all { it.record.date.isNotBlank() }) {
            playerMatches.sortedWith(compareBy<StoredMatch> { it.record.date }.thenBy { it.id })
        } else {
            playerMatches.sortedBy { it.id }
        }
        val recentOverall = ordered.takeLast(10)
        val surfaceHistory = ordered.filter { it.record.surface == surface }
        val recentSurface = surfaceHistory.takeLast(10)

        fun won(stored: StoredMatch): Boolean =
            normalizeName(stored.record.winner) == key

        fun resultTrail(rows: List<StoredMatch>): String =
            if (rows.isEmpty()) "нет записей"
            else rows.asReversed().joinToString(" · ") { if (won(it)) "В" else "П" }

        return PlayerFormSnapshot(
            totalMatches = ordered.size,
            surfaceMatches = surfaceHistory.size,
            recentOverallWins = recentOverall.count(::won),
            recentOverallCount = recentOverall.size,
            recentOverallResults = resultTrail(recentOverall),
            recentSurfaceWins = recentSurface.count(::won),
            recentSurfaceCount = recentSurface.size,
            recentSurfaceResults = resultTrail(recentSurface)
        )
    }

    private fun isValidRecord(stored: StoredMatch): Boolean {
        val match = stored.record
        val a = normalizeName(match.playerA)
        val b = normalizeName(match.playerB)
        val winner = normalizeName(match.winner)
        return a.isNotEmpty() && b.isNotEmpty() && a != b && (winner == a || winner == b)
    }

    private fun evidenceWeight(form: PlayerFormSnapshot): Double =
        form.totalMatches + 1.5 * form.surfaceMatches

    private fun uncertaintyBound(
        probability: Double,
        evidenceA: Double,
        evidenceB: Double,
        direction: Int
    ): Double {
        val p = probability.coerceIn(MIN_PROBABILITY, MAX_PROBABILITY)
        // A conservative heuristic: fewer observations imply a wider plausible Elo range.
        val ratingSdA = 250.0 / sqrt(1.0 + evidenceA)
        val ratingSdB = 250.0 / sqrt(1.0 + evidenceB)
        val logOddsMargin = EIGHTY_PERCENT_Z * sqrt(ratingSdA * ratingSdA + ratingSdB * ratingSdB) *
            ln(10.0) / RATING_SCALE
        val logOdds = ln(p / (1.0 - p)) + direction * logOddsMargin
        return sigmoid(logOdds).coerceIn(0.001, 0.999)
    }

    private fun sigmoid(value: Double): Double =
        if (value >= 0.0) 1.0 / (1.0 + exp(-value))
        else {
            val e = exp(value)
            e / (1.0 + e)
        }

    private fun normalizeName(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
}
