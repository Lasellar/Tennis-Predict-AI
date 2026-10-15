# Tennis Predictor AI

Android MVP for estimating tennis match win probabilities from historical results.

## Version 0.6

- Added a date-safe, smoothed head-to-head (H2H) feature: only prior meetings count, and meetings from the same tournament date cannot leak into feature snapshots.
- Expanded model selection to 10 feature profiles across two optimization presets, with paired temporal ablations for recent form and H2H.
- Every candidate is scored on three independent chronological validation intervals. A model may replace Elo only when both Log Loss and Brier Score are non-worse against Elo on every interval (tolerance 0.0001), one mean metric improves by at least 0.0001, and each included form/H2H factor passes its exact-context ablation.
- If no candidate clears every gate, the real-match forecast uses the pure overall-Elo baseline. Surface, recent form and H2H remain visible as evidence rather than unvalidated hand-tuned probability adjustments.
- Increased the model feature version to 3 so coefficients from the previous five-feature model are invalidated and must be retrained.
- Added a reproducible Python/NumPy benchmark and GitHub Actions workflow. The workflow uploads only the metrics report, not the third-party match archive.

The initial reference replay of ATP results from 2010–2024 showed incremental gains from form and H2H compared with matched logistic-regression profiles, but the tested logistic candidates still failed the stricter Elo comparison on at least one validation interval. Consequently, version 0.6 deliberately keeps Elo as the active prediction baseline until a candidate demonstrates a robust improvement.

## Version 0.5.2

- Added side-by-side player form summaries with overall Elo, recent results, selected-surface results, match counts and last dated match.
- Added head-to-head history: overall meeting record, record on the planned court surface and up to five recent meetings from the imported history.
- Added a transparent explanation panel that lists the features active in the selected profile: overall Elo, surface Elo, recent form and experience, when applicable.
- Reframed uncertainty as an easy-to-read reliability status and kept the reasons and Elo-sensitivity range visible.
- Head-to-head meetings are contextual information only; they do not directly change the calculated probability.
- Added unit coverage for head-to-head summaries and exclusion of matches on/after the planned match date. Undated meetings are disclosed in the UI.

## Version 0.5.0

- Redesigned the Android interface with a pinned header and four clear sections: Forecast, Data, Rankings and Model.
- Added a calmer tennis-inspired green palette, clearer typography hierarchy, softer card shapes and more consistent spacing.
- Kept the everyday real-match forecast on the main screen; the manual Elo calculator is now secondary and collapsible.
- Made forecast probabilities easier to compare with a side-by-side summary and a visual probability bar.
- Kept match import, local storage, surface-specific Elo rankings, model training and saved forecast evaluation available in their own sections.

## Version 0.4.5

- Player-name suggestions keep the keyboard open while typing; the suggestion list can be scrolled and selected without reopening the text field.
- CSV import accepts multiple files in one picker session and imports them together, skipping duplicates across the selected files.
- The current-season ATP and WTA CSV archive builder now targets results through the build date (UTC), including a licensed current-season supplement where available.

## Stage 3 — real-match forecasting

The real-match forecast card is integrated into the 0.4.5 application without replacing the existing manual Elo form, scrollable suggestions, multi-file CSV importer, or ATP/WTA archive workflow.

- Choose two players, the planned match date, and court surface.
- Calculate a probability from overall Elo, surface Elo and recent form; use the locally trained logistic model only when its validation status and date coverage are safe.
- Results on or after the planned match date are excluded from forecast feature history to reduce future-result leakage.
- Show each player's recent-form evidence, uncertainty rating and an Elo-sensitivity range. The range is a heuristic, not a calibrated statistical confidence interval.
- Save forecasts on-device, enter outcomes manually, and automatically reconcile a saved forecast after CSV import only when date, player pair and surface match.
- Review hit rate, Log Loss and Brier Score for resolved forecasts.

The SQLite database migrates from schema version 1 to 3 without deleting imported match history. It also detects and migrates forecasts and settled outcomes from the alternate 0.4.0 schema.

## Historical-data stage

The Android app now supports:

- Importing one or multiple match-result CSV files in one selection.
- Showing scrollable player-name suggestions without closing the keyboard while typing.
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

## Model-training and automatic selection

The app trains logistic regression locally and now selects models using a time-aware validation process:

- It compares three feature profiles: overall Elo, overall plus surface Elo, and all five features (overall Elo, surface Elo, recent form, total experience, and surface experience).
- Each profile is tested with two optimization presets, for six candidate configurations total.
- Three consecutive walk-forward intervals are used for model selection. Dates are split as complete date batches; no match outcome from a date is allowed to change another match's pre-match features for that same date.
- Candidate models are ranked by average validation Log Loss, with Brier Score as a tie-breaker. The latest 15% of unique dates are kept as an independent final holdout and are not used to select the candidate.
- The app only routes predictions through logistic regression when its average validation Log Loss improves on Elo by at least 0.0001. Otherwise it automatically continues to use Elo. This is intentional: a more complex model must demonstrate that it improves the historical probability estimates.
- A selected model stores its feature profile, coefficients, hyperparameters, training date, sample count, and selection status locally. After importing more matches, prediction falls back to Elo until the model is trained again on the new history.
- The main prediction screen uses the selected model's probability when it is recommended and its training history is current. Otherwise it shows the Elo probability and the reason the model was not applied.

## Building current ATP and WTA archives

A reproducible downloader creates separate, app-importable CSV files for both tours:

    python3 scripts/download_tour_archives.py --start 2010 --end 2026

It downloads season files into `data/benchmark/raw/` and writes `data/benchmark/atp_2010_2026.csv` and `data/benchmark/wta_2010_2026.csv`. The archive cutoff is the build date in UTC, inclusive. It uses Jeff Sackmann / Tennis Abstract match data preserved by `Aneeshers/tennis-sackmann-archive` (CC BY-NC-SA 4.0), then supplements the current season with top-tour matches from Valuebetennis' open dataset (CC BY 4.0) from the last date present in the primary snapshot onward. Repeated results are deduplicated by date, winner and loser. CSV files are generated by the workflow and are not committed to the repository.

For an ATP-only file, the backward-compatible command remains available:

    python3 scripts/download_atp_archive.py --start 2010 --end 2026

The GitHub Actions workflow packages both CSVs, a ZIP containing both archives, checksums, and source/licence attribution into a downloadable build artifact. Since the primary dataset is licensed CC BY-NC-SA 4.0, the combined archive is for non-commercial use and must retain the same licence and attribution. Obtain permission from the rights holders before commercial use.

The benchmark used 42,571 ATP matches from 2010–2024. On three earlier validation intervals, Elo averaged Log Loss 0.62278 and Brier Score 0.21668; the best of the six logistic candidates averaged 0.62451 and 0.21762. The final holdout (5,775 matches from 2023-02-02 through 2024-12-18) gave Elo Log Loss 0.63391 / Brier 0.22187 and logistic regression 0.63402 / 0.22199. The automatic selector therefore keeps Elo for this dataset. Full metrics, tested configurations, intervals, and source attribution are documented in benchmarks/atp_archive_2010_2024.md.

The third-party ATP archive is attributed to Jeff Sackmann / Tennis Abstract, using a public mirror maintained by farhadGithub. Its stated license is CC BY-NC-SA 4.0, which permits non-commercial use only unless additional permission is obtained. Do not bundle or redistribute the source archive for commercial use without checking the rights first.

## Current limitations

The app does not yet ingest live match feeds, stable tour player identifiers, tournament importance, match format, injury status or serve/return statistics. Real-match forecasts are based on the quality and recency of the CSV history available on the device; they do not guarantee outcomes or imply a betting edge.
