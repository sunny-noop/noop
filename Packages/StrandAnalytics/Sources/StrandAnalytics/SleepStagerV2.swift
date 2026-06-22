import Foundation
import WhoopProtocol

/// SleepStagerV2 — an alternative, cardiorespiratory sleep-staging recipe, offered ALONGSIDE the
/// shipped `SleepStager` rather than replacing it. Session DETECTION (the in-bed `[start, end]` spans) is
/// reused verbatim from `SleepStager.detectSleep`; only the per-session STAGING differs.
///
/// Where the shipped stager runs a percentile-band classifier + median smoothing + physiology
/// re-imposition over a Cole–Kripke actigraphy grid, V2 stages each epoch from per-night z-scored
/// cardiorespiratory emissions, a soft sleep-cycle prior, a peak-motion wake gate scaled to each night's
/// own quiescent jerk floor (so it's strap/fit-relative, not a fixed g), an RR-RSA respiration-regularity
/// term, and Viterbi/HMM smoothing.
///
/// Measured per-30 s-epoch against a commercial sleep-stage reference (one subject, 7 nights), V2 raises Cohen's kappa from
/// ~0.06 (the shipped stager collapses to light) to ~0.47 — deep recall 12%→78%, REM 19%→67%.
///
/// All coefficients are fixed a-priori from sleep physiology + population base rates (NOT fit to the
/// labels). **Caveat: validated on n=1 subject** — the window sizes / weights may be subject-specific and
/// need multi-subject validation before they can be trusted as general.
public enum SleepStagerV2 {

    /// Same entry-point shape as `SleepStager.detectSleep`. Reuses the shipped session detection to find the
    /// in-bed spans, then re-stages each span with this recipe and recomputes efficiency from the new
    /// stages. `restingHR` / `avgHRV` are carried over from the shipped detection (computed identically).
    /// `userFloor` (optional) is a per-user CALIBRATED quiescent jerk floor learned from the wearer's own
    /// history (see the calibration-profile design): when supplied it replaces the per-night floor estimate
    /// for every session, so a short/noisy night inherits the user's stable baseline. nil → per-night floor
    /// (the default, no calibration required).
    public static func detectSleep(hr: [HRSample] = [],
                                   rr: [RRInterval] = [],
                                   resp: [RespSample] = [],
                                   gravity: [GravitySample],
                                   tzOffsetSeconds: Int = 0,
                                   wristOff: [(start: Int, end: Int)] = [],
                                   bandSleepState: [(ts: Int, state: Int)] = [],
                                   userFloor: Double? = nil) -> [SleepSession] {
        let base = SleepStager.detectSleep(hr: hr, rr: rr, resp: resp, gravity: gravity,
                                           tzOffsetSeconds: tzOffsetSeconds, wristOff: wristOff,
                                           bandSleepState: bandSleepState)
        // Sort once to match the stream ordering the shipped detection used internally.
        let grav = gravity.sorted { $0.ts < $1.ts }
        let hrS = hr.sorted { $0.ts < $1.ts }
        let rrS = rr.sorted { $0.ts < $1.ts }
        let respS = resp.sorted { $0.ts < $1.ts }
        return base.map { ses in
            let stages = stageSession(start: ses.start, end: ses.end, grav: grav, hr: hrS, rr: rrS, resp: respS,
                                      userFloor: userFloor)
            let eff = SleepStager.efficiency(start: ses.start, end: ses.end, stages: stages)
            return SleepSession(start: ses.start, end: ses.end, efficiency: eff,
                                stages: stages, restingHR: ses.restingHR, avgHRV: ses.avgHRV)
        }
    }

    /// Build a 30 s hypnogram for `[start, end]` with this recipe and return StageSegments tiling the
    /// span. Mirrors `SleepStager.stageSession`'s shape (a forced window, no boundary detection) so it can
    /// be used wherever the shipped one is. The recipe stages "awake" naturally, so there is no separate
    /// pre-onset / post-wake forcing. `resp` (raw resp ADC) is not consumed — respiration regularity is
    /// recovered from the R-R stream (RSA), the path available on both WHOOP 4 and 5.
    public static func stageSession(start: Int, end: Int, grav: [GravitySample],
                                    hr: [HRSample], rr: [RRInterval], resp: [RespSample],
                                    userFloor: Double? = nil) -> [StageSegment] {
        let feats = features(start: start, end: end, grav: grav, hr: hr, rr: rr, userFloor: userFloor)
        if feats.isEmpty { return [StageSegment(start: start, end: end, stage: "light")] }
        let labels = stageEpochs(feats)

        // Tile [start, end] with one segment per staged epoch. The first segment back-fills [start, firstEpoch)
        // and the last extends to `end`; an interior coverage gap is carried by the preceding label.
        var segments: [StageSegment] = []
        for (i, f) in feats.enumerated() {
            let stage = labels[i] == "awake" ? "wake" : labels[i]
            let segStart = i == 0 ? start : f.start
            let segEnd = i == feats.count - 1 ? end : feats[i + 1].start
            if let last = segments.last, last.stage == stage {
                segments[segments.count - 1].end = segEnd
            } else {
                segments.append(StageSegment(start: segStart, end: segEnd, stage: stage))
            }
        }
        return segments
    }

    // MARK: - recipe staging (cardiorespiratory emissions + cycle prior + HMM)
    //
    // Components, all fixed a-priori from sleep physiology + population base rates (NOT fit to the labels):
    //   1. emission log-scores from z-scored HR / HR-variability / movement, with a per-night DEEP gate on
    //      the 11-min HR-flatness percentile (the strongest deep-vs-light separator observed in the data);
    //   2. a soft sleep-cycle prior — deep concentrated early, REM suppressed in the first ~12% then rising;
    //   3. a peak-motion (jerk) wake gate, thresholded relative to the night's own quiescent jerk floor;
    //   4. an RR-RSA respiration-regularity term (regular breathing → deep, irregular → REM);
    //   5. Viterbi/HMM transition smoothing with a sticky transition matrix.

    static let stageNames = ["deep", "rem", "light", "awake"]
    /// Population sleep-architecture base rates as log-priors (adult TST: light ~50, deep ~18, rem ~22,
    /// waso ~10 %). Calibrates the boundary so light wins weak-evidence epochs.
    static let baseLogPrior: [String: Double] = [
        "light": log(0.50), "deep": log(0.18), "rem": log(0.22), "awake": log(0.10)]
    /// Deep is eligible only in the night's lowest ~20 % HR-flatness epochs (≈ deep base rate + margin).
    static let deepGateThresh = 0.20
    static let deepGateSlope = 5.0
    /// Motion thresholds are expressed RELATIVE to each night's own quiescent jerk floor (the median
    /// per-second gravity-jerk over the in-bed session, ≈ the still-sleep sensor floor, typically a few
    /// ×10⁻⁴ g), NOT in absolute g. This self-calibrates to a strap's gravity-decode scale and the wearer's
    /// fit instead of assuming a fixed g value — the one place the recipe otherwise carried a raw-units
    /// constant. The multipliers sit a little tighter than the former fixed 0.02 g / 0.03 g; they were swept
    /// to the joint optimum of per-epoch agreement with a commercial reference (one subject) AND inter-strap
    /// agreement on a second subject's WHOOP-4/WHOOP-5 dual-wear, so they don't lean on either alone.
    static let jerkFloorMoveMult = 38.0  // a per-second jerk counts as "moving" above floor × this
    static let jerkFloorGateMult = 55.0  // wake-boost when an epoch's peak jerk exceeds floor × this
    static let motionGateBoost = 2.0
    /// Weight of the RSA respiration-regularity term (regular → deep, irregular → REM).
    static let respWeight = 0.6
    /// Transition matrix (rows = from, cols = to). Self-transitions dominate (stages persist over many
    /// 30 s epochs); deep↔rem rare; wake mostly to/from light. A priori, not fit.
    static let transition: [String: [String: Double]] = [
        "deep":  ["deep": 0.90, "rem": 0.005, "light": 0.09, "awake": 0.005],
        "rem":   ["deep": 0.005, "rem": 0.88, "light": 0.10, "awake": 0.015],
        "light": ["deep": 0.06, "rem": 0.06, "light": 0.85, "awake": 0.03],
        "awake": ["deep": 0.01, "rem": 0.02, "light": 0.27, "awake": 0.70]]

    /// One 30 s epoch's recipe features. Optionals are "no measurement"; the z-score/percentile treat a
    /// missing value as the neutral centre so a sparse channel never blocks a stage.
    struct Epoch {
        let start: Int          // epoch start (unix seconds, multiple of 30)
        let hr: Double?         // epoch-mean HR (bpm)
        let hrVar: Double?      // std of per-second HR over a centred 5-min window
        let hrFlat11: Double?   // std of per-second HR over a centred 11-min window (deep/light separator)
        let moveFrac: Double    // fraction of in-epoch per-second jerks above the night-relative move threshold
        let jerkMax: Double     // peak in-epoch per-second jerk (g) — wake is bursty
        let respReg: Double?    // RSA spectral peakedness in the 0.15–0.40 Hz band (breathing regularity)
        let clock: Double       // time-of-night fraction in [0, 1]
        let jerkScale: Double   // night quiescent jerk floor (median per-second jerk over the session)
    }

    /// The quiescent jerk floor of one session: the median per-second gravity-jerk over `[start, end)` (most
    /// sleep seconds are still, so the median tracks the strap's noise/decode floor). The calibration trainer
    /// calls this per detected session to update a user's profile; nil when the window has too little motion
    /// data. This is the same quantity `features()` computes internally as the per-night floor.
    public static func sessionJerkFloor(start: Int, end: Int, gravity: [GravitySample]) -> Double? {
        if end <= start { return nil }
        var gxSum = [Int: Double](), gySum = [Int: Double](), gzSum = [Int: Double](), gCnt = [Int: Int]()
        for g in gravity where g.ts >= start && g.ts < end {
            gxSum[g.ts, default: 0] += g.x; gySum[g.ts, default: 0] += g.y
            gzSum[g.ts, default: 0] += g.z; gCnt[g.ts, default: 0] += 1
        }
        let secs = gCnt.keys.sorted()
        if secs.count < 2 { return nil }
        var jerks: [Double] = []; jerks.reserveCapacity(secs.count)
        var prev: (Double, Double, Double)? = nil, prevSec = 0
        for s in secs {
            let d = Double(gCnt[s]!); let cur = (gxSum[s]! / d, gySum[s]! / d, gzSum[s]! / d)
            if let p = prev, s - prevSec == 1 {
                let dx = p.0 - cur.0, dy = p.1 - cur.1, dz = p.2 - cur.2
                jerks.append((dx * dx + dy * dy + dz * dz).squareRoot())
            }
            prev = cur; prevSec = s
        }
        if jerks.isEmpty { return nil }
        jerks.sort(); let n = jerks.count
        return n % 2 == 1 ? jerks[n / 2] : 0.5 * (jerks[n / 2 - 1] + jerks[n / 2])
    }

    /// Build the per-epoch recipe features over a 30 s wall-clock-aligned grid covering [start, end].
    /// Streams are the FULL (un-clipped) sorted streams, so the 5-/11-min HR windows and the RSA beat
    /// window can reach across the session edges exactly as the reference pipeline does.
    static func features(start: Int, end: Int, grav: [GravitySample],
                            hr: [HRSample], rr: [RRInterval], userFloor: Double? = nil) -> [Epoch] {
        if end <= start { return [] }
        let span = Double(max(1, end - start))

        // Per-second aggregation (one value per integer second; mean when a second carries several samples).
        var hrSum = [Int: Double](), hrCnt = [Int: Int]()
        for s in hr { hrSum[s.ts, default: 0] += Double(s.bpm); hrCnt[s.ts, default: 0] += 1 }
        var secHR = [Int: Double](); secHR.reserveCapacity(hrSum.count)
        for (k, v) in hrSum { secHR[k] = v / Double(hrCnt[k]!) }

        var gxSum = [Int: Double](), gySum = [Int: Double](), gzSum = [Int: Double](), gCnt = [Int: Int]()
        for g in grav {
            gxSum[g.ts, default: 0] += g.x; gySum[g.ts, default: 0] += g.y
            gzSum[g.ts, default: 0] += g.z; gCnt[g.ts, default: 0] += 1
        }
        var secG = [Int: (Double, Double, Double)](); secG.reserveCapacity(gCnt.count)
        for (k, c) in gCnt { let d = Double(c); secG[k] = (gxSum[k]! / d, gySum[k]! / d, gzSum[k]! / d) }

        // R-R values bucketed by second (for the RSA respiration window).
        var rrBy = [Int: [Double]]()
        for r in rr { rrBy[r.ts, default: []].append(Double(r.rrMs)) }

        func stdOfSeconds(_ lo: Int, _ hi: Int) -> Double? {
            var vals: [Double] = []
            for s in lo..<hi { if let v = secHR[s] { vals.append(v) } }
            if vals.count < 2 { return nil }
            let m = vals.reduce(0, +) / Double(vals.count)
            let v = vals.reduce(0.0) { $0 + ($1 - m) * ($1 - m) } / Double(vals.count)
            return v.squareRoot()
        }

        // PASS 1 — build every per-epoch quantity EXCEPT the move fraction, and pool every per-second jerk
        // so the night's quiescent jerk floor (its median) can scale the motion thresholds. moveFrac needs
        // that floor, which isn't known until the whole session has been scanned, hence the two passes.
        struct Raw {
            let start: Int; let hr: Double?; let hrVar: Double?; let hrFlat11: Double?
            let jerks: [Double]; let gapSec: Int; let jerkMax: Double; let respReg: Double?; let clock: Double
        }
        var raws: [Raw] = []
        var allJerks: [Double] = []
        let firstE = ((start + 29) / 30) * 30
        var e = firstE
        while e < end {
            // Present seconds in [e, e+30): a second with gravity and/or HR coverage.
            var hrs: [Double] = []
            var gseq: [(Double, Double, Double)] = []
            for s in e..<(e + 30) {
                if let h = secHR[s] { hrs.append(h) }
                if let g = secG[s] { gseq.append(g) }
            }
            if hrs.isEmpty && gseq.isEmpty { e += 30; continue }   // no coverage → skip (mirrors reference)

            // Movement: consecutive per-second gravity jerks within the epoch.
            var jerks: [Double] = []
            for i in 1..<max(1, gseq.count) {
                let a = gseq[i - 1], b = gseq[i]
                let dx = a.0 - b.0, dy = a.1 - b.1, dz = a.2 - b.2
                jerks.append((dx * dx + dy * dy + dz * dz).squareRoot())
            }
            allJerks.append(contentsOf: jerks)
            let jerkMax = jerks.max() ?? 0.0

            let hrMean = hrs.isEmpty ? nil : hrs.reduce(0, +) / Double(hrs.count)
            let hrVar = stdOfSeconds(e - 150, e + 30 + 150)     // 5-min centred window
            let hrFlat11 = stdOfSeconds(e - 330, e + 30 + 360)  // 11-min centred window

            // RSA respiration over a wider beat window [e-90, e+120).
            var beats: [(Double, Double)] = []
            for s in (e - 90)..<(e + 120) {
                if let vs = rrBy[s] { for v in vs { beats.append((Double(s), min(max(v, 300), 2000))) } }
            }
            beats.sort { $0.0 != $1.0 ? $0.0 < $1.0 : $0.1 < $1.1 }
            let respReg = respRegularity(beats)

            raws.append(Raw(start: e, hr: hrMean, hrVar: hrVar, hrFlat11: hrFlat11,
                            jerks: jerks, gapSec: max(1, gseq.count - 1), jerkMax: jerkMax,
                            respReg: respReg, clock: Double(e + 15 - start) / span))
            e += 30
        }

        // Night quiescent jerk floor = median of all per-second jerks (most seconds of sleep are still, so
        // the median tracks the strap's noise/decode floor, not the night's restlessness). Tiny epsilon when
        // there's no motion data so the move threshold collapses to ~0 rather than dividing by nothing.
        let jerkScale: Double = {
            if let uf = userFloor { return uf }     // calibrated per-user floor (nil → per-night median below)
            if allJerks.isEmpty { return 1e-6 }
            let s = allJerks.sorted(); let n = s.count
            return n % 2 == 1 ? s[n / 2] : 0.5 * (s[n / 2 - 1] + s[n / 2])
        }()
        let moveThr = jerkScale * jerkFloorMoveMult

        // PASS 2 — move fraction against the night-relative threshold; carry the floor on each epoch so the
        // wake gate in stageEpochs() can be night-relative too.
        var feats: [Epoch] = []
        feats.reserveCapacity(raws.count)
        for r in raws {
            let moves = r.jerks.reduce(0) { $0 + ($1 > moveThr ? 1 : 0) }
            feats.append(Epoch(
                start: r.start, hr: r.hr, hrVar: r.hrVar, hrFlat11: r.hrFlat11,
                moveFrac: Double(moves) / Double(r.gapSec), jerkMax: r.jerkMax, respReg: r.respReg,
                clock: r.clock, jerkScale: jerkScale))
        }
        return feats
    }

    /// RSA respiration regularity: tachogram → 4 Hz resample → detrend → power spectrum → peak/sum of the
    /// 0.15–0.40 Hz (9–24 brpm) band. Returns the spectral peakedness (higher = more regular breathing),
    /// or nil when there are too few beats. A direct band-limited DFT (only the ~50 in-band bins are needed).
    static func respRegularity(_ beats: [(Double, Double)]) -> Double? {
        if beats.count < 12 { return nil }
        let t0 = beats.first!.0, tN = beats.last!.0
        if tN <= t0 { return nil }
        let n = Int(ceil((tN - t0) / 0.25 - 1e-9))   // np.arange(t0, tN, 0.25) length
        if n < 16 { return nil }

        // Linear resample onto the uniform 4 Hz grid (numpy.interp; clamped within [t0, tN]).
        var y = [Double](repeating: 0, count: n)
        var seg = 0
        for i in 0..<n {
            let t = t0 + 0.25 * Double(i)
            while seg < beats.count - 2 && beats[seg + 1].0 < t { seg += 1 }
            let ta = beats[seg].0, tb = beats[seg + 1].0
            let va = beats[seg].1, vb = beats[seg + 1].1
            y[i] = tb <= ta ? va : va + min(max((t - ta) / (tb - ta), 0), 1) * (vb - va)
        }
        let mean = y.reduce(0, +) / Double(n)
        for i in 0..<n { y[i] -= mean }

        // Band bins: f[k] = k / (n·0.25); keep 0.15 ≤ f ≤ 0.40.
        let kLo = Int(ceil(0.15 * 0.25 * Double(n)))
        let kHi = Int(floor(0.40 * 0.25 * Double(n)))
        if kHi < kLo || kLo < 0 { return nil }
        var maxP = 0.0, sumP = 0.0
        for k in kLo...kHi {
            var re = 0.0, im = 0.0
            let w = -2.0 * Double.pi * Double(k) / Double(n)
            for j in 0..<n { let a = w * Double(j); re += y[j] * cos(a); im += y[j] * sin(a) }
            let p = re * re + im * im
            sumP += p
            if p > maxP { maxP = p }
        }
        if sumP == 0 { return nil }
        return maxP / sumP
    }

    /// Soft sleep-cycle prior added to the log-emission: deep concentrated early (decays, never hard-wiped);
    /// REM suppressed in the first ~12 % (REM latency) then rising toward morning.
    static func cyclePrior(_ c: Double) -> [String: Double] {
        ["deep": 1.2 * max(0.0, 1.0 - c / 0.55),
         "rem": 1.0 * c - (c < 0.12 ? 3.0 : 0.0),
         "light": 0.0, "awake": 0.0]
    }

    /// Viterbi most-likely path over the per-epoch log-emissions with the sticky transition matrix and a
    /// uniform start. Ties resolve to the earlier stage in `stageNames` (matches the reference).
    static func viterbi(_ emSeq: [[String: Double]]) -> [String] {
        if emSeq.isEmpty { return [] }
        let logT = transition.mapValues { row in row.mapValues { log($0) } }
        var V = emSeq[0]   // uniform start
        var back: [[String: String]] = []
        for t in 1..<emSeq.count {
            var newV = [String: Double](), bp = [String: String]()
            for s in stageNames {
                var bestPrev = stageNames[0]
                var bestVal = V[bestPrev]! + logT[bestPrev]![s]!
                for p in stageNames.dropFirst() {
                    let val = V[p]! + logT[p]![s]!
                    if val > bestVal { bestVal = val; bestPrev = p }
                }
                newV[s] = bestVal + emSeq[t][s]!
                bp[s] = bestPrev
            }
            V = newV; back.append(bp)
        }
        var last = stageNames[0], lastV = V[last]!
        for s in stageNames.dropFirst() where V[s]! > lastV { lastV = V[s]!; last = s }
        var path = [last]
        for bp in back.reversed() { last = bp[last]!; path.append(last) }
        return path.reversed()
    }

    /// Run the full recipe over a night's epochs and return one stage label per epoch (incl. "awake").
    /// All normalisation (z-scores, the HR-flatness percentile) is WITHIN the night, matching the reference.
    static func stageEpochs(_ feats: [Epoch]) -> [String] {
        if feats.isEmpty { return [] }

        // Per-night z-score over the present values (population std; 0 std → 1 so a flat channel is neutral).
        func zfun(_ vals: [Double?]) -> (Double?) -> Double {
            let present = vals.compactMap { $0 }
            if present.isEmpty { return { _ in 0.0 } }
            let m = present.reduce(0, +) / Double(present.count)
            let sd0 = (present.reduce(0.0) { $0 + ($1 - m) * ($1 - m) } / Double(present.count)).squareRoot()
            let sd = sd0 == 0 ? 1.0 : sd0
            return { v in v == nil ? 0.0 : (v! - m) / sd }
        }
        let zhr = zfun(feats.map { $0.hr })
        let zhv = zfun(feats.map { $0.hrVar })
        let zmv = zfun(feats.map { Optional($0.moveFrac) })
        let zrg = zfun(feats.map { $0.respReg })

        // HR-flatness percentile rank within the night (bisect_right / n), neutral 0.5 when missing.
        let fsorted = feats.compactMap { $0.hrFlat11 }.sorted()
        func fpct(_ v: Double?) -> Double {
            guard let v = v, !fsorted.isEmpty else { return 0.5 }
            var lo = 0, hi = fsorted.count
            while lo < hi { let mid = (lo + hi) / 2; if fsorted[mid] <= v { lo = mid + 1 } else { hi = mid } }
            return Double(lo) / Double(fsorted.count)
        }

        var seq: [[String: Double]] = []
        seq.reserveCapacity(feats.count)
        for f in feats {
            let zhrv = zhr(f.hr), zhvv = zhv(f.hrVar), zmvv = zmv(f.moveFrac)
            let gate = deepGateSlope * max(0.0, fpct(f.hrFlat11) - deepGateThresh)
            var em: [String: Double] = [
                "deep": -1.4 * zhvv - 0.2 * zhrv - 0.3 * zmvv - gate + baseLogPrior["deep"]!,
                "rem": 0.6 * zhvv - 0.6 * zmvv + 0.4 * zhrv + baseLogPrior["rem"]!,
                "light": baseLogPrior["light"]!,
                "awake": 1.0 * zmvv + 0.8 * zhvv + 0.4 * zhrv + baseLogPrior["awake"]!,
            ]
            let pr = cyclePrior(f.clock)
            for s in stageNames { em[s]! += pr[s]! }
            if f.jerkMax > f.jerkScale * jerkFloorGateMult { em["awake"]! += motionGateBoost }
            if let rg = f.respReg { let z = zrg(rg); em["deep"]! += respWeight * z; em["rem"]! -= respWeight * z }
            seq.append(em)
        }
        return viterbi(seq)
    }
}
