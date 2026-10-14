# ATP historical archive: model benchmark

## Dataset used

The benchmark replay used 42,571 ATP main-tour singles results from the seasons 2010–2024 inclusive. The dates run from 2010-01-03 through 2024-12-18; the archive contains 690 distinct `tourney_date` values. All 42,571 source rows had a parseable tournament date, winner, and loser in this pull.

Source: [farhadGithub/tennis-atp-data](https://github.com/farhadGithub/tennis-atp-data), a mirror of the Jeff Sackmann / Tennis Abstract ATP results dataset. Its README identifies the source-data license as Creative Commons Attribution-NonCommercial-ShareAlike 4.0 (CC BY-NC-SA 4.0). Keep attribution and do not use or redistribute this third-party archive for commercial purposes without separate permission. The raw data is deliberately not committed into this application repository.

To download the seasons and create a compact CSV that the Android app can import:

```bash
python3 scripts/download_atp_archive.py --start 2010 --end 2024
```

The generated archive is `data/benchmark/atp_2010_2024.csv`. The downloader also retains each annual source CSV in `data/benchmark/raw/`. The output uses `date,player_a,player_b,winner,surface`, which is supported by the app importer.

## Evaluation design

Historical results were replayed chronologically. Player orientation was canonicalized by name so the winner-first source schema could not make all training labels positive. Elo, surface Elo, recent form, and experience features were computed only from results assigned to earlier tournament dates; results sharing a `tourney_date` were scored from the same pre-date state. Elo used K=32 and the app's 400-point probability scale.

The first 40%, 55%, 70%, and 85% cut points of the unique dates define three consecutive walk-forward validation intervals. Configurations are ranked by mean Log Loss across those three intervals, with mean Brier Score as tie-breaker. The latest 15% of dates is reserved as a final holdout and is not used to select the configuration.

The tested logistic configurations combined three feature profiles—overall Elo only, overall plus surface Elo, and all five features—with two optimizer presets:

| Preset | Iterations | Learning rate | L2 |
|---|---:|---:|---:|
| regularized | 80 | 0.10 | 0.10 |
| standard | 140 | 0.08 | 0.01 |

Lower Log Loss and Brier Score are better. Accuracy is included as a secondary descriptive metric, not as the model-selection target.

## Walk-forward validation results

The baseline Elo mean across the three validation windows was **Log Loss 0.62278** and **Brier Score 0.21668**.

| Logistic candidate | Mean Log Loss | Mean Brier Score |
|---|---:|---:|
| All 5 features / standard | 0.62451 | 0.21762 |
| Elo + surface Elo / standard | 0.62855 | 0.21915 |
| All 5 features / regularized | 0.63772 | 0.22318 |
| Elo only / standard | 0.64351 | 0.22582 |
| Elo + surface Elo / regularized | 0.64693 | 0.22738 |
| Elo only / regularized | 0.66101 | 0.23409 |

The best logistic candidate was the all-five-feature standard configuration, but its mean Log Loss was still worse than Elo's: 0.62451 vs 0.62278. Its mean Brier Score was also worse: 0.21762 vs 0.21668. Therefore the automatic selection rule correctly **keeps Elo for prediction** on this archive rather than activating a model that performed worse in validation.

## Final holdout (2023-02-02 to 2024-12-18)

The selected logistic configuration was fitted on the earlier history and evaluated on the untouched final period of 5,775 matches.

| Metric | Elo baseline | Logistic regression |
|---|---:|---:|
| Log Loss | **0.63391** | 0.63402 |
| Brier Score | **0.22187** | 0.22199 |
| Accuracy | 0.63429 | 0.63429 |

The final holdout supports the validation conclusion: the tested logistic model did not improve calibrated probabilities over this Elo baseline. This is not a reason to delete the model workflow. The app now keeps the candidate scores, automatically refuses to route predictions through a model that fails walk-forward validation, and will use logistic regression if a future imported history produces a candidate that beats Elo.

## Interpretation and limitations

- `tourney_date` is the tournament date recorded in the source, not each individual match's actual start date. Treating all results with the same date as one batch is conservative and prevents outcomes from that date entering features for other matches on that date.
- This is a replay of an archival ATP men's main-tour dataset, not a live-data test and not evidence of profitability for betting.
- The results don't include uncertainty intervals; small differences should not be overinterpreted.
- The benchmark numbers were calculated in a chronological reference replay matching the app's current feature definitions and candidate presets. After importing the generated CSV on Android, the app card displays the on-device evaluation from the exact local database.
