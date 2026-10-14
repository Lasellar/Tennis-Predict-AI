package ru.tennispredictor.app

import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Trained coefficients and the selected feature/optimization configuration.
 * Feature values are always supplied in the canonical five-feature order.
 */
data class LogisticRegressionModel(
    val weights: List<Double>,
    val trainingSamples: Int,
    val trainedThroughDate: String,
    val featureVersion: Int = FEATURE_VERSION,
    val featureIndices: List<Int> = listOf(0, 1, 2, 3, 4),
    val iterations: Int = 0,
    val learningRate: Double = 0.0,
    val l2Strength: Double = 0.0,
    val profileName: String = "full",
    val recommendedForPrediction: Boolean = true
) {
    fun predictProbability(features: DoubleArray): Double {
        require(features.size == FEATURE_COUNT) { "Ожидалось ${FEATURE_COUNT} исходных признаков модели." }
        require(featureIndices.isNotEmpty() && featureIndices.all { it in 0 until FEATURE_COUNT }) {
            "Некорректная конфигурация признаков модели."
        }
        require(weights.size == featureIndices.size + 1) { "Число коэффициентов не совпадает с конфигурацией признаков." }
        var logit = weights[0]
        featureIndices.forEachIndexed { index, featureIndex ->
            logit += weights[index + 1] * features[featureIndex]
        }
        return stableSigmoid(logit)
    }

    companion object {
        const val FEATURE_COUNT = 5
        const val FEATURE_VERSION = 2
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
    /** Independent, latest-date holdout metrics. */
    val eloMetrics: EvaluationMetrics,
    val logisticMetrics: EvaluationMetrics,
    /** The model fitted to all dated history for future predictions. */
    val model: LogisticRegressionModel,
    /** Candidate configurations ranked only on earlier walk-forward folds. */
    val candidateResults: List<ModelCandidateResult>,
    val selectedModelName: String,
    val validationEloMeanLogLoss: Double,
    val validationEloMeanBrier: Double,
    val validationModelMeanLogLoss: Double,
    val validationModelMeanBrier: Double,
    val validationFolds: List<TemporalValidationFold>,
    val logisticSelectedForPrediction: Boolean
)

data class TemporalValidationFold(
    val startDate: String,
    val endDate: String,
    val trainMatches: Int,
    val testMatches: Int,
    val eloMetrics: EvaluationMetrics,
    val modelMetrics: EvaluationMetrics
)

data class ModelCandidateResult(
    val name: String,
    val profileName: String,
    val featureIndices: List<Int>,
    val iterations: Int,
    val learningRate: Double,
    val l2Strength: Double,
    val folds: List<TemporalValidationFold>,
    val meanLogLoss: Double,
    val meanBrierScore: Double
)

internal data class PreparedModelSample(
    val matchId: Long,
    val date: LocalDate,
    val features: DoubleArray,
    val label: Int,
    /** Difference between pre-match overall Elo ratings, canonical A minus B. */
    val eloDifference: Double
)

internal data class ModelFeaturePreparation(
    val samples: List<PreparedModelSample>,
    val excludedUndatedMatches: Int,
    val excludedInvalidMatches: Int
)

/**
 * Walk-forward model selection with a separate final chronological holdout.
 *
 * 1. Match features are always frozen before outcomes from that date are applied.
 * 2. Six feature/hyperparameter configurations are ranked across three successive
 *    validation intervals. The last 15% of unique dates is never used to select.
 * 3. The selected configuration is compared with Elo on that untouched holdout.
 * 4. Its coefficients are then refitted on the complete eligible history.
 *
 * Model selection uses validation Log Loss (Brier Score breaks ties); the final
 * holdout remains diagnostic and is not used to cherry-pick a configuration.
 */
object ModelTrainer {
    const val MINIMUM_DATED_MATCHES = 20
    const val MINIMUM_DISTINCT_DATES = 20
    private const val K_FACTOR = 32.0
    private const val RECENT_MATCH_WINDOW = 10

    val featureNames: List<String> = listOf(
        "overall_elo_difference",
        "surface_elo_difference",
        "recent_form_difference",
        "experience_difference",
        "surface_experience_difference"
    )

    private data class FeatureProfile(val name: String, val indices: List<Int>)
    private data class TrainingPreset(
        val name: String,
        val iterations: Int,
        val learningRate: Double,
        val l2Strength: Double
    )
    private data class CandidateSpec(
        val profile: FeatureProfile,
        val preset: TrainingPreset
    ) {
        val displayName: String
            get() = "${profile.name} / ${preset.name}"
    }

    private val featureProfiles = listOf(
        FeatureProfile("Elo only", listOf(0)),
        FeatureProfile("Elo + surface Elo", listOf(0, 1)),
        FeatureProfile("All 5 features", listOf(0, 1, 2, 3, 4))
    )

    private val trainingPresets = listOf(
        TrainingPreset("regularized", iterations = 80, learningRate = 0.10, l2Strength = 0.10),
        TrainingPreset("standard", iterations = 140, learningRate = 0.08, l2Strength = 0.01)
    )

    fun trainAndEvaluate(history: List<StoredMatch>): ModelComparisonReport {
        val preparation = prepareSamples(history)
        val samples = preparation.samples
        if (samples.size < MINIMUM_DATED_MATCHES) {
            throw IllegalStateException(
                "Для обучения нужно минимум ${MINIMUM_DATED_MATCHES} корректных датированных матчей. " +
                    "Сейчас доступно: ${samples.size}."
            )
        }

        val dates = samples.map { it.date }.distinct().sorted()
        if (dates.size < MINIMUM_DISTINCT_DATES) {
            throw IllegalStateException(
                "Для временной проверки нужно минимум ${MINIMUM_DISTINCT_DATES} разных игровых дат. " +
                    "Сейчас доступно: ${dates.size}."
            )
        }

        val cut40 = (dates.size * 0.40).toInt()
        val cut55 = (dates.size * 0.55).toInt()
        val cut70 = (dates.size * 0.70).toInt()
        val cut85 = (dates.size * 0.85).toInt()
        val indexBounds = listOf(cut40, cut55, cut70, cut85)
        if (indexBounds.any { it <= 0 || it >= dates.size } ||
            indexBounds.zipWithNext().any { (a, b) -> b <= a }) {
            throw IllegalStateException("Недостаточно независимых дат, чтобы построить последовательные временные интервалы.")
        }

        val foldRanges = listOf(
            dates[cut40] to dates[cut55],
            dates[cut55] to dates[cut70],
            dates[cut70] to dates[cut85]
        )
        val foldData = foldRanges.map { (startDate, endDate) ->
            val train = samples.filter { it.date < startDate }
            val test = samples.filter { it.date >= startDate && it.date < endDate }
            if (train.size < 10 || test.size < 5) {
                throw IllegalStateException(
                    "В одном из временных интервалов слишком мало матчей: обучение ${train.size}, " +
                        "проверка ${test.size}. Импортируй более длинную историю."
                )
            }
            if (train.map { it.label }.distinct().size < 2) {
                throw IllegalStateException("В обучающем интервале встречается только один вариант исхода.")
            }
            Triple(startDate, train, test)
        }

        val candidates = mutableListOf<ModelCandidateResult>()
        for (profile in featureProfiles) {
            for (preset in trainingPresets) {
                val spec = CandidateSpec(profile, preset)
                val foldResults = foldData.map { (startDate, train, test) ->
                    val model = fit(
                        train = train,
                        trainedThroughDate = train.maxOf { it.date }.toString(),
                        spec = spec
                    )
                    val endDate = test.maxOf { it.date }.toString()
                    TemporalValidationFold(
                        startDate = startDate.toString(),
                        endDate = endDate,
                        trainMatches = train.size,
                        testMatches = test.size,
                        eloMetrics = calculateMetrics(test, test.map { eloProbability(it.eloDifference) }),
                        modelMetrics = calculateMetrics(test, test.map { model.predictProbability(it.features) })
                    )
                }
                candidates += ModelCandidateResult(
                    name = spec.displayName,
                    profileName = profile.name,
                    featureIndices = profile.indices,
                    iterations = preset.iterations,
                    learningRate = preset.learningRate,
                    l2Strength = preset.l2Strength,
                    folds = foldResults,
                    meanLogLoss = foldResults.map { it.modelMetrics.logLoss }.average(),
                    meanBrierScore = foldResults.map { it.modelMetrics.brierScore }.average()
                )
            }
        }

        val rankedCandidates = candidates.sortedWith(
            compareBy<ModelCandidateResult> { it.meanLogLoss }
                .thenBy { it.meanBrierScore }
                .thenBy { it.iterations }
        )
        val best = rankedCandidates.first()
        val validationEloMeanLogLoss = foldData.indices.map { foldIndex ->
            val test = foldData[foldIndex].third
            calculateMetrics(test, test.map { eloProbability(it.eloDifference) }).logLoss
        }.average()
        val validationEloMeanBrier = foldData.indices.map { foldIndex ->
            val test = foldData[foldIndex].third
            calculateMetrics(test, test.map { eloProbability(it.eloDifference) }).brierScore
        }.average()

        // Require a measurable validation gain before the learned model replaces Elo.
        // The 1e-4 margin avoids switching based on tiny floating-point differences.
        val useLogistic = best.meanLogLoss < validationEloMeanLogLoss - 1e-4
        val selectedSpec = CandidateSpec(
            FeatureProfile(best.profileName, best.featureIndices),
            TrainingPreset("selected", best.iterations, best.learningRate, best.l2Strength)
        )

        val finalHoldoutStart = dates[cut85]
        val development = samples.filter { it.date < finalHoldoutStart }
        val finalHoldout = samples.filter { it.date >= finalHoldoutStart }
        if (development.size < 10 || finalHoldout.size < 5) {
            throw IllegalStateException("Недостаточно матчей для независимой финальной проверки.")
        }
        val holdoutModel = fit(
            train = development,
            trainedThroughDate = development.maxOf { it.date }.toString(),
            spec = selectedSpec
        )
        val eloHoldoutMetrics = calculateMetrics(
            finalHoldout,
            finalHoldout.map { eloProbability(it.eloDifference) }
        )
        val logisticHoldoutMetrics = calculateMetrics(
            finalHoldout,
            finalHoldout.map { holdoutModel.predictProbability(it.features) }
        )

        // Refit selected coefficients on every eligible historic row for future use.
        val fullHistoryModel = fit(
            train = samples,
            trainedThroughDate = samples.maxOf { it.date }.toString(),
            spec = selectedSpec
        ).copy(recommendedForPrediction = useLogistic)

        return ModelComparisonReport(
            eligibleMatches = samples.size,
            excludedUndatedMatches = preparation.excludedUndatedMatches,
            excludedInvalidMatches = preparation.excludedInvalidMatches,
            trainMatches = development.size,
            testMatches = finalHoldout.size,
            trainStartDate = development.minOf { it.date }.toString(),
            trainEndDate = development.maxOf { it.date }.toString(),
            testStartDate = finalHoldout.minOf { it.date }.toString(),
            testEndDate = finalHoldout.maxOf { it.date }.toString(),
            eloMetrics = eloHoldoutMetrics,
            logisticMetrics = logisticHoldoutMetrics,
            model = fullHistoryModel,
            candidateResults = rankedCandidates,
            selectedModelName = if (useLogistic) best.name else "Elo baseline (no CV improvement)",
            validationEloMeanLogLoss = validationEloMeanLogLoss,
            validationEloMeanBrier = validationEloMeanBrier,
            validationModelMeanLogLoss = best.meanLogLoss,
            validationModelMeanBrier = best.meanBrierScore,
            validationFolds = best.folds,
            logisticSelectedForPrediction = useLogistic
        )
    }

    /**
     * Produces a prediction for the entered A/B orientation using the current
     * historical state. Returns null when the model was not selected by CV, when
     * history changed since training, or when the input cannot be prepared safely.
     */
    fun predictFutureProbability(
        history: List<StoredMatch>,
        playerA: String,
        playerB: String,
        surface: Surface,
        eloA: Double,
        eloB: Double,
        model: LogisticRegressionModel
    ): Double? {
        if (!model.recommendedForPrediction || playerA.isBlank() || playerB.isBlank()) return null
        if (normalizeName(playerA) == normalizeName(playerB)) return null

        val latestDate = history.mapNotNull { parseDate(it.record.date) }.maxOrNull()
        val futureDate = (latestDate ?: LocalDate.of(1970, 1, 1)).plusDays(1)
        val futureId = Long.MAX_VALUE
        val futureRecord = MatchRecord(
            playerA = playerA.trim(),
            playerB = playerB.trim(),
            winner = playerA.trim(),
            surface = surface,
            date = futureDate.toString()
        )
        val preparation = prepareSamples(history + StoredMatch(futureId, futureRecord))
        val futureSample = preparation.samples.firstOrNull { it.matchId == futureId } ?: return null
        val historicSamples = preparation.samples.filter { it.matchId != futureId }
        val historyThroughDate = historicSamples.maxOfOrNull { it.date }?.toString().orEmpty()

        // Refuse a stale model, including new rows added on the same final date.
        if (historicSamples.size != model.trainingSamples || historyThroughDate != model.trainedThroughDate) {
            return null
        }

        val canonicalFeatures = futureSample.features.copyOf()
        val userAIsCanonicalA = normalizeName(playerA) <= normalizeName(playerB)
        val orientedFeatures = if (userAIsCanonicalA) canonicalFeatures else {
            DoubleArray(canonicalFeatures.size) { -canonicalFeatures[it] }
        }
        // Preserve user-supplied/current Elo ratings as the model's overall-Elo input.
        orientedFeatures[0] = (eloA - eloB) / 400.0
        return model.predictProbability(orientedFeatures).coerceIn(0.001, 0.999)
    }

    /** Feature snapshots are calculated strictly before the outcome on that date. */
    internal fun prepareSamples(history: List<StoredMatch>): ModelFeaturePreparation {
        val dated = mutableListOf<DatedMatch>()
        var excludedUndated = 0
        var excludedInvalid = 0

        history.forEach { stored ->
            val date = parseDate(stored.record.date)
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
            if (keyA.isBlank() || keyB.isBlank() || keyA == keyB ||
                (winnerKey != keyA && winnerKey != keyB)
            ) {
                excludedInvalid++
                return@forEach
            }

            // Winner-first ATP/WTA archives are normalized to a stable name order.
            val aComesFirst = keyA <= keyB
            dated += DatedMatch(
                id = stored.id,
                date = date,
                keyA = if (aComesFirst) keyA else keyB,
                keyB = if (aComesFirst) keyB else keyA,
                winnerKey = winnerKey,
                surface = record.surface
            )
        }

        val states = mutableMapOf<String, PlayerState>()
        val samples = mutableListOf<PreparedModelSample>()
        val matchesByDate = dated.sortedWith(compareBy<DatedMatch> { it.date }.thenBy { it.id })
            .groupBy { it.date }.toSortedMap()

        for ((date, dayMatches) in matchesByDate) {
            // Freeze every match's features before updating any result from this date.
            dayMatches.forEach { match ->
                val stateA = states.getOrPut(match.keyA) { PlayerState() }
                val stateB = states.getOrPut(match.keyB) { PlayerState() }
                val overallDifference = stateA.overallElo - stateB.overallElo
                val trackedSurface = trackedSurface(match.surface)
                val surfaceDifference = if (trackedSurface == null) 0.0 else
                    stateA.surfaceElos.getOrDefault(trackedSurface, 1500.0) -
                        stateB.surfaceElos.getOrDefault(trackedSurface, 1500.0)
                val formDifference = recentWinRate(stateA) - recentWinRate(stateB)
                val experienceDifference =
                    (ln(1.0 + stateA.overallMatches) - ln(1.0 + stateB.overallMatches)) / 4.0
                val surfaceExperienceDifference = if (trackedSurface == null) 0.0 else {
                    val countA = stateA.surfaceMatches.getOrDefault(trackedSurface, 0)
                    val countB = stateB.surfaceMatches.getOrDefault(trackedSurface, 0)
                    (ln(1.0 + countA) - ln(1.0 + countB)) / 4.0
                }
                samples += PreparedModelSample(
                    matchId = match.id,
                    date = date,
                    features = doubleArrayOf(
                        overallDifference / 400.0,
                        surfaceDifference / 400.0,
                        formDifference,
                        experienceDifference,
                        surfaceExperienceDifference
                    ),
                    label = if (match.winnerKey == match.keyA) 1 else 0,
                    eloDifference = overallDifference
                )
            }

            // Aggregate Elo changes from ratings frozen at the beginning of this date.
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

            // Advance experience/form only after this date's features have been frozen.
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

        return ModelFeaturePreparation(samples, excludedUndated, excludedInvalid)
    }

    private fun fit(
        train: List<PreparedModelSample>,
        trainedThroughDate: String,
        spec: CandidateSpec
    ): LogisticRegressionModel {
        require(train.isNotEmpty())
        require(train.map { it.label }.distinct().size == 2) {
            "Для обучения нужны победы обоих вариантов целевого исхода."
        }
        val weights = DoubleArray(spec.profile.indices.size + 1)
        repeat(spec.preset.iterations) {
            val gradients = DoubleArray(weights.size)
            train.forEach { sample ->
                var logit = weights[0]
                spec.profile.indices.forEachIndexed { index, featureIndex ->
                    logit += weights[index + 1] * sample.features[featureIndex]
                }
                val error = stableSigmoid(logit) - sample.label.toDouble()
                gradients[0] += error
                spec.profile.indices.forEachIndexed { index, featureIndex ->
                    gradients[index + 1] += error * sample.features[featureIndex]
                }
            }
            val inverseCount = 1.0 / train.size
            for (index in weights.indices) {
                var gradient = gradients[index] * inverseCount
                if (index > 0) gradient += spec.preset.l2Strength * weights[index]
                weights[index] -= spec.preset.learningRate * gradient
            }
        }
        return LogisticRegressionModel(
            weights = weights.toList(),
            trainingSamples = train.size,
            trainedThroughDate = trainedThroughDate,
            featureIndices = spec.profile.indices,
            iterations = spec.preset.iterations,
            learningRate = spec.preset.learningRate,
            l2Strength = spec.preset.l2Strength,
            profileName = spec.profile.name
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

    private fun parseDate(value: String): LocalDate? =
        runCatching { LocalDate.parse(value.trim()) }.getOrNull()

    private fun normalizeName(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private data class DatedMatch(
        val id: Long,
        val date: LocalDate,
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

private fun stableSigmoid(value: Double): Double =
    if (value >= 0.0) {
        1.0 / (1.0 + exp(-value.coerceAtMost(35.0)))
    } else {
        val exponential = exp(value.coerceAtLeast(-35.0))
        exponential / (1.0 + exponential)
    }
