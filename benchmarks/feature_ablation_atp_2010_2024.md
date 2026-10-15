ATP feature-ablation benchmark
================================

Reference replay for Tennis Predict AI v0.6. Lower Log Loss and Brier Score are better.

Dataset and protocol
--------------------

Input: data/benchmark/atp_2010_2024.csv
Valid dated matches: 42,564
Date span: 2010-01-03 through 2024-12-18
Distinct tournament dates: 690
Optimizer: standard, 140 iterations, learning rate 0.08, L2 0.01
All matches on the same tournament date share a pre-date feature snapshot.
H2H feature is (prior wins A minus prior wins B) divided by (prior meetings + 4), which shrinks small samples toward neutral.
Threshold: neither metric may worsen by more than 0.0001 on any fold, and at least one mean metric must improve by 0.0001.
The latest 15% of unique dates is reserved and is not used for candidate selection.

Validation windows
------------------

Start | End (exclusive) | Train | Test
--- | --- | ---: | ---:
2015-11-15 | 2018-05-07 | 17,823 | 7,120
2018-05-07 | 2021-03-01 | 24,943 | 6,319
2021-03-01 | 2023-02-02 | 31,262 | 5,530

Candidate profiles
------------------

Profile | Features | Mean Log Loss | Mean Brier | Fold Log Loss | Fold Brier | Elo gate
--- | ---: | ---: | ---: | --- | --- | ---
Elo only | 1 | 0.643518 | 0.225827 | 0.638160 / 0.647055 / 0.645338 | 0.223263 / 0.227574 / 0.226644 | no
Elo + surface Elo | 2 | 0.628557 | 0.219152 | 0.622830 / 0.632138 / 0.630704 | 0.216449 / 0.220937 / 0.220071 | no
Elo + recent form | 2 | 0.640431 | 0.224443 | 0.634545 / 0.644747 / 0.642000 | 0.221632 / 0.226568 / 0.225129 | no
Elo + surface Elo + recent form | 3 | 0.627163 | 0.218587 | 0.621126 / 0.631373 / 0.628989 | 0.215734 / 0.220677 / 0.219350 | no
Elo + surface Elo + head-to-head | 3 | 0.628125 | 0.218963 | 0.622300 / 0.631772 / 0.630302 | 0.216214 / 0.220782 / 0.219893 | no
Elo + surface Elo + recent form + head-to-head | 4 | 0.626755 | 0.218409 | 0.620630 / 0.631030 / 0.628606 | 0.215516 / 0.220532 / 0.219181 | no
Elo + surface Elo + experience | 4 | 0.625795 | 0.218184 | 0.613659 / 0.631646 / 0.632079 | 0.212414 / 0.220999 / 0.221140 | no
Elo + surface Elo + experience + head-to-head | 5 | 0.625378 | 0.218003 | 0.613172 / 0.631283 / 0.631679 | 0.212199 / 0.220847 / 0.220962 | no
All 5 features | 5 | 0.624533 | 0.217629 | 0.612436 / 0.630977 / 0.630185 | 0.211879 / 0.220720 / 0.220288 | no
All 5 features + head-to-head | 6 | 0.624139 | 0.217458 | 0.611977 / 0.630636 / 0.629804 | 0.211678 / 0.220577 / 0.220120 | no

Paired ablation tests
---------------------

Delta is candidate minus baseline; a negative value is better. The factor passes only if both metrics remain non-worse within tolerance on every fold and one mean metric improves meaningfully.

Factor | Baseline | Candidate | Mean Δ Log Loss | Mean Δ Brier | Per-fold Δ LL | Per-fold Δ Brier | Pass
--- | --- | --- | ---: | ---: | --- | --- | ---
Recent form | Elo only | Elo + recent form | -0.003087 | -0.001384 | -0.003616 / -0.002307 / -0.003339 | -0.001630 / -0.001006 / -0.001515 | yes
Recent form | Elo + surface Elo | Elo + surface Elo + recent form | -0.001395 | -0.000565 | -0.001704 / -0.000765 / -0.001715 | -0.000715 / -0.000261 / -0.000721 | yes
Recent form | Elo + surface Elo + head-to-head | Elo + surface Elo + recent form + head-to-head | -0.001369 | -0.000554 | -0.001671 / -0.000742 / -0.001696 | -0.000699 / -0.000250 / -0.000713 | yes
Recent form | Elo + surface Elo + experience | All 5 features | -0.001262 | -0.000555 | -0.001223 / -0.000668 / -0.001894 | -0.000535 / -0.000279 / -0.000852 | yes
Recent form | Elo + surface Elo + experience + head-to-head | All 5 features + head-to-head | -0.001239 | -0.000544 | -0.001195 / -0.000647 / -0.001875 | -0.000522 / -0.000269 / -0.000842 | yes
Head-to-head | Elo + surface Elo | Elo + surface Elo + head-to-head | -0.000432 | -0.000189 | -0.000530 / -0.000366 / -0.000402 | -0.000234 / -0.000155 / -0.000178 | yes
Head-to-head | Elo + surface Elo + recent form | Elo + surface Elo + recent form + head-to-head | -0.000407 | -0.000177 | -0.000496 / -0.000343 / -0.000383 | -0.000218 / -0.000145 / -0.000169 | yes
Head-to-head | Elo + surface Elo + experience | Elo + surface Elo + experience + head-to-head | -0.000417 | -0.000182 | -0.000488 / -0.000363 / -0.000400 | -0.000215 / -0.000152 / -0.000178 | yes
Head-to-head | All 5 features | All 5 features + head-to-head | -0.000394 | -0.000171 | -0.000459 / -0.000342 / -0.000381 | -0.000201 / -0.000142 / -0.000168 | yes

Interpretation
--------------

An incremental feature may help relative to a matched model without it while the full model still loses to plain Elo. The app enables a model for forecasting only if both metrics pass the Elo comparison on all three intervals and every included form/H2H feature passes its exact-context paired ablation.
If no candidate passes all gates, the forecast uses Elo only; form, surface results and H2H remain visible evidence, not unvalidated probability adjustments.

Source and licence
------------------

Jeff Sackmann / Tennis Abstract ATP match data, preserved by Aneeshers/tennis-sackmann-archive. Source data is CC BY-NC-SA 4.0: attribute the source and do not use or redistribute the downloaded archive commercially without separate permission.
