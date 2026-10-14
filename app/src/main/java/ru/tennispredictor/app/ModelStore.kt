package ru.tennispredictor.app

import android.content.Context

/**
 * Stores the selected model locally. History and coefficients never leave the device.
 * Feature-version checks deliberately invalidate models produced by older implementations.
 */
class ModelStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun save(model: LogisticRegressionModel) {
        preferences.edit()
            .putString(KEY_WEIGHTS, model.weights.joinToString(",") { it.toString() })
            .putString(KEY_FEATURE_INDICES, model.featureIndices.joinToString(","))
            .putInt(KEY_TRAINING_SAMPLES, model.trainingSamples)
            .putString(KEY_TRAINED_THROUGH, model.trainedThroughDate)
            .putInt(KEY_FEATURE_VERSION, model.featureVersion)
            .putInt(KEY_ITERATIONS, model.iterations)
            .putString(KEY_LEARNING_RATE, model.learningRate.toString())
            .putString(KEY_L2_STRENGTH, model.l2Strength.toString())
            .putString(KEY_PROFILE_NAME, model.profileName)
            .putBoolean(KEY_RECOMMENDED, model.recommendedForPrediction)
            .apply()
    }

    fun load(): LogisticRegressionModel? {
        val rawWeights = preferences.getString(KEY_WEIGHTS, null) ?: return null
        val weights = rawWeights.split(",").map { it.toDoubleOrNull() ?: return null }
        val rawIndices = preferences.getString(KEY_FEATURE_INDICES, null) ?: return null
        val featureIndices = rawIndices.split(",").map { it.toIntOrNull() ?: return null }
        if (featureIndices.isEmpty() ||
            featureIndices.distinct().size != featureIndices.size ||
            featureIndices.any { it !in 0 until LogisticRegressionModel.FEATURE_COUNT } ||
            weights.size != featureIndices.size + 1 ||
            weights.any { !it.isFinite() }
        ) return null

        val version = preferences.getInt(KEY_FEATURE_VERSION, -1)
        if (version != LogisticRegressionModel.FEATURE_VERSION) return null
        val learningRate = preferences.getString(KEY_LEARNING_RATE, null)?.toDoubleOrNull() ?: return null
        val l2Strength = preferences.getString(KEY_L2_STRENGTH, null)?.toDoubleOrNull() ?: return null
        if (!learningRate.isFinite() || learningRate <= 0.0 ||
            !l2Strength.isFinite() || l2Strength < 0.0
        ) return null

        return LogisticRegressionModel(
            weights = weights,
            trainingSamples = preferences.getInt(KEY_TRAINING_SAMPLES, 0),
            trainedThroughDate = preferences.getString(KEY_TRAINED_THROUGH, "").orEmpty(),
            featureVersion = version,
            featureIndices = featureIndices,
            iterations = preferences.getInt(KEY_ITERATIONS, 0),
            learningRate = learningRate,
            l2Strength = l2Strength,
            profileName = preferences.getString(KEY_PROFILE_NAME, "unknown").orEmpty(),
            recommendedForPrediction = preferences.getBoolean(KEY_RECOMMENDED, false)
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "tennis_predictor_logistic_model"
        const val KEY_WEIGHTS = "weights"
        const val KEY_FEATURE_INDICES = "feature_indices"
        const val KEY_TRAINING_SAMPLES = "training_samples"
        const val KEY_TRAINED_THROUGH = "trained_through_date"
        const val KEY_FEATURE_VERSION = "feature_version"
        const val KEY_ITERATIONS = "iterations"
        const val KEY_LEARNING_RATE = "learning_rate"
        const val KEY_L2_STRENGTH = "l2_strength"
        const val KEY_PROFILE_NAME = "profile_name"
        const val KEY_RECOMMENDED = "recommended_for_prediction"
    }
}
