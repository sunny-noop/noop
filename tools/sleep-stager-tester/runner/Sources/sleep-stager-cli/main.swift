import Foundation
import WhoopProtocol
import StrandAnalytics

// sleep-stager-cli — stage a capture.json with SleepStager (v1) or SleepStagerV2 (v2) and emit a
// hypnogram as JSON, so the two stagers can be compared on real data.
//
// Input : auto-detected, one of —
//         • a capture.json — a JSON array of {"hex","char"} records (e.g. tools/linux-capture/
//           whoop_sync.py export), or the noop app's raw-frame export (JSONL: one frame per line with a
//           `characteristic` field + `#` header comments); both are decoded to streams here, or
//         • the noop app's "Export raw sensor data (CSV)" — the decoded long-format stream dump (hr / rr /
//           gravity / resp …). This is the only raw export a WHOOP 4.0 on Android can produce, and it
//           feeds the SAME stager, so a phone CSV and a frame capture stage through identical logic.
// Output: stdout JSON {"sessions":[{"stages":[{"start","end","stage"} ...]} ...]}. A one-line human
//         summary per session goes to stderr.
//
// Pick the stager with `--stager v1|v2` (default v1). Family is detected per record from the source
// characteristic UUID (fd4b… → whoop5, 6108… → whoop4); a positional "whoop4"|"whoop5"|"auto" forces it.
//
// Uses StrandAnalytics' public API (SleepStager.detectSleep / SleepStagerV2.detectSleep) via a package
// dependency — no copied or symlinked sources. Builds on macOS.

struct OutStage: Codable { let start: Int; let end: Int; let stage: String }
struct OutSession: Codable {
    let start: Int, end: Int
    let efficiency: Double
    let deepMin: Double, remMin: Double, lightMin: Double, wakeMin: Double
    let stages: [OutStage]
}
struct OutDoc: Codable {
    let hr: Int, rr: Int, resp: Int, gravity: Int
    let sessions: [OutSession]
}

func die(_ m: String) -> Never { FileHandle.standardError.write((m + "\n").data(using: .utf8)!); exit(2) }

let usage = "usage: sleep-stager-cli <capture.json|raw-sensors.csv> [whoop4|whoop5|auto] [--stager v1|v2]"
// Parse args: a positional capture path, an optional positional family, and a `--stager v1|v2` flag
// selecting which stager produces the hypnogram (v1 = shipped SleepStager, v2 = SleepStagerV2 recipe).
var positional: [String] = []
var stagerOpt = "v1"
do {
    let argv = CommandLine.arguments
    var ai = 1
    while ai < argv.count {
        let a = argv[ai]
        if a == "--stager" {
            ai += 1
            guard ai < argv.count else { die("--stager needs a value (v1|v2)") }
            stagerOpt = argv[ai].lowercased()
        } else if a.hasPrefix("--stager=") {
            stagerOpt = String(a.dropFirst("--stager=".count)).lowercased()
        } else {
            positional.append(a)
        }
        ai += 1
    }
}
guard let capturePath = positional.first else { die(usage) }
guard stagerOpt == "v1" || stagerOpt == "v2" else { die("--stager must be v1 or v2 (got \(stagerOpt))") }

struct Rec: Decodable { let hex: String; let char: String? }
struct ExportRec: Decodable { let hex: String; let characteristic: String? }  // noop app raw-frame JSONL

// Decoded streams the stager consumes. Filled EITHER by decoding raw frames (capture.json / raw-frame
// JSONL) via extractHistoricalStreams, OR straight from the noop app's "Export raw sensor data (CSV)".
var hrArr: [HRSample] = []
var rrArr: [RRInterval] = []
var respArr: [RespSample] = []
var gravArr: [GravitySample] = []

// Input dispatch by first non-whitespace byte: '[' → capture.json array; otherwise a raw-sensor CSV if a
// header/comment line names the schema (`unix_s` + `stream`), else a raw-frame JSONL.
let inData: Data
do { inData = try Data(contentsOf: URL(fileURLWithPath: capturePath)) }
catch { die("could not read input: \(error)") }
let firstNonWS = inData.first { $0 != 0x20 && $0 != 0x09 && $0 != 0x0A && $0 != 0x0D }
let text = String(decoding: inData, as: UTF8.self)
let isCSV = firstNonWS != 0x5B && text.split(whereSeparator: { $0.isNewline })
    .prefix(8).contains { $0.contains("unix_s") && $0.contains("stream") }

if isCSV {
    // noop "Export raw sensor data (CSV)" — long format, one row per sample, only that stream's columns
    // filled. Header (after `#` comments): unix_s,iso_utc,stream,hr_bpm,rr_ms,grav_x,grav_y,grav_z,
    //   step_counter,ppg_bpm,ppg_conf,spo2_red,spo2_ir,skintemp_raw,resp_raw,event_kind,event_payload
    // We take only the four streams detectSleep needs (hr/rr/gravity/resp); steps/ppghr/spo2/skintemp/
    // event are ignored. ts is wall-clock unix seconds — exactly HRSample.ts etc., so no clock alignment.
    func i(_ f: ArraySlice<Substring>, _ k: Int) -> Int? {   // lenient int (tolerates "812.0")
        guard let v = f.dropFirst(k).first else { return nil }
        let t = v.trimmingCharacters(in: .whitespaces); if t.isEmpty { return nil }
        return Int(t) ?? Double(t).map { Int($0) }
    }
    func d(_ f: ArraySlice<Substring>, _ k: Int) -> Double? {
        guard let v = f.dropFirst(k).first else { return nil }
        let t = v.trimmingCharacters(in: .whitespaces); if t.isEmpty { return nil }
        return Double(t)
    }
    var bad = 0
    for line in text.split(whereSeparator: { $0.isNewline }) {
        if line.hasPrefix("#") || line.hasPrefix("unix_s") { continue }
        let f = line.split(separator: ",", omittingEmptySubsequences: false)[...]
        guard f.count >= 3, let ts = i(f, 0) else { if !line.isEmpty { bad += 1 }; continue }
        switch f[f.startIndex + 2] {
        case "hr":      if let v = i(f, 3) { hrArr.append(HRSample(ts: ts, bpm: v)) }
        case "rr":      if let v = i(f, 4) { rrArr.append(RRInterval(ts: ts, rrMs: v)) }
        case "gravity": if let x = d(f, 5), let y = d(f, 6), let z = d(f, 7) {
                            gravArr.append(GravitySample(ts: ts, x: x, y: y, z: z)) }
        case "resp":    if let v = i(f, 14) { respArr.append(RespSample(ts: ts, raw: v)) }
        default: break   // steps / ppghr / spo2 / skintemp / event — not staging inputs
        }
    }
    hrArr.sort { $0.ts < $1.ts }; rrArr.sort { $0.ts < $1.ts }
    respArr.sort { $0.ts < $1.ts }; gravArr.sort { $0.ts < $1.ts }
    if gravArr.isEmpty { die("no gravity samples in CSV — cannot stage (need the gravity stream)") }
    FileHandle.standardError.write("csv: hr=\(hrArr.count) rr=\(rrArr.count) resp=\(respArr.count) gravity=\(gravArr.count)\(bad > 0 ? " (\(bad) unparsed line(s))" : "")\n".data(using: .utf8)!)
} else {
    let recs: [Rec]
    if firstNonWS == 0x5B {   // '[' → capture.json array
        do { recs = try JSONDecoder().decode([Rec].self, from: inData) }
        catch { die("could not decode capture.json: \(error)") }
    } else {                  // noop raw-frame export (JSONL)
        let dec = JSONDecoder()
        var outR: [Rec] = [], skipped = 0
        for line in text.split(whereSeparator: { $0.isNewline }) {
            let t = line.trimmingCharacters(in: .whitespaces)
            if t.isEmpty || t.hasPrefix("#") { continue }
            if let ld = t.data(using: .utf8), let er = try? dec.decode(ExportRec.self, from: ld) {
                outR.append(Rec(hex: er.hex, char: er.characteristic))
            } else { skipped += 1 }
        }
        if outR.isEmpty { die("no frames in \(capturePath) (expected a capture.json array, a raw-sensor CSV, or a raw-frame JSONL)") }
        if skipped > 0 { FileHandle.standardError.write("skipped \(skipped) unparseable line(s)\n".data(using: .utf8)!) }
        recs = outR
    }

    let forced: DeviceFamily? = {
        guard positional.count > 1 else { return nil }
        switch positional[1].lowercased() {
        case "whoop5", "5": return .whoop5
        case "whoop4", "4": return .whoop4
        default: return nil   // "auto"
        }
    }()
    func familyFor(_ char: String?) -> DeviceFamily {
        if let f = forced { return f }
        if let c = char?.lowercased(), c.hasPrefix("fd4b") { return .whoop5 }
        return .whoop4   // 6108… or unknown → whoop4
    }
    func bytes(_ h: String) -> [UInt8] {
        var o = [UInt8](); o.reserveCapacity(h.count / 2)
        var idx = h.startIndex
        while idx < h.endIndex { let j = h.index(idx, offsetBy: 2); o.append(UInt8(h[idx..<j], radix: 16) ?? 0); idx = j }
        return o
    }
    let parsed = recs.map { parseFrame(bytes($0.hex), family: familyFor($0.char)) }
    let nW5 = recs.filter { familyFor($0.char) == .whoop5 }.count
    FileHandle.standardError.write("parsed \(recs.count) frames (\(nW5) whoop5, \(recs.count - nW5) whoop4)\n".data(using: .utf8)!)
    let s = extractHistoricalStreams(parsed, deviceClockRef: 0, wallClockRef: 0)
    hrArr = s.hr; rrArr = s.rr; respArr = s.resp; gravArr = s.gravity
}

FileHandle.standardError.write("stager: \(stagerOpt)\n".data(using: .utf8)!)
let sessions = stagerOpt == "v2"
    ? SleepStagerV2.detectSleep(hr: hrArr, rr: rrArr, resp: respArr, gravity: gravArr)
    : SleepStager.detectSleep(hr: hrArr, rr: rrArr, resp: respArr, gravity: gravArr)

func minutes(_ stages: [StageSegment], _ stage: String) -> Double {
    stages.filter { $0.stage == stage }.reduce(0.0) { $0 + Double($1.end - $1.start) } / 60.0
}

let out = OutDoc(
    hr: hrArr.count, rr: rrArr.count, resp: respArr.count, gravity: gravArr.count,
    sessions: sessions.map { ses in
        OutSession(
            start: ses.start, end: ses.end, efficiency: ses.efficiency,
            deepMin: minutes(ses.stages, "deep"), remMin: minutes(ses.stages, "rem"),
            lightMin: minutes(ses.stages, "light"), wakeMin: minutes(ses.stages, "wake"),
            stages: ses.stages.map { OutStage(start: $0.start, end: $0.end, stage: $0.stage) })
    })

let enc = JSONEncoder(); enc.outputFormatting = [.prettyPrinted, .sortedKeys]
FileHandle.standardOutput.write(try enc.encode(out))
FileHandle.standardOutput.write("\n".data(using: .utf8)!)

let df = DateFormatter(); df.dateFormat = "MM-dd HH:mm"
for ses in out.sessions {
    let f = { (u: Int) in df.string(from: Date(timeIntervalSince1970: Double(u))) }
    FileHandle.standardError.write(String(format: "sleep %@→%@  eff %.0f%%  deep %.0fm rem %.0fm light %.0fm wake %.0fm\n",
        f(ses.start), f(ses.end), ses.efficiency * 100,
        ses.deepMin, ses.remMin, ses.lightMin, ses.wakeMin).data(using: .utf8)!)
}
