package ru.tennispredictor.app

import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Logistic regression coefficients trained on historical pre-match features.
 * weights[0] is the intercept; the remaining coefficients follow FEATURE_NAMES.
 */
data class LogisticRegressionModel(
    val weights: List<Double>,
    val trainingSamples: Int,
    val trainedThroughDate: String,
    val featureVersion: Int = FEATURE_VERSION
) {
    fun predictProbability(features: DoubleArray): Double {
        require(features.size == FEATURE_COUNT) { "Ожидалось ${FEATURE_COUNT} признаков модели." }
        require(weights.size == FEATURE_COUNT + 1) { "Некорректное число коэффициентов модели." }
        var logit = weights[0]
        for (index in features.indices) logit += weights[index + 1] * features[index]
        return stableSigmoid(logit)
    }

    companion object {
        const val FEATURE_COUNT = 5
        const val FEATURE_VERSION = 1
    }
}

data class ModelComparisonReport(
    val eligibleMatches: Int,
    val excludedUndatedMatches: Int,
    val excludedInvalidMatches: Int,
    val trainMatches: Int,
    val testMatches: Int,
    val trainStartDate: String,
    val trainEndDate: String,
    val testStartDate: String,
    val testEndDate: String,
    val eloMetrics: EvaluationMetrics,
    val logisticMetrics: EvaluationMetrics,
    /** Model fitted to the complete eligible history for future predictions. */
    val model: LogisticRegressionModel
)

internal data class PreparedModelSample(
    val date: LocalDate,
    val features: DoubleArray,
    val label: Int,
    /** Difference between the pre-match overall Elo ratings, A minus B. */
    val eloDifference: Double
)

internal data class ModelFeaturePreparation(
    val samples: List<PreparedModelSample>,
    val excludedUndatedMatches: Int,
    val excludedInvalidMatches: Int
)

/**
 * Chronological model training and a leakage-safe temporal holdout evaluation.
 *
 * Matches on the same date are treated as a single batch: all their features are
 * calculated before any results from that date update Elo, form, or experience.
 * That prevents one match's result from leaking into another match on the same day.
 */
object ModelTrainer {
    const val MINIMUM_DATED_MATCHES = 20
    private const val HOLDOUT_FRACTION = 0.20
    private const val K_FACTOR = 32.0
    private const val RECENT_MATCH_WINDOW = 10
    private const val MODEL_ITERATIONS = 250
    private const val LEARNING_RATE = 0.15
    private const val L2_STRENGTH = 0.01

    val featureNames: List<String> = listOf(
        "overall_elo_difference",
        "surface_elo_difference",
        "recent_form_difference",
        "experience_difference",
        "surface_experience_difference"
    )

    fun trainAndEvaluate(history: List<StoredMatch>): ModelComparisonReport {
        val preparation = prepareSamples(history)
        val samples = preparation.samples
        if (samples.size < MINIMUM_DATED_MATCHES) {
            throw IllegalStateException(
                "Для обучения и временной проверки нужно минимум $MINIMUM_DATED_MATCHES корректных матчей с датами. " +
                    "Сейчас доступно: ${samples.size}."
            )
        }

        val dateGroups = samples.groupingBy { it.date }.eachCount().toSortedMap().entries.toList()
        if (dateGroups.size < 2) {
            throw IllegalStateException(
                "Для разделения по времени нужны матчи минимум за два разных дня. Проверь столбец date / tourney_date."
            )
        }

        val targetTrainCount = samples.size * (1.0 - HOLDOUT_FRACTION)
        var cumulative = 0
        var bestSplitIndex = -1
        var bestDistance = Double.POSITIVE_INFINITY
        for (splitIndex in 1 until dateGroups.size) {
            cumulative += dateGroups[splitIndex - 1].value
            val distance = abs(cumulative - targetTrainCount)
            if (distance < bestDistance) {
                bestDistance = distance
                bestSplitIndex = splitIndex
            }
        }
        if (bestSplitIndex <= 0 || bestSplitIndex >= dateGroups.size) {
            throw IllegalStateException("Не удалось сформировать временное разделение исторических матчей.")
        }

        val testStart = dateGroups[bestSplitIndex].key
        val train = samples.filter { it.date < testStart }
        val test = samples.filter { it.date >= testStart }
        if (train.size < 10 || test.size < 5) {
            throw IllegalStateException(
                "Недостаточно матчей до или после временной границы. Нужно не менее 10 матчей для обучения " +
                    "и 5 более поздних матчей для проверки; получено ${train.size} и ${test.size}."
            )
        }
        if (train.map { it.label }.distinct().size < 2) {
            throw IllegalStateException(
                "В обучающей части обнаружен только один вариант исхода. Добавь историю с победами разных игроков, затем повтори обучение."
            )
        }

        // This model has seen only rows strictly before the holdout start date.
        val evaluationModel = fit(train, train.maxOf { it.date }.toString())
        val eloProbabilities = test.map { eloProbability(it.eloDifference) }
        val logisticProbabilities = test.map { evaluationModel.predictProbability(it.features) }

        // After an honest evaluation, refit on all historical rows for future use.
        val fullHistoryModel = fit(samples, samples.maxOf { it.date }.toString())

        return ModelComparisonReport(
            eligibleMatches = samples.size,
            excludedUndatedMatches = preparation.excludedUndatedMatches,
            excludedInvalidMatches = preparation.excludedInvalidMatches,
            trainMatches = train.size,
            testMatches = test.size,
            trainStartDate = train.minOf { it.date }.toString(),
            trainEndDate = train.maxOf { it.date }.toString(),
            testStartDate = test.minOf { it.date }.toString(),
            testEndDate = test.maxOf { it.date }.toString(),
            eloMetrics = calculateMetrics(test, eloProbabilities),
            logisticMetrics = calculateMetrics(test, logisticProbabilities),
            model = fullHistoryModel
        )
    }

    /**
     * Exposed internally for unit tests. Features for a row only use results from
     * strictly earlier dates. Rows without a parseable ISO date are excluded.
     */
    internal fun prepareSamples(history: List<StoredMatch>): ModelFeaturePreparation {
        val dated = mutableListOf<DatedMatch>()
        var excludedUndated = 0
        var excludedInvalid = 0

        history.forEach { stored ->
            val date = runCatching { LocalDate.parse(stored.record.date.trim()) }.getOrNull()
            if (date == null) {
                excludedUndated++
                return@forEach
            }

            val record = stored.record
            val rawA = record.playerA.trim().replace(Regex("\\s+"), " ")
            val rawB = record.playerB.trim().replace(Regex("\\s+"), " ")
            val rawWinner = record.winner.trim().replace(Regex("\\s+"), " ")
            val keyA = normalizeName(rawA)
            val keyB = normalizeName(rawB)
            val winnerKey = normalizeName(rawWinner)
            if (
                keyA.isBlank() || keyB.isBlank() || keyA == keyB ||
                (winnerKey != keyA && winnerKey != keyB)
            ) {
                excludedInvalid++
                return@forEach
            }

            // Canonical orientation is essential: common winner/loser archive CSVs
            // always put the winner first. A stable name-based order prevents that
            // source layout from making every training target equal to 1.
            val aComesFirst = keyA <= keyB
            dated += DatedMatch(
                id = stored.id,
                date = date,
                playerA = if (aComesFirst) rawA else rawB,
                playerB = if (aComesFirst) rawB else rawA,
                keyA = if (aComesFirst) keyA else keyB,
                keyB = if (aComesFirst) keyB else keyA,
                winnerKey = winnerKey,
                surface = record.surface
            )
        }

        val states = mutableMapOf<String, PlayerState>()
        val samples = mutableListOf<PreparedModelSample>()
        val sortedMatches = dated.sortedWith(compareBy<DatedMatch> { it.date }.thenBy { it.id })
        val matchesByDate = sortedMatches.groupBy { it.date }.toSortedMap()

        for ((date, dayMatches) in matchesByDate) {
            // Snapshot features before applying any outcome from this date.
            dayMatches.forEach { match ->
                val stateA = states.getOrPut(match.keyA) { PlayerState() }
                val stateB = states.getOrPut(match.keyB) { PlayerState() }
                val overallDifference = stateA.overallElo - stateB.overallElo
                val trackedSurface = trackedSurface(match.surface)
                val surfaceDifference = if (trackedSurface == null) {
                    0.0
                } else {
                    stateA.surfaceElos.getOrDefault(trackedSurface, 1500.0) -
                        stateB.surfaceElos.getOrDefault(trackedSurface, 1500.0)
                }
                val formDifference = recentWinRate(stateA) - recentWinRate(stateB)
                val experienceDifference =
                    (ln(1.0 + stateA.overallMatches) - ln(1.0 + stateB.overallMatches)) / 4.0
                val surfaceExperienceDifference = if (trackedSurface == null) {
                    0.0
                } else {
                    val countA = stateA.surfaceMatches.getOrDefault(trackedSurface, 0)
                    val countB = stateB.surfaceMatches.getOrDefault(trackedSurface, 0)
                    (ln(1.0 + countA) - ln(1.0 + countB)) / 4.0
                }
                val label = if (match.winnerKey == match.keyA) 1 else 0
                samples += PreparedModelSample(
                    date = date,
                    features = doubleArrayOf(
                        overallDifference / 400.0,
                        surfaceDifference / 400.0,
                        formDifference,
                        experienceDifference,
                        surfaceExperienceDifference
                    ),
                    label = label,
                    eloDifference = overallDifference
                )
            }

            // Accumulate updates using frozen ratings from the beginning of the day.
            // This avoids imposing an invented order on matches with the same date.
            val overallChanges = mutableMapOf<String, Double>()
            val surfaceChanges = mutableMapOf<Pair<String, Surface>, Double>()
            dayMatches.forEach { match ->
                val stateA = states.getValue(match.keyA)
                val stateB = states.getValue(match.keyB)
                val actualA = if (match.winnerKey == match.keyA) 1.0 else 0.0
                val expectedA = TennisPredictor.probability(stateA.overallElo, stateB.overallElo)
                val change = K_FACTOR * (actualA - expectedA)
                overallChanges[match.keyA] = overallChanges.getOrDefault(match.keyA, 0.0) + change
                overallChanges[match.keyB] = overallChanges.getOrDefault(match.keyB, 0.0) - change

                trackedSurface(match.surface)?.let { surface ->
                    val surfaceA = stateA.surfaceElos.getOrDefault(surface, 1500.0)
                    val surfaceB = stateB.surfaceElos.getOrDefault(surface, 1500.0)
                    val expectedSurfaceA = TennisPredictor.probability(surfaceA, surfaceB)
                    val surfaceChange = K_FACTOR * (actualA - expectedSurfaceA)
                    val keyA = match.keyA to surface
                    val keyB = match.keyB to surface
                    surfaceChanges[keyA] = surfaceChanges.getOrDefault(keyA, 0.0) + surfaceChange
                    surfaceChanges[keyB] = surfaceChanges.getOrDefault(keyB, 0.0) - surfaceChange
                }
            }

            overallChanges.forEach { (key, change) -> states.getValue(key).overallElo += change }
            surfaceChanges.forEach { (key, change) ->
                val (playerKey, surface) = key
                val state = states.getValue(playerKey)
                state.surfaceElos[surface] = state.surfaceElos.getOrDefault(surface, 1500.0) + change
            }

            // Counts and form are also advanced only after every feature for the date
            // has been created.
            dayMatches.forEach { match ->
                val stateA = states.getValue(match.keyA)
                val stateB = states.getValue(match.keyB)
                stateA.overallMatches++
                stateB.overallMatches++
                trackedSurface(match.surface)?.let { surface ->
                    stateA.surfaceMatches[surface] = stateA.surfaceMatches.getOrDefault(surface, 0) + 1
                    stateB.surfaceMatches[surface] = stateB.surfaceMatches.getOrDefault(surface, 0) + 1
                }
                val aWon = match.winnerKey == match.keyA
                addRecentResult(stateA, if (aWon) 1 else 0)
                addRecentResult(stateB, if (aWon) 0 else 1)
            }
        }

        return ModelFeaturePreparation(
            samples = samples,
            excludedUndatedMatches = excludedUndated,
            excludedInvalidMatches = excludedInvalid
        )
    }

    private fun fit(samples: List<PreparedModelSample>, trainedThroughDate: String): LogisticRegressionModel {
        require(samples.isNotEmpty())
        val weights = DoubleArray(LogisticRegressionModel.FEATURE_COUNT + 1)
        repeat(MODEL_ITERATIONS) {
            val gradients = DoubleArray(weights.size)
            samples.forEach { sample ->
                var logit = weights[0]
                for (index in sample.features.indices) {
                    logit += weights[index + 1] * sample.features[index]
                }
                val error = stableSigmoid(logit) - sample.label.toDouble()
                gradients[0] += error
                for (index in sample.features.indices) {
                    gradients[index + 1] += error * sample.features[index]
                }
            }

            val inverseCount = 1.0 / samples.size
            for (index in weights.indices) {
                var gradient = gradients[index] * inverseCount
                if (index > 0) gradient += L2_STRENGTH * weights[index]
                weights[index] -= LEARNING_RATE * gradient
            }
        }
        return LogisticRegressionModel(
            weights = weights.toList(),
            trainingSamples = samples.size,
            trainedThroughDate = trainedThroughDate
        )
    }

    private fun calculateMetrics(
        samples: List<PreparedModelSample>,
        probabilities: List<Double>
    ): EvaluationMetrics {
        if (samples.isEmpty() || samples.size != probabilities.size) {
            return EvaluationMetrics(0.0, 0.0, 0.0, 0)
        }
        var correct = 0
        var logLoss = 0.0
        var brier = 0.0
        samples.indices.forEach { index ->
            val probability = probabilities[index].coerceIn(1e-6, 1.0 - 1e-6)
            val actual = samples[index].label.toDouble()
            if ((probability >= 0.5) == (actual == 1.0)) correct++
            logLoss -= actual * ln(probability) + (1.0 - actual) * ln(1.0 - probability)
            brier += (probability - actual) * (probability - actual)
        }
        return EvaluationMetrics(
            accuracy = correct.toDouble() / samples.size,
            logLoss = logLoss / samples.size,
            brierScore = brier / samples.size,
            matches = samples.size
        )
    }

    private fun eloProbability(difference: Double): Double =
        TennisPredictor.probability(1500.0 + difference, 1500.0)

    private fun trackedSurface(surface: Surface): Surface? = when (surface) {
        Surface.HARD, Surface.CLAY, Surface.GRASS -> surface
        else -> null
    }

    private fun recentWinRate(state: PlayerState): Double =
        if (state.recentResults.isEmpty()) 0.5
        else state.recentResults.sum().toDouble() / state.recentResults.size

    private fun addRecentResult(state: PlayerState, result: Int) {
        state.recentResults += result
        if (state.recentResults.size > RECENT_MATCH_WINDOW) state.recentResults.removeAt(0)
    }

    private fun normalizeName(value: String): String = value.trim().lowercase(Locale.ROOT)

    private data class DatedMatch(
        val id: Long,
        val date: LocalDate,
        val playerA: String,
        val playerB: String,
        val keyA: String,
        val keyB: String,
        val winnerKey: String,
        val surface: Surface
    )

    private class PlayerState {
        var overallElo: Double = 1500.0
        val surfaceElos = mutableMapOf<Surface, Double>()
        var overallMatches: Int = 0
        val surfaceMatches = mutableMapOf<Surface, Int>()
        val recentResults = mutableListOf<Int>()
    }
}

private fun stableSigmoid(value: Double): Double {
    return if (value >= 0.0) {
        1.0 / (1.0 + exp(-value.coerceAtMost(35.0)))
    } else {
        val exponential = exp(value.coerceAtLeast(-35.0))
        exponential / (1.0 + exponential)
    }
}
