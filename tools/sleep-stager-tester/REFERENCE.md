# The reference hypnogram — what it is and how to make one

To check a stager objectively (not just "the hypnogram looks plausible"), you score its output against a
**reference hypnogram**: an independent, trusted, per-epoch sleep-stage labelling of *the same night* you
captured. The reference is the answer key; the stager's output is the prediction; `score.py` reports how
well they agree (Cohen's kappa, per-stage recall, confusion).

The reference is **never an input to the stager** and is **not bundled here** — you bring your own. That
is deliberate: a stager must be measured on *your* night against *your* labels, not against ours.

## What counts as a reference
Any independent source of per-epoch stage labels for the night, for example:

- A **commercial sleep-stage reference** — the staged hypnogram a commercial sleep platform produces for
  that night, exported for your own account. This is the practical option most people already have.
- **Polysomnography (PSG)** — the clinical gold standard, if you have a study for the night.

The only requirement is that it labels each part of the night as **deep / rem / light / awake** and
covers the same clock window as your capture. `score.py` only scores epochs the reference covers, so
partial coverage is fine.

## File format
The easiest format is **per-epoch** — one row per 30 s epoch, `unix` = the epoch's start time:

```jsonc
{"epochs": [
  {"unix": 1700000000, "stage": "light"},
  {"unix": 1700000030, "stage": "deep"},
  {"unix": 1700000060, "stage": "deep"},
  {"unix": 1700000090, "stage": "rem"}
]}
```

`score.py` also accepts segment forms if your source exports stage *intervals* instead of fixed epochs:

```jsonc
{"stages": [
  {"start": 1700000000, "end": 1700000900, "stage": "light"},
  {"start": 1700000900, "end": 1700001800, "stage": "deep"}
]}
```

Rules: times are **unix seconds**; `stage` is one of `deep`, `rem`, `light`, `awake` (`wake` is accepted
as `awake`). Epoch `unix` values should sit on the 30 s grid your reference uses; whatever grid you pick,
keep the capture you stage and this reference on the **same timeline** so epochs line up.

## How to build it
1. Pick a night you **also captured** raw strap data for (so the stager has the same night to work on).
2. Export that night's stage labels from your reference source.
3. Convert them to the format above — one `{"unix","stage"}` per 30 s epoch (or `{"start","end","stage"}`
   segments). Map the source's stage names onto `deep / rem / light / awake`.

A tiny converter is all this takes (read your export, emit the JSON). Keep your reference file local; only
the resulting aggregate scores (kappa / recall) are worth sharing.

## Then score
```bash
# v1.json / v2.json come from the runner (README.md step 2)
python3 score.py --reference reference.json --hypno v1=v1.json --hypno v2=v2.json
```

Example scorecard (one subject, multiple nights):

```
reference: 6877 labelled 30 s epochs spanning ~8 day(s)

=== v2 ===
  accuracy 67%   macro-recall 66%   Cohen's kappa 0.465
  per-stage recall: deep 79%  rem 67%  light 65%  awake 52%
  confusion (rows = reference, cols = predicted):
           deep    rem  light  awake
  deep      ...
  ...

=== v1  ->  v2 ===
  kappa 0.063 -> 0.465  (7.4x)
  deep recall 12% -> 79%  (6.8x)
  rem recall 19% -> 67%  (3.6x)
```

The headline is a **relative** comparison: both stagers see the same capture and are scored against the
same reference, so the difference is the staging logic alone. Run it on your own night to confirm the win
holds on your data — that, not our table, is the proof that matters.
