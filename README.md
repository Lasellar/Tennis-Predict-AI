# Tennis Predictor AI

Android MVP for estimating tennis match win probabilities from Elo ratings.

## Historical-data stage

The Android app now supports:

- Importing match results from a CSV file selected from the phone.
- Reading a compact CSV schema: date, player_a, player_b, winner, surface.
- Reading common ATP/WTA archive columns: tourney_date, winner_name, loser_name, surface.
- Persisting match history in a local SQLite database on the device.
- Skipping duplicate rows when the same history is imported again.
- Calculating overall Elo rankings and separate rankings for hard court, clay and grass.
- Sorting dated matches chronologically before recalculating Elo.
- Loading the selected historical ratings into the prediction form.
- Showing match counts, wins and losses alongside the top 10 players in each ranking.

No remote service or paid API is required. Imported history stays on the device.

## CSV examples

Compact format:

date,player_a,player_b,winner,surface  
2024-01-05,Player A,Player B,Player A,hard  
2024-01-06,Player A,Player C,Player C,clay  
2024-01-07,Player B,Player C,Player B,grass

The player names in the winner column must match one of the two player names. Supported surface labels include hard, clay, grass and carpet/indoor. Missing or unknown surfaces are kept in the overall rating but are not mixed into the hard, clay or grass ratings.

Common ATP/WTA archive exports using winner_name, loser_name and tourney_date are also supported. Dates in YYYYMMDD and ISO YYYY-MM-DD formats are normalized.

## Rating rules

- Elo starts at 1500 for each player in each independently calculated rating table.
- The K-factor is 32.
- The overall rating uses all valid imported results.
- Hard, clay and grass ratings use only matches recorded on that surface.
- If every match in a rating set has a date, results are processed in chronological order. If dates are missing or mixed, import order is retained rather than inventing a chronology.
- When a player has no history on the selected surface, the prediction form uses a baseline Elo of 1500 if you press “Подставить Elo из истории”.

These scores are baseline estimates, not guarantees of match outcomes or betting advice.

## Build

Requirements: JDK 17 and Android SDK (API 35).

From the repository root, run:

    gradle --no-daemon assembleDebug testDebugUnitTest

The APK is created at app/build/outputs/apk/debug/app-debug.apk.

A successful GitHub Actions run publishes an artifact named tennis-predictor-debug-apk.

## Model-training stage

The app includes an on-device logistic-regression workflow and compares it with an Elo baseline:

- It builds pre-match features from overall Elo difference, surface Elo difference, recent form, total experience and experience on the selected surface.
- Player order is canonicalized by name before training. This prevents winner-first ATP/WTA archive columns from making every label look like a win for player A.
- Rows are sorted by date and split by whole dates: earlier dates are used for training, and later dates are held out for evaluation.
- All matches on the same date receive features before that date's results update Elo, form or experience. Undated or invalid records are excluded from this temporal evaluation and counted in the report.
- Log Loss, Brier Score and accuracy are shown for Elo and logistic regression on the same later-date holdout. Lower Log Loss and Brier Score indicate better probabilistic predictions.
- After evaluation, a final logistic model is refitted on all eligible dated matches and its coefficients are stored locally on the device for the next development stage.

The model currently uses match outcomes and rating history only. It does not yet use rankings, tournament categories, surface-specific serve/return statistics, fatigue or injuries. A better score on one historical holdout does not guarantee better future results.

## Current limitations

The importer reads CSV data locally and supports common ATP/WTA winner/loser archive schemas. It does not yet ingest live match feeds, player identifiers, tournament importance, match format, injury status or serve/return statistics. Elo ratings therefore remain a baseline feature for later prediction-model improvements.
