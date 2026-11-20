# Classifier narrowing (0.1.2 / 0.2.0) - evidence

| File | What |
|---|---|
| `cases-new-classifier.txt` | `cases.py` with the narrowed classifier: 48/48 as expected |
| `cases-old-classifier.txt` | the same 48 cases with the classifier before narrowing: 39/48 (5 safe changes blocked, 4 modality changes let through) |
| `manual-hot-example-and-b4.txt` | `manual_hot.py` on example-gradle's ui module (11/11 hot, on screen, same PID) and B4 through the plugin (17/17) |
| `manual-hot-three-modules.txt` | `manual_hot.py` on the three-module project: core 11/11, ui 11/11 (rerun from a fresh launch) |
| `lambda-stress.txt` | `lambda_stress.py`: composable lambdas added/removed/re-added in a live ComposableSingletons object, 20 reloads in one process, taps after each, no crash; the 5 "screen" misses are an existing lambda whose text changed (stale static call site, a recomposition issue, not memory safety) |
