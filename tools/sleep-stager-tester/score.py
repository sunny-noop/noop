#!/usr/bin/env python3
"""Score a sleep-stager hypnogram against a reference hypnogram, per 30 s epoch.

Bring-your-own-data: this depends on NO bundled dataset. Give it (1) a REFERENCE hypnogram — your own
per-epoch stage labels from an externally-calibrated sleep-stage reference (see REFERENCE.md) — and (2) one or more
CANDIDATE hypnograms (e.g. the runner's v1 and v2 outputs). It aligns everything to fixed 30 s epochs
over the reference's span and reports, for each candidate vs the same reference on the same epochs:
accuracy, macro-recall, Cohen's kappa, per-stage recall, a confusion matrix, and stage minutes. With two
candidates it also prints the v1 -> v2 change (the kappa multiplier). Standard library only.

This is the measurement half of the runner: the runner turns a capture into a hypnogram, this turns a
hypnogram + your reference into a scorecard. Neither ships any data — you bring your own night.

------------------------------------------------------------------------------------------------------
FILE FORMATS  (all times are unix seconds; stage in {deep, rem, light, awake}; "wake" accepted == awake)

Reference / candidate hypnogram — ANY of (auto-detected):
  A) runner native:  {"sessions": [ {"stages": [ {"start": <unix>, "end": <unix>, "stage": "deep"} ...]} ...]}
  B) flat segments:  {"stages":   [ {"start": <unix>, "end": <unix>, "stage": "deep"} ...]}
  C) per-epoch:      {"epochs":   [ {"unix": <epoch_start_unix>, "stage": "deep"} ...]}
The runner emits format A, so its v1.json / v2.json plug straight in as candidates. Your reference is
easiest as format C (one row per 30 s epoch). Epochs the reference does not cover are ignored; an epoch a
candidate does not cover counts as "awake" (the stager placed that epoch in no sleep session).

USAGE
  # score the shipped stager and the v2 candidate against your reference:
  python3 score.py --reference reference.json --hypno v1=v1.json --hypno v2=v2.json

  # produce v1.json / v2.json first with the runner (see README.md step 2):
  #   "$BIN" /path/to/capture.json auto --stager v1 > v1.json
  #   "$BIN" /path/to/capture.json auto --stager v2 > v2.json
------------------------------------------------------------------------------------------------------
"""
import argparse, json, sys
from collections import Counter

STAGES = ["deep", "rem", "light", "awake"]
EPOCH_S = 30


def _norm(stage):
    return "awake" if stage in ("wake", "awake") else stage


def load_segments(path):
    """Return a sorted list of (start_unix, end_unix, stage) from any supported format."""
    doc = json.load(open(path))
    segs = []
    if "sessions" in doc:                       # A) runner native
        for ses in doc["sessions"]:
            for s in ses.get("stages", []):
                segs.append((int(s["start"]), int(s["end"]), _norm(s["stage"])))
    elif "stages" in doc:                       # B) flat segments
        for s in doc["stages"]:
            segs.append((int(s["start"]), int(s["end"]), _norm(s["stage"])))
    elif "epochs" in doc:                       # C) per-epoch
        for e in doc["epochs"]:
            u = int(e["unix"]); segs.append((u, u + EPOCH_S, _norm(e["stage"])))
    else:
        sys.exit(f"{path}: unrecognised format (need 'sessions', 'stages', or 'epochs')")
    segs.sort()
    return segs


def stage_at(segs, t):
    """Stage covering unix time t (linear scan; hypnograms are small). None if uncovered."""
    for a, b, stg in segs:
        if a <= t < b:
            return stg
    return None


def reference_epochs(ref_segs):
    """Every 30 s epoch the reference labels, as {epoch_mid_unix: stage}."""
    out = {}
    for a, b, stg in ref_segs:
        e = (a // EPOCH_S) * EPOCH_S
        while e < b:
            mid = e + EPOCH_S // 2
            if a <= mid < b:
                out[mid] = stg
            e += EPOCH_S
    return out


def scorecard(name, pred, truth):
    M = {c: Counter() for c in STAGES}
    for p, t in zip(pred, truth):
        M[t][p] += 1
    n = len(truth)
    correct = sum(M[c][c] for c in STAGES)
    rec = {c: (100 * M[c][c] / sum(M[c].values()) if sum(M[c].values()) else 0.0) for c in STAGES}
    macro = sum(rec.values()) / len(STAGES)
    # Cohen's kappa
    po = correct / n
    pe = sum((sum(M[c].values()) / n) * (sum(M[r][c] for r in STAGES) / n) for c in STAGES)
    kappa = (po - pe) / (1 - pe) if pe < 1 else 0.0
    print(f"\n=== {name} ===")
    print(f"  accuracy {po*100:.0f}%   macro-recall {macro:.0f}%   Cohen's kappa {kappa:.3f}")
    print("  per-stage recall: " + "  ".join(f"{c} {rec[c]:.0f}%" for c in STAGES))
    print("  confusion (rows = reference, cols = predicted):")
    print("        " + "".join(f"{c:>7}" for c in STAGES))
    for c in STAGES:
        print(f"  {c:5} " + "".join(f"{M[c][p]:>7}" for p in STAGES))
    pmin, tmin = Counter(pred), Counter(truth)
    print("  stage minutes pred/ref: " + "  ".join(f"{c} {pmin[c]*EPOCH_S/60:.0f}/{tmin[c]*EPOCH_S/60:.0f}" for c in STAGES))
    return dict(kappa=kappa, acc=po * 100, macro=macro, rec=rec)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--reference", required=True,
                    help="reference hypnogram: your per-epoch stage labels (see REFERENCE.md)")
    ap.add_argument("--hypno", action="append", required=True, metavar="LABEL=PATH",
                    help="a candidate hypnogram to score, e.g. v1=v1.json (repeatable)")
    a = ap.parse_args()

    ref = reference_epochs(load_segments(a.reference))
    epochs = sorted(ref)
    if not epochs:
        sys.exit("reference covers 0 epochs — check the file format (see REFERENCE.md)")
    nights = len({(e // 86400) for e in epochs})
    print(f"reference: {len(epochs)} labelled 30 s epochs spanning ~{nights} day(s)")

    results = {}
    for spec in a.hypno:
        if "=" not in spec:
            sys.exit(f"--hypno expects LABEL=PATH, got {spec!r}")
        label, path = spec.split("=", 1)
        segs = load_segments(path)
        truth = [ref[e] for e in epochs]
        pred = [(stage_at(segs, e) or "awake") for e in epochs]   # uncovered sleep epoch -> awake
        results[label] = scorecard(label, pred, truth)

    if len(results) >= 2:
        labels = list(results)
        a0, b0 = results[labels[0]], results[labels[1]]
        print(f"\n=== {labels[0]}  ->  {labels[1]} ===")
        mult = lambda new, old: f"{new/old:.1f}x" if old > 0 else "—"
        print(f"  kappa {a0['kappa']:.3f} -> {b0['kappa']:.3f}  ({mult(b0['kappa'], a0['kappa'])})")
        print(f"  macro-recall {a0['macro']:.0f}% -> {b0['macro']:.0f}%  ({mult(b0['macro'], a0['macro'])})")
        for c in STAGES:
            print(f"  {c} recall {a0['rec'][c]:.0f}% -> {b0['rec'][c]:.0f}%  ({mult(b0['rec'][c], a0['rec'][c])})")


if __name__ == "__main__":
    main()
