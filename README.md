# Tennis Predictor AI

Android MVP for estimating tennis match win probabilities from Elo ratings.

## Current features

- Native Android app built with Kotlin and Jetpack Compose.
- Manual entry of two players and their Elo ratings.
- Win-probability estimate using the standard Elo logistic formula.
- Elo training utility for ordered historical match records.
- Evaluation metrics: accuracy, log loss and Brier score.
- Unit tests for probability calculations, rating updates and empty datasets.
- GitHub Actions workflow builds a debug APK, runs unit tests and uploads the APK as an artifact.

## Build

Requirements: JDK 17 and Android SDK (API 35).

From the repository root, run:

```bash
gradle --no-daemon assembleDebug testDebugUnitTest
```

The APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

A successful CI run publishes an artifact named `tennis-predictor-debug-apk` in the workflow run.

## First launch

Enter each player's name and Elo rating (allowed range 500–3000), then tap **Рассчитать прогноз**. The displayed percentages are model estimates, not guarantees.

## Important limitations

This is an early baseline, not a production-grade predictive model. The first screen uses manually entered Elo ratings. The Elo training function is currently a domain utility and is not yet connected to CSV import or persistent storage in the UI. Surface-specific ratings, real data ingestion, chronological train/test splits, calibration and richer match features are planned next. No external data service or paid API is required for this MVP.

Predictions are uncertain and should not be treated as betting advice.
