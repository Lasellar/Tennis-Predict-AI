ATP feature-ablation benchmark
================================

Reference replay for Tennis Predict AI v0.6. Lower Log Loss and Brier Score are better.

Dataset and protocol
--------------------

Input: data/benchmark/atp_2010_2024.csv
Valid dated matches: 42,564
Date span: 2010-01-03 through 2024-12-18
Distinct tournament dates: 690
Optimizer presets: regularized (80 iterations, lr=0.1, L2=0.1); standard (140 iterations, lr=0.08, L2=0.01)
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

Profile / preset | Features | Mean Log Loss | Mean Brier | Fold Log Loss | Fold Brier | Elo gate
--- | ---: | ---: | ---: | --- | --- | ---
Elo only / regularized | 1 | 0.661013 | 0.234096 | 0.657461 / 0.663218 / 0.662360 | 0.232357 / 0.235192 / 0.234740 | no
Elo + surface Elo / regularized | 2 | 0.646931 | 0.227382 | 0.642810 / 0.649231 / 0.648752 | 0.225398 / 0.228524 / 0.228222 | no
Elo + recent form / regularized | 2 | 0.657845 | 0.232570 | 0.653809 / 0.660581 / 0.659147 | 0.230598 / 0.233928 / 0.233186 | no
Elo + surface Elo + recent form / regularized | 3 | 0.644850 | 0.226417 | 0.640365 / 0.647614 / 0.646572 | 0.224261 / 0.227788 / 0.227202 | no
Elo + surface Elo + head-to-head / regularized | 3 | 0.646494 | 0.227176 | 0.642274 / 0.648833 / 0.648374 | 0.225146 / 0.228338 / 0.228043 | no
Elo + surface Elo + recent form + head-to-head / regularized | 4 | 0.644435 | 0.226222 | 0.639857 / 0.647237 / 0.646212 | 0.224022 / 0.227612 / 0.227032 | no
Elo + surface Elo + experience / regularized | 4 | 0.639439 | 0.223968 | 0.630932 / 0.642876 / 0.644509 | 0.219841 / 0.225668 / 0.226395 | no
Elo + surface Elo + experience + head-to-head / regularized | 5 | 0.639034 | 0.223778 | 0.630442 / 0.642507 / 0.644154 | 0.219610 / 0.225496 / 0.226227 | no
All 5 features / regularized | 5 | 0.637720 | 0.223179 | 0.629038 / 0.641605 / 0.642517 | 0.218975 / 0.225096 / 0.225465 | no
All 5 features + head-to-head / regularized | 6 | 0.637335 | 0.222999 | 0.628571 / 0.641255 / 0.642179 | 0.218757 / 0.224933 / 0.225305 | no
Elo only / standard | 1 | 0.643518 | 0.225827 | 0.638160 / 0.647055 / 0.645338 | 0.223263 / 0.227574 / 0.226644 | no
Elo + surface Elo / standard | 2 | 0.628557 | 0.219152 | 0.622830 / 0.632138 / 0.630704 | 0.216449 / 0.220937 / 0.220071 | no
Elo + recent form / standard | 2 | 0.640431 | 0.224443 | 0.634545 / 0.644747 / 0.642000 | 0.221632 / 0.226568 / 0.225129 | no
Elo + surface Elo + recent form / standard | 3 | 0.627163 | 0.218587 | 0.621126 / 0.631373 / 0.628989 | 0.215734 / 0.220677 / 0.219350 | no
Elo + surface Elo + head-to-head / standard | 3 | 0.628125 | 0.218963 | 0.622300 / 0.631772 / 0.630302 | 0.216214 / 0.220782 / 0.219893 | no
Elo + surface Elo + recent form + head-to-head / standard | 4 | 0.626755 | 0.218409 | 0.620630 / 0.631030 / 0.628606 | 0.215516 / 0.220532 / 0.219181 | no
Elo + surface Elo + experience / standard | 4 | 0.625795 | 0.218184 | 0.613659 / 0.631646 / 0.632079 | 0.212414 / 0.220999 / 0.221140 | no
Elo + surface Elo + experience + head-to-head / standard | 5 | 0.625378 | 0.218003 | 0.613172 / 0.631283 / 0.631679 | 0.212199 / 0.220847 / 0.220962 | no
All 5 features / standard | 5 | 0.624533 | 0.217629 | 0.612436 / 0.630977 / 0.630185 | 0.211879 / 0.220720 / 0.220288 | no
All 5 features + head-to-head / standard | 6 | 0.624139 | 0.217458 | 0.611977 / 0.630636 / 0.629804 | 0.211678 / 0.220577 / 0.220120 | no

Paired ablation tests
---------------------

Delta is candidate minus baseline; a negative value is better. The factor passes only if both metrics remain non-worse within tolerance on every fold and one mean metric improves meaningfully.

Factor | Preset | Baseline | Candidate | Mean Δ Log Loss | Mean Δ Brier | Per-fold Δ LL | Per-fold Δ Brier | Pass
--- | --- | --- | --- | ---: | ---: | --- | --- | ---
Recent form | regularized | Elo only / regularized | Elo + recent form / regularized | -0.003167 | -0.001526 | -0.003652 / -0.002637 / -0.003213 | -0.001759 / -0.001264 / -0.001555 | yes
Recent form | regularized | Elo + surface Elo / regularized | Elo + surface Elo + recent form / regularized | -0.002081 | -0.000965 | -0.002445 / -0.001617 / -0.002180 | -0.001138 / -0.000736 / -0.001020 | yes
Recent form | regularized | Elo + surface Elo + head-to-head / regularized | Elo + surface Elo + recent form + head-to-head / regularized | -0.002058 | -0.000953 | -0.002417 / -0.001596 / -0.002162 | -0.001123 / -0.000725 / -0.001011 | yes
Recent form | regularized | Elo + surface Elo + experience / regularized | All 5 features / regularized | -0.001719 | -0.000789 | -0.001895 / -0.001271 / -0.001992 | -0.000866 / -0.000572 / -0.000929 | yes
Recent form | regularized | Elo + surface Elo + experience + head-to-head / regularized | All 5 features + head-to-head / regularized | -0.001699 | -0.000779 | -0.001871 / -0.001252 / -0.001976 | -0.000854 / -0.000563 / -0.000921 | yes
Head-to-head | regularized | Elo + surface Elo / regularized | Elo + surface Elo + head-to-head / regularized | -0.000437 | -0.000206 | -0.000536 / -0.000398 / -0.000378 | -0.000253 / -0.000186 / -0.000179 | yes
Head-to-head | regularized | Elo + surface Elo + recent form / regularized | Elo + surface Elo + recent form + head-to-head / regularized | -0.000415 | -0.000195 | -0.000508 / -0.000377 / -0.000360 | -0.000238 / -0.000175 / -0.000170 | yes
Head-to-head | regularized | Elo + surface Elo + experience / regularized | Elo + surface Elo + experience + head-to-head / regularized | -0.000405 | -0.000190 | -0.000491 / -0.000369 / -0.000355 | -0.000231 / -0.000172 / -0.000168 | yes
Head-to-head | regularized | All 5 features / regularized | All 5 features + head-to-head / regularized | -0.000385 | -0.000180 | -0.000466 / -0.000351 / -0.000339 | -0.000218 / -0.000162 / -0.000160 | yes
Recent form | standard | Elo only / standard | Elo + recent form / standard | -0.003087 | -0.001384 | -0.003616 / -0.002307 / -0.003339 | -0.001630 / -0.001006 / -0.001515 | yes
Recent form | standard | Elo + surface Elo / standard | Elo + surface Elo + recent form / standard | -0.001395 | -0.000565 | -0.001704 / -0.000765 / -0.001715 | -0.000715 / -0.000261 / -0.000721 | yes
Recent form | standard | Elo + surface Elo + head-to-head / standard | Elo + surface Elo + recent form + head-to-head / standard | -0.001369 | -0.000554 | -0.001671 / -0.000742 / -0.001696 | -0.000699 / -0.000250 / -0.000713 | yes
Recent form | standard | Elo + surface Elo + experience / standard | All 5 features / standard | -0.001262 | -0.000555 | -0.001223 / -0.000668 / -0.001894 | -0.000535 / -0.000279 / -0.000852 | yes
Recent form | standard | Elo + surface Elo + experience + head-to-head / standard | All 5 features + head-to-head / standard | -0.001239 | -0.000544 | -0.001195 / -0.000647 / -0.001875 | -0.000522 / -0.000269 / -0.000842 | yes
Head-to-head | standard | Elo + surface Elo / standard | Elo + surface Elo + head-to-head / standard | -0.000432 | -0.000189 | -0.000530 / -0.000366 / -0.000402 | -0.000234 / -0.000155 / -0.000178 | yes
Head-to-head | standard | Elo + surface Elo + recent form / standard | Elo + surface Elo + recent form + head-to-head / standard | -0.000407 | -0.000177 | -0.000496 / -0.000343 / -0.000383 | -0.000218 / -0.000145 / -0.000169 | yes
Head-to-head | standard | Elo + surface Elo + experience / standard | Elo + surface Elo + experience + head-to-head / standard | -0.000417 | -0.000182 | -0.000488 / -0.000363 / -0.000400 | -0.000215 / -0.000152 / -0.000178 | yes
Head-to-head | standard | All 5 features / standard | All 5 features + head-to-head / standard | -0.000394 | -0.000171 | -0.000459 / -0.000342 / -0.000381 | -0.000201 / -0.000142 / -0.000168 | yes

Interpretation
--------------

An incremental feature may help relative to a matched model without it while the full model still loses to plain Elo. The app enables a model for forecasting only if both metrics pass the Elo comparison on all three intervals and every included form/H2H feature passes its exact-context paired ablation.
If no candidate passes all gates, the forecast uses Elo only; form, surface results and H2H remain visible evidence, not unvalidated probability adjustments.

Source and licence
------------------

Jeff Sackmann / Tennis Abstract ATP match data, preserved by Aneeshers/tennis-sackmann-archive. Source data is CC BY-NC-SA 4.0: attribute the source and do not use or redistribute the downloaded archive commercially without separate permission.
