import Foundation
import WhoopProtocol
import StrandAnalytics

// sleep-stager-cli — stage a capture.json with SleepStager (v1) or SleepStagerV2 (v2) and emit a
// hypnogram as JSON, so the two stagers can be compared on real data.
//
// Input : EITHER a capture.json (a JSON array of {"hex","char"} records, e.g. from
//         tools/linux-capture/whoop_sync.py export) OR the noop app's raw-capture export (JSONL: one frame
//         per line with a `characteristic` field + `#` header comments). The format is auto-detected, so
//         a noop user can export from the app and run this directly — no conversion step.
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

let usage = "usage: sleep-stager-cli <capture.json> [whoop4|whoop5|auto] [--stager v1|v2]"
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
struct ExportRec: Decodable { let hex: String; let characteristic: String? }  // noop app raw-capture JSONL

// Accept EITHER a capture.json (a JSON array of {hex,char}) OR the noop app's raw-capture export
// (JSONL: one frame object per line, with `#` comment headers and a `characteristic` field). Detected by
// the first non-whitespace byte: '[' → array; anything else → JSONL.
let recs: [Rec]
do {
    let data = try Data(contentsOf: URL(fileURLWithPath: capturePath))
    let firstNonWS = data.first { $0 != 0x20 && $0 != 0x09 && $0 != 0x0A && $0 != 0x0D }
    if firstNonWS == 0x5B {   // '[' → capture.json array
        recs = try JSONDecoder().decode([Rec].self, from: data)
    } else {                  // noop raw-capture export (JSONL)
        let dec = JSONDecoder()
        var out: [Rec] = [], skipped = 0
        for line in String(decoding: data, as: UTF8.self).split(whereSeparator: { $0.isNewline }) {
            let t = line.trimmingCharacters(in: .whitespaces)
            if t.isEmpty || t.hasPrefix("#") { continue }
            if let ld = t.data(using: .utf8), let er = try? dec.decode(ExportRec.self, from: ld) {
                out.append(Rec(hex: er.hex, char: er.characteristic))
            } else { skipped += 1 }
        }
        if out.isEmpty { die("no frames in \(capturePath) (expected a capture.json array or a noop export JSONL)") }
        if skipped > 0 { FileHandle.standardError.write("skipped \(skipped) unparseable line(s)\n".data(using: .utf8)!) }
        recs = out
    }
} catch { die("could not read input: \(error)") }

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
    var i = h.startIndex
    while i < h.endIndex { let j = h.index(i, offsetBy: 2); o.append(UInt8(h[i..<j], radix: 16) ?? 0); i = j }
    return o
}

let parsed = recs.map { parseFrame(bytes($0.hex), family: familyFor($0.char)) }
let nW5 = recs.filter { familyFor($0.char) == .whoop5 }.count
FileHandle.standardError.write("parsed \(recs.count) frames (\(nW5) whoop5, \(recs.count - nW5) whoop4)\n".data(using: .utf8)!)
let s = extractHistoricalStreams(parsed, deviceClockRef: 0, wallClockRef: 0)

FileHandle.standardError.write("stager: \(stagerOpt)\n".data(using: .utf8)!)
let sessions = stagerOpt == "v2"
    ? SleepStagerV2.detectSleep(hr: s.hr, rr: s.rr, resp: s.resp, gravity: s.gravity)
    : SleepStager.detectSleep(hr: s.hr, rr: s.rr, resp: s.resp, gravity: s.gravity)

func minutes(_ stages: [StageSegment], _ stage: String) -> Double {
    stages.filter { $0.stage == stage }.reduce(0.0) { $0 + Double($1.end - $1.start) } / 60.0
}

let out = OutDoc(
    hr: s.hr.count, rr: s.rr.count, resp: s.resp.count, gravity: s.gravity.count,
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
