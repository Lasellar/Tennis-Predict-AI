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

## Real-match forecasting (Stage 3)

The main screen now includes a real-match prediction workflow:

- Player search suggestions are built from names already present in the phone's local match database. Names can also be entered manually for players not found in the archive.
- The selected court surface is applied to the Elo baseline. The app shows each player's most recent results overall and on that surface; the selected logistic-regression model also uses recent-form features when temporal validation has shown that the model is useful.
- Every estimate includes both win probabilities, an approximate 80% probability range, recent-form context and the amount of historical evidence. The range is a heuristic based on rating uncertainty and match counts, not a statistically calibrated confidence interval.
- Forecasts are saved on-device with their probabilities, source, court surface, player form and optional target date. The user can record the actual winner afterwards; the app calculates per-forecast Log Loss and Brier Score and shows aggregate accuracy for settled forecasts.
- When a target match date is supplied, importing history automatically verifies a saved forecast only when the date, both players and surface match exactly. Forecasts without a date can be settled manually.

No live scores, injuries or external player feeds are requested; the estimate uses only the imported archive and the model trained on the device.

## Model-training and automatic selection

The app trains logistic regression locally and now selects models using a time-aware validation process:

- It compares three feature profiles: overall Elo, overall plus surface Elo, and all five features (overall Elo, surface Elo, recent form, total experience, and surface experience).
- Each profile is tested with two optimization presets, for six candidate configurations total.
- Three consecutive walk-forward intervals are used for model selection. Dates are split as complete date batches; no match outcome from a date is allowed to change another match's pre-match features for that same date.
- Candidate models are ranked by average validation Log Loss, with Brier Score as a tie-breaker. The latest 15% of unique dates are kept as an independent final holdout and are not used to select the candidate.
- The app only routes predictions through logistic regression when its average validation Log Loss improves on Elo by at least 0.0001. Otherwise it automatically continues to use Elo. This is intentional: a more complex model must demonstrate that it improves the historical probability estimates.
- A selected model stores its feature profile, coefficients, hyperparameters, training date, sample count, and selection status locally. After importing more matches, prediction falls back to Elo until the model is trained again on the new history.
- The main prediction screen uses the selected model's probability when it is recommended and its training history is current. Otherwise it shows the Elo probability and the reason the model was not applied.

## Building the ATP benchmark archive

A reproducible download utility creates a combined CSV that can be imported directly from the Android app:

    python3 scripts/download_atp_archive.py --start 2010 --end 2024

The command downloads yearly ATP main-tour singles CSVs into data/benchmark/raw/ and writes data/benchmark/atp_2010_2024.csv. The generated dataset is not committed to this repository.

The benchmark used 42,571 ATP matches from 2010–2024. On three earlier validation intervals, Elo averaged Log Loss 0.62278 and Brier Score 0.21668; the best of the six logistic candidates averaged 0.62451 and 0.21762. The final holdout (5,775 matches from 2023-02-02 through 2024-12-18) gave Elo Log Loss 0.63391 / Brier 0.22187 and logistic regression 0.63402 / 0.22199. The automatic selector therefore keeps Elo for this dataset. Full metrics, tested configurations, intervals, and source attribution are documented in benchmarks/atp_archive_2010_2024.md.

The third-party ATP archive is attributed to Jeff Sackmann / Tennis Abstract, using a public mirror maintained by farhadGithub. Its stated license is CC BY-NC-SA 4.0, which permits non-commercial use only unless additional permission is obtained. Do not bundle or redistribute the source archive for commercial use without checking the rights first.

## Current limitations

The importer reads CSV data locally and supports common ATP/WTA winner/loser archive schemas. It does not yet ingest live match feeds, player identifiers, tournament importance, match format, injury status or serve/return statistics. Elo ratings therefore remain a baseline feature for later prediction-model improvements.
