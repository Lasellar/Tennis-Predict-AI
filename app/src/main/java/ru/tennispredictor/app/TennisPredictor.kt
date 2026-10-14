package ru.tennispredictor.app

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round

enum class Surface(val displayName: String) {
    HARD("Хард"),
    CLAY("Грунт"),
    GRASS("Трава"),
    INDOOR("Ковёр / indoor"),
    OTHER("Другое / неизвестно")
}

data class MatchRecord(
    val playerA: String,
    val playerB: String,
    val winner: String,
    val surface: Surface = Surface.HARD,
    val eloA: Double = 1500.0,
    val eloB: Double = 1500.0,
    val date: String = ""
)

data class Prediction(
    val playerA: String,
    val playerB: String,
    val probabilityA: Double,
    val probabilityB: Double,
    val ratingDifference: Double
)

data class EvaluationMetrics(
    val accuracy: Double,
    val logLoss: Double,
    val brierScore: Double,
    val matches: Int
)

object TennisPredictor {
    private const val SCALE = 400.0
    private const val DEFAULT_ELO = 1500.0
    private const val K_FACTOR = 32.0

    fun probability(ratingA: Double, ratingB: Double): Double =
        1.0 / (1.0 + exp((ratingB - ratingA) / SCALE * ln(10.0)))

    fun predict(playerA: String, playerB: String, eloA: Double, eloB: Double): Prediction {
        val p = probability(eloA, eloB).coerceIn(0.001, 0.999)
        return Prediction(playerA, playerB, p, 1.0 - p, eloA - eloB)
    }

    /**
     * Processes matches in the order supplied. Callers that have dates should sort
     * the records chronologically before training so future results never leak backward.
     */
    fun trainElo(matches: List<MatchRecord>, initialRating: Double = DEFAULT_ELO): Map<String, Double> {
        val ratings = mutableMapOf<String, Double>()
        for (match in matches) {
            val a = match.playerA.trim()
            val b = match.playerB.trim()
            val winner = match.winner.trim()
            if (a.isEmpty() || b.isEmpty() || a == b || (winner != a && winner != b)) continue

            val ratingA = ratings.getOrPut(a) { initialRating }
            val ratingB = ratings.getOrPut(b) { initialRating }
            val expectedA = probability(ratingA, ratingB)
            val actualA = if (winner == a) 1.0 else 0.0
            val change = K_FACTOR * (actualA - expectedA)
            ratings[a] = ratingA + change
            ratings[b] = ratingB - change
        }
        return ratings.toMap()
    }

    fun evaluate(records: List<MatchRecord>, ratings: Map<String, Double>): EvaluationMetrics {
        val valid = records.filter {
            it.playerA.isNotBlank() && it.playerB.isNotBlank() &&
                (it.winner == it.playerA || it.winner == it.playerB)
        }
        if (valid.isEmpty()) return EvaluationMetrics(0.0, 0.0, 0.0, 0)

        var correct = 0
        var loss = 0.0
        var brier = 0.0
        valid.forEach { match ->
            val probabilityA = probability(
                ratings[match.playerA] ?: match.eloA,
                ratings[match.playerB] ?: match.eloB
            ).coerceIn(0.001, 0.999)
            val actualA = if (match.winner == match.playerA) 1.0 else 0.0
            if ((probabilityA >= 0.5) == (actualA == 1.0)) correct++
            loss -= actualA * ln(probabilityA) + (1.0 - actualA) * ln(1.0 - probabilityA)
            brier += (probabilityA - actualA) * (probabilityA - actualA)
        }
        return EvaluationMetrics(
            accuracy = correct.toDouble() / valid.size,
            logLoss = loss / valid.size,
            brierScore = brier / valid.size,
            matches = valid.size
        )
    }

    fun formatPercent(value: Double): String = "${round(value * 1000.0) / 10.0}%"
}
