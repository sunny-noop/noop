# Sleep-stager runner — run v1 and v2 on a capture

A tiny, self-contained Swift CLI to stage a `capture.json` with **either** sleep stager and emit a
hypnogram, so the two can be compared on real data:

- **v1** — the shipped `SleepStager` (unchanged).
- **v2** — `SleepStagerV2`, the cardiorespiratory recipe added in this change. It reuses v1's session
  detection (the in-bed spans) and replaces only the per-epoch staging.

The runner is **opt-in**: it is not part of the app's `Packages/` workspace, so it has no effect on the
app build or CI. It depends on the `StrandAnalytics` package and uses its public API
(`SleepStager.detectSleep` / `SleepStagerV2.detectSleep`) — no copied or symlinked sources. Builds on
macOS (where the app and its tests build).

## 1. Get a capture
The runner reads **either** input, auto-detected — so the easiest path needs no conversion:

- **A raw-capture export from the noop app** (JSONL, one frame per line). Export it on-device, then point
  the runner straight at the file. *(This is how anyone can test on their own strap's data.)*
- **A `capture.json`** — a JSON array of `{"hex","char"}` records, e.g. from the Linux capture tool:
  ```bash
  python3 tools/linux-capture/whoop_sync.py export --db captures/whoop.db --address <MAC> --out capture.json
  ```

Either way the frames are raw strap frames. Family is auto-detected per frame from the source
characteristic UUID (`fd4b…` → whoop5, `6108…` → whoop4); pass `whoop4`/`whoop5` to force it.

## 2. Build and run both stagers
```bash
cd tools/sleep-stager-tester/runner
swift build -c release
BIN="$(find .build -name sleep-stager-cli -type f | head -1)"

# INPUT can be the noop app export (.jsonl) OR a capture.json — auto-detected.
# v1 — shipped SleepStager:
"$BIN" /path/to/noop-raw-capture-*.jsonl auto --stager v1 > v1.json
# v2 — SleepStagerV2 recipe:
"$BIN" /path/to/noop-raw-capture-*.jsonl auto --stager v2 > v2.json
```

Each run writes the hypnogram JSON to stdout and a one-line per-session summary to stderr, e.g.:

```
sleep 23:00→07:00  eff 94%  deep 95m rem 130m light 225m wake 30m
```

so the v1-vs-v2 difference (e.g. how much deep/REM each assigns) is visible at a glance, per night, from
the same capture.

## Output format
```jsonc
{"sessions": [
  {"start": …, "end": …, "efficiency": …,
   "deepMin": …, "remMin": …, "lightMin": …, "wakeMin": …,
   "stages": [{"start": …, "end": …, "stage": "deep|rem|light|wake"}, …]}
]}
```

## 3. Score against a reference (objective, not just "looks plausible")
The runner only produces hypnograms; to get a *number* you score them against a **reference hypnogram** —
your own per-epoch stage labels for the same night, from an externally-calibrated sleep-stage reference (or PSG). The
reference is bring-your-own and never an input to the stager; see **[REFERENCE.md](REFERENCE.md)** for what
it is and how to build one. Then:

```bash
# v1.json / v2.json from step 2; reference.json built per REFERENCE.md
python3 score.py --reference reference.json --hypno v1=v1.json --hypno v2=v2.json
```

`score.py` is standard-library only and bundles no data. It aligns everything to 30 s epochs over the
reference's span and prints accuracy, macro-recall, **Cohen's kappa**, per-stage recall, a confusion
matrix, and stage minutes for each candidate — plus the `v1 -> v2` change. Run it on your own night to
confirm the win holds on your data; that is the proof, not the table below.

## Note on the recipe (n=1)
This was validated by **capturing raw WHOOP 5 strap data and replaying the same recording offline through
both stagers** (shipped v6.2.0 and v2), then scoring each per-epoch against an externally-calibrated sleep-stage reference (one subject, 7 nights). v2 raised agreement substantially over v1 (which collapses toward
*light*). That result is **n=1** — the recipe's weights and window sizes need multi-subject validation
before they can be trusted as general. This runner is the tool to gather that evidence: stage your own
captures with both and compare.

This is a **big step forward** but **still under active development** — expect improvements over the
coming weeks (more subjects, tuning).
