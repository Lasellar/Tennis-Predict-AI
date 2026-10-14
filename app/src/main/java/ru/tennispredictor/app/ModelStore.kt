package ru.tennispredictor.app

import android.content.Context

/**
 * Stores the most recent full-history model locally so later app stages can use it
 * without retraining. No match history or model data is uploaded.
 */
class ModelStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun save(model: LogisticRegressionModel) {
        preferences.edit()
            .putString(KEY_WEIGHTS, model.weights.joinToString(",") { it.toString() })
            .putInt(KEY_TRAINING_SAMPLES, model.trainingSamples)
            .putString(KEY_TRAINED_THROUGH, model.trainedThroughDate)
            .putInt(KEY_FEATURE_VERSION, model.featureVersion)
            .apply()
    }

    fun load(): LogisticRegressionModel? {
        val rawWeights = preferences.getString(KEY_WEIGHTS, null) ?: return null
        val weights = rawWeights.split(",").map { it.toDoubleOrNull() ?: return null }
        if (weights.size != LogisticRegressionModel.FEATURE_COUNT + 1 || weights.any { !it.isFinite() }) {
            return null
        }
        val version = preferences.getInt(KEY_FEATURE_VERSION, -1)
        if (version != LogisticRegressionModel.FEATURE_VERSION) return null

        return LogisticRegressionModel(
            weights = weights,
            trainingSamples = preferences.getInt(KEY_TRAINING_SAMPLES, 0),
            trainedThroughDate = preferences.getString(KEY_TRAINED_THROUGH, "").orEmpty(),
            featureVersion = version
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "tennis_predictor_logistic_model"
        const val KEY_WEIGHTS = "weights"
        const val KEY_TRAINING_SAMPLES = "training_samples"
        const val KEY_TRAINED_THROUGH = "trained_through_date"
        const val KEY_FEATURE_VERSION = "feature_version"
    }
}
