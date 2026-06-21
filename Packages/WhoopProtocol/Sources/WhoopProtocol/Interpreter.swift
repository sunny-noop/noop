import Foundation

public struct DecodedField: Codable, Equatable {
    public let off: Int
    public let len: Int
    public let name: String
    public let cat: String
    public let value: ParsedValue?
    public let raw: String
    public let note: String?
}

public struct ParsedFrame: Codable, Equatable {
    public let ok: Bool
    public let typeName: String
    public let seq: Int?
    public let cmdName: String?
    public let crcOK: Bool?
    public let lenBytes: Int
    public let rawHex: String
    public let fields: [DecodedField]
    public let parsed: [String: ParsedValue]
}

// MARK: - low-level readers (LE), nil when out of range (mirrors interpreter._read)

@inline(__always) private func readU8(_ f: [UInt8], _ off: Int) -> Int? {
    off + 1 <= f.count ? Int(f[off]) : nil
}
@inline(__always) private func readU16(_ f: [UInt8], _ off: Int) -> Int? {
    off + 2 <= f.count ? Int(f[off]) | (Int(f[off + 1]) << 8) : nil
}
@inline(__always) private func readU32(_ f: [UInt8], _ off: Int) -> Int? {
    guard off + 4 <= f.count else { return nil }
    return Int(f[off]) | (Int(f[off + 1]) << 8) | (Int(f[off + 2]) << 16) | (Int(f[off + 3]) << 24)
}
@inline(__always) private func readI16(_ f: [UInt8], _ off: Int) -> Int? {
    guard off + 2 <= f.count else { return nil }
    let raw = UInt16(f[off]) | (UInt16(f[off + 1]) << 8)
    return Int(Int16(bitPattern: raw))
}

@inline(__always) private func readI32(_ f: [UInt8], _ off: Int) -> Int? {
    guard off + 4 <= f.count else { return nil }
    let raw = UInt32(f[off]) | (UInt32(f[off + 1]) << 8) | (UInt32(f[off + 2]) << 16) | (UInt32(f[off + 3]) << 24)
    return Int(Int32(bitPattern: raw))
}

@inline(__always) private func readF32(_ f: [UInt8], _ off: Int) -> Double? {
    guard off + 4 <= f.count else { return nil }
    let bits = UInt32(f[off]) | (UInt32(f[off + 1]) << 8) | (UInt32(f[off + 2]) << 16) | (UInt32(f[off + 3]) << 24)
    return Double(Float(bitPattern: bits))   // float32 -> Double is exact, no rounding
}

/// Read a schema dtype at off; returns the integer value or nil if out of range.
private func readDType(_ f: [UInt8], _ off: Int, _ dtype: String) -> Int? {
    switch dtype {
    case "u8": return readU8(f, off)
    case "u16": return readU16(f, off)
    case "u32": return readU32(f, off)
    case "i16": return readI16(f, off)
    default: return nil
    }
}

private func hexString(_ bytes: ArraySlice<UInt8>) -> String {
    bytes.map { String(format: "%02x", $0) }.joined()
}

/// Field builder: accumulates annotated fields and a flat parsed dict. Port of Python FB.
final class FieldBuilder {
    let frame: [UInt8]
    var fields: [DecodedField] = []
    var parsed: [String: ParsedValue] = [:]

    init(_ frame: [UInt8]) {
        self.frame = frame
    }

    @discardableResult
    func add(_ off: Int, _ length: Int, _ name: String, _ cat: String,
             value: ParsedValue? = nil, note: String? = nil) -> FieldBuilder {
        let end = min(off + length, frame.count)
        let raw = off <= frame.count ? hexString(frame[max(0, off)..<max(off, end)]) : ""
        fields.append(DecodedField(off: off, len: length, name: name, cat: cat,
                                   value: value, raw: raw, note: note))
        if value != nil && cat != "frame" && cat != "unknown" {
            parsed[name] = value
        }
        return self
    }

    func region(_ start: Int, _ end: Int, _ name: String, _ cat: String, note: String? = nil) {
        if start < end && end <= frame.count {
            add(start, end - start, name, cat, value: .string("[\(end - start) bytes]"), note: note)
        }
    }
}

public func parseFrame(_ frame: [UInt8]) -> ParsedFrame {
    let rawHex = frame.map { String(format: "%02x", $0) }.joined()
    if frame.count < 8 || frame[0] != 0xAA {
        return ParsedFrame(ok: false, typeName: "INVALID/FRAGMENT", seq: nil, cmdName: nil,
                           crcOK: nil, lenBytes: frame.count, rawHex: rawHex,
                           fields: [], parsed: [:])
    }

    let schema = loadSchema()
    let check = verifyFrame(frame)
    let length = check.length
    let crcOK = check.crc32OK

    let t = Int(frame[4])
    let typeName = schema.typeName(t)
    let seq = Int(frame[5])

    let fb = FieldBuilder(frame)
    // envelope
    fb.add(0, 1, "SOF", "frame", value: .string("0xAA"))
    fb.add(1, 2, "length", "frame", value: length.map { .int($0) })
    fb.add(3, 1, "crc8", "frame", value: .string(String(format: "0x%02X", frame[3])))
    fb.add(4, 1, "packet_type", "frame", value: .string(typeName))
    fb.add(5, 1, "seq", "frame", value: .int(Int(frame[5])))

    let spec = schema.packet(forType: t)
    if spec == nil {
        fb.add(6, 1, "cmd", "cmd", value: frame.count > 6 ? .int(Int(frame[6])) : nil)
        if let length = length { fb.region(7, length, "payload", "unknown") }
    } else {
        // static fields from schema
        for fld in spec!.fields {
            guard let dtype = fld.dtype else { continue }
            guard let val = readDType(frame, fld.off, dtype) else { continue }
            let value: ParsedValue
            if let enumKey = fld.`enum` {
                value = .string(schema.enumName(enumKey, val))
            } else {
                value = .int(val)
            }
            fb.add(fld.off, fld.len, fld.name, fld.cat, value: value, note: fld.note)
        }
        // per-type post-hook for irregular fields (populated in PostHooks.swift by B7)
        if let postName = spec!.post, let hook = postHooks[postName] {
            hook(fb, frame, length, schema)
        }
    }

    // crc32 trailer field
    if let length = length, length + 4 <= frame.count {
        let crcVal = UInt32(frame[length]) | (UInt32(frame[length + 1]) << 8)
            | (UInt32(frame[length + 2]) << 16) | (UInt32(frame[length + 3]) << 24)
        fb.add(length, 4, "crc32", "frame", value: .string(String(format: "0x%08X", crcVal)),
               note: check.crc32OK == true ? "OK" : "MISMATCH")
    }

    let cmdByte = frame.count > 6 ? Int(frame[6]) : 0
    let cmdName = (t == 35 || t == 36) ? schema.enumName("CommandNumber", cmdByte) : nil

    return ParsedFrame(ok: true, typeName: typeName, seq: seq, cmdName: cmdName,
                       crcOK: crcOK, lenBytes: frame.count, rawHex: rawHex,
                       fields: fb.fields, parsed: fb.parsed)
}

/// Family-aware frame parsing.
///
/// `whoop4` behaves EXACTLY like the no-family `parseFrame(_:)` above (back-compat). `whoop5`
/// parses the Whoop 5.0 envelope (see `verifyFrame(_:family:)` for the layout): the SOF/length/
/// header-CRC live in the first 8 bytes, the inner `[type][seq][cmd][data…]` starts at offset 8,
/// and the 4-byte CRC32 trailer closes the frame. "Puffin" types 38/56 are aliased onto their base
/// names (COMMAND_RESPONSE / METADATA) via `canonicalTypeName`.
public func parseFrame(_ frame: [UInt8], family: DeviceFamily) -> ParsedFrame {
    switch family {
    case .whoop4:
        return parseFrame(frame)
    case .whoop5:
        return parseFrameWhoop5(frame)
    }
}

private func parseFrameWhoop5(_ frame: [UInt8]) -> ParsedFrame {
    let rawHex = frame.map { String(format: "%02x", $0) }.joined()
    // Minimum whoop5 frame: 8 header bytes + 1 inner (type) + 4 CRC32 trailer.
    if frame.count < 12 || frame[0] != 0xAA {
        return ParsedFrame(ok: false, typeName: "INVALID/FRAGMENT", seq: nil, cmdName: nil,
                           crcOK: nil, lenBytes: frame.count, rawHex: rawHex,
                           fields: [], parsed: [:])
    }

    let schema = loadSchema()
    let check = verifyFrame(frame, family: .whoop5)
    let declaredLength = check.length            // payload + 4 (CRC32)
    let crcOK = check.crc32OK

    // Inner record starts at offset 8: [type][seq][cmd][data…].
    let innerStart = 8
    let t = Int(frame[innerStart])
    let typeName = canonicalTypeName(t, schema: schema)
    let seq = frame.count > innerStart + 1 ? Int(frame[innerStart + 1]) : nil

    let fb = FieldBuilder(frame)
    // envelope
    fb.add(0, 1, "SOF", "frame", value: .string("0xAA"))
    fb.add(1, 1, "format", "frame", value: .int(Int(frame[1])))
    fb.add(2, 2, "length", "frame", value: declaredLength.map { .int($0) })
    fb.add(4, 2, "header", "frame", value: .string(hexFrameSlice(frame, 4, 6)))
    let hdrCRC = UInt16(frame[6]) | (UInt16(frame[7]) << 8)
    fb.add(6, 2, "crc16", "frame", value: .string(String(format: "0x%04X", hdrCRC)),
           note: check.crc8OK == true ? "OK" : "MISMATCH")
    fb.add(innerStart, 1, "packet_type", "frame", value: .string(typeName))
    if let seq = seq { fb.add(innerStart + 1, 1, "seq", "frame", value: .int(seq)) }

    // WHOOP 5.0 field offsets are the WHOOP 4.0 layout shifted by +4: the inner record starts at
    // byte 8 here vs byte 4 on 4.0, so every field sits at its 4.0 offset + `delta`. Verified on real
    // hardware for REALTIME_DATA (type 40) — HR, R-R and the unix timestamp land exactly at +4 (HR
    // matched the standard 2A37 profile to ~0.4 bpm). We reuse the 4.0 schema with that shift.
    let cmdByte = frame.count > innerStart + 2 ? Int(frame[innerStart + 2]) : 0
    let delta = innerStart - 4                       // = 4
    let payloadEnd = declaredLength.map { ($0 + 8) - 4 }   // start of CRC32 trailer
    let spec = schema.packet(forType: t)
    if spec == nil {
        fb.add(innerStart + 2, 1, "cmd", "cmd",
               value: frame.count > innerStart + 2 ? .int(cmdByte) : nil)
        if let payloadEnd = payloadEnd, innerStart + 3 < payloadEnd, payloadEnd <= frame.count {
            fb.region(innerStart + 3, payloadEnd, "payload", "unknown")
        }
    } else {
        // Static schema fields at the 4.0 offset + delta.
        for fld in spec!.fields {
            guard let dtype = fld.dtype, let val = readDType(frame, fld.off + delta, dtype) else { continue }
            let value: ParsedValue = fld.`enum`.map { .string(schema.enumName($0, val)) } ?? .int(val)
            fb.add(fld.off + delta, fld.len, fld.name, fld.cat, value: value, note: fld.note)
        }
        if spec!.post == "realtime_data" {
            // Verified variable-length extension: REALTIME_DATA R-R intervals (rr_count @13+delta,
            // intervals @14+delta…), the same shape as 4.0 shifted by +4.
            let rrn = readDType(frame, 13 + delta, "u8") ?? 0
            var rrs: [Int] = []
            for i in 0..<rrn {
                let off = 14 + delta + i * 2
                if let v = readDType(frame, off, "u16"), v > 0 {
                    fb.add(off, 2, "rr[\(i)]", "rr", value: .int(v), note: "ms")
                    rrs.append(v)
                }
            }
            fb.parsed["rr_intervals"] = .intArray(rrs)
        } else if spec!.post == "historical_data" {
            decodeWhoop5Historical(frame, fb: fb, payloadEnd: payloadEnd)
        } else if spec!.post == "metadata" {
            decodeWhoop5Metadata(frame, fb: fb)
        } else if spec!.post == "command_response" {
            decodeWhoop5CommandResponse(frame, fb: fb, schema: schema, payloadEnd: payloadEnd)
        } else if spec!.post == "event" {
            decodeWhoop5Event(frame, fb: fb, schema: schema)
        } else if let payloadEnd = payloadEnd, innerStart + 3 < payloadEnd, payloadEnd <= frame.count {
            // Other types: static fields decoded above; the remaining variable body is kept raw —
            // its 4.0 post-hook awaits per-type 5.0 hardware verification before we apply it at +4.
            fb.region(innerStart + 3, payloadEnd, "payload", "unknown")
        }
    }

    // crc32 trailer field
    if let payloadEnd = payloadEnd, payloadEnd + 4 <= frame.count {
        let crcVal = UInt32(frame[payloadEnd]) | (UInt32(frame[payloadEnd + 1]) << 8)
            | (UInt32(frame[payloadEnd + 2]) << 16) | (UInt32(frame[payloadEnd + 3]) << 24)
        fb.add(payloadEnd, 4, "crc32", "frame",
               value: .string(String(format: "0x%08X", crcVal)),
               note: check.crc32OK == true ? "OK" : "MISMATCH")
    }

    let cmdName = (t == 35 || t == 36 || t == PuffinPacketType.puffinCommandResponse)
        ? schema.enumName("CommandNumber", cmdByte) : nil

    return ParsedFrame(ok: true, typeName: typeName, seq: seq, cmdName: cmdName,
                       crcOK: crcOK, lenBytes: frame.count, rawHex: rawHex,
                       fields: fb.fields, parsed: fb.parsed)
}

/// Decode a WHOOP 5.0 HISTORICAL_DATA (type 47) DSP biometric record.
///
/// The layout version is carried in the byte at frame[9] — the inner record's seq slot, which the
/// historical packet reuses for its layout version exactly as WHOOP 4.0 does (version at frame[5],
/// +4 here). Real WHOOP 5 hardware on the latest firmware emits **version 18**, captured 2026-06-08
/// and unlocked via the HISTORICAL_DATA_RESULT chunk-ack handshake (see docs §5).
///
/// v18 is NOT the repo's 4.0 v24 layout shifted by +4 — that firmware revision is not what this
/// device emits, and a naive +4 decodes to garbage (HR 0, gravity overflow). Every offset below is
/// read directly off real frames at its absolute 5.0 position and cross-checked physiologically:
///   • unix monotonic at +1 s,  • rr_count matches the number of valid R-R intervals (100%),
///   • 60000/mean(R-R) ≈ heart_rate (88%, the rest being HR-averaging cases),  • |gravity| ≈ 1 g
///     (100% of 500 records).
/// PPG / SpO₂ / skin-temp live further in the 124-byte record but lack on-device ground truth, so
/// they are left as a raw region rather than guessed (project rule: real captures, never invented
/// offsets).
private func decodeWhoop5Historical(_ frame: [UInt8], fb: FieldBuilder, payloadEnd: Int?) {
    let version = frame.count > 9 ? Int(frame[9]) : -1
    fb.parsed["hist_version"] = .int(version)
    fb.add(9, 1, "hist_version", "meta", value: .int(version))
    if version == 26 {
        decodeWhoop5HistoricalV26(frame, fb: fb)
        return
    }
    if version == 20 || version == 21 {
        decodeWhoop5HistoricalV2021(frame, fb: fb, version: version, payloadEnd: payloadEnd)
        return
    }
    guard version == 18 else {
        // Unknown historical layout — describe it faithfully without inventing offsets.
        if let payloadEnd = payloadEnd, 11 < payloadEnd, payloadEnd <= frame.count {
            fb.region(11, payloadEnd, "HISTORICAL_DATA v\(version) (unmapped layout)", "unknown")
        }
        return
    }
    if let idx = readDType(frame, 11, "u32") {
        // A per-record counter: +1 every record and independent of unix (it advances across gaps);
        // observed identical on two straps. @11 is only the low byte — read the full u32 LE.
        fb.add(11, 4, "record_index", "meta", value: .int(idx), note: "per-record counter")
    }
    if let unix = readDType(frame, 15, "u32") {
        fb.add(15, 4, "unix", "time", value: .int(unix), note: "real unix seconds")
    }
    if let hr = readDType(frame, 22, "u8") {
        fb.add(22, 1, "heart_rate", "hr", value: .int(hr), note: "bpm")
    }
    let rrn = readDType(frame, 23, "u8") ?? 0
    fb.add(23, 1, "rr_count", "rr", value: .int(rrn))
    var rrs: [Int] = []
    for i in 0..<min(rrn, 4) {
        let off = 24 + i * 2
        if let v = readDType(frame, off, "u16"), v > 0 {
            fb.add(off, 2, "rr[\(i)]", "rr", value: .int(v), note: "ms")
            rrs.append(v)
        }
    }
    fb.parsed["rr_intervals"] = .intArray(rrs)
    // Bytes adjacent to the HR/R-R fields, read off real frames. @36 / 256 tracks the integer hr@22 to
    // sub-bpm (corr 0.989 over ~258k records) — a higher-precision heart rate; the others are raw.
    if let v = readDType(frame, 33, "u8") {
        fb.add(33, 1, "cardiac_flags", "cardiac", value: .int(v), note: "raw byte near the HR fields")
    }
    if let v = readDType(frame, 36, "u16") {
        fb.add(36, 2, "hr_fixed_8_8", "hr", value: .int(v), note: "higher-precision HR: bpm = value/256")
    }
    if let v = readDType(frame, 38, "u16") {
        fb.add(38, 2, "rr_packed", "rr", value: .int(v), note: "raw u16 near the R-R fields; meaning not pinned")
    }
    if let v = readDType(frame, 40, "u8") {
        fb.add(40, 1, "cardiac_status", "cardiac", value: .int(v), note: "raw status-like byte near the HR fields")
    }
    for (name, off) in [("gravity_x", 45), ("gravity_y", 49), ("gravity_z", 53)] {
        if let d = readF32(frame, off) {
            fb.add(off, 4, name, "accel", value: .double(d), note: "g")
        }
    }
    // Per-second fields beyond HR/gravity, each gated to a physically-real range and cross-validated
    // against real v18 frames (worn vs off-wrist), so a wrong offset on an unmapped layout stores
    // nothing rather than garbage (the data is the arbiter).
    if let d = readF32(frame, 41), d.isFinite, (0...8).contains(d) {
        fb.add(41, 4, "dynamic_acceleration", "accel", value: .double(d), note: "g, gravity-removed magnitude")
    }
    if let raw = readDType(frame, 57, "u16") {
        // Cumulative motion/step counter — monotonic across a stream (validated downstream), no midnight
        // reset. Single-frame value is unbounded so it carries no physical gate here.
        fb.add(57, 2, "step_motion_counter", "activity", value: .int(raw), note: "cumulative motion counter")
    }
    if let cad = readDType(frame, 59, "u8") {
        // A per-step cadence-like byte between the step counter and @63: never 0, and lower when moving
        // faster (still > walk > run in the data). Raw — no unit asserted.
        fb.add(59, 1, "step_cadence", "activity", value: .int(cad), note: "cadence-like byte (raw)")
    }
    if let wear = readDType(frame, 63, "u8"), (0...2).contains(wear) {
        fb.add(63, 1, "motion_wear_quality", "quality", value: .int(wear), note: "0=still/good, 1, 2=poor contact")
    }
    // @63 also reads as a small validated ACTIVITY-CLASS enum (community finding, #316): 0=still, 1=walk,
    // 2=run, 0xFF=invalid. A lightweight, no-cloud per-record activity readout that rides alongside the
    // step counter. Only the four known codes are surfaced — anything else (incl. 0xFF) stores nothing so
    // an unmapped firmware can't inject garbage.
    if let cls = readDType(frame, 63, "u8"), cls == 0 || cls == 1 || cls == 2 {
        fb.add(63, 1, "activity_class", "activity", value: .int(cls), note: "0=still, 1=walk, 2=run (0xFF=invalid)")
    }
    // Two auxiliary thermal channels just before skin_temp. Each is a signed i16 whose value/10 reads as
    // °C, tracks skin_temp@73 closely (corr ~0.92 and ~0.97 across the captured corpus) and follows the
    // same diurnal curve. Gated to a plausible thermal range so a wrong offset stores nothing.
    if let v = readI16(frame, 69), (0...60).contains(Double(v) / 10.0) {
        fb.add(69, 2, "temp_aux_1_raw", "temp", value: .int(v),
               note: "secondary temperature channel; °C = value/10; tracks skin_temp (corr ~0.92) with the same diurnal curve")
    }
    if let v = readI16(frame, 71), (0...60).contains(Double(v) / 10.0) {
        fb.add(71, 2, "temp_aux_2_raw", "temp", value: .int(v),
               note: "secondary temperature channel; °C = value/10; tracks skin_temp (corr ~0.97) with the same diurnal curve")
    }
    if let raw = readDType(frame, 73, "u16") {
        // Skin temperature from a digital skin-temperature sensor. Emitted as the RAW u16 register
        // (`skin_temp_raw`, consumed by the decode-features store) to stay scale-agnostic; °C = raw/100
        // is the divisor that yields physiological worn skin temps (median ~34 °C across two straps;
        // 30.6 °C worn / 22.5 °C ambient off-wrist on the test fixtures). The alternative /128 reads a
        // non-physiological ~27 °C worn (≈23.9 °C on this fixture) and is rejected. The on-wrist warming
        // curve after donning is a thermal signature nothing else in the record has. Gate on a plausible
        // thermal range so a wrong offset on an unmapped firmware stores nothing rather than garbage.
        let celsius = Double(raw) / 100.0
        if (5...45).contains(celsius) {
            fb.add(73, 2, "skin_temp_raw", "temp", value: .int(raw),
                   note: "raw register; °C = raw/100 (≈30.6 worn / ~22.5 ambient; on-wrist warming curve)")
        }
    }
    if let raw = readDType(frame, 75, "u16") {
        // A 16-bit status word. NOT a deep-sleep marker: across ~258k records its low nibble is 0 and it
        // occurs as often awake as asleep (the community "80 = deep" reading is a misread).
        fb.add(75, 2, "status_word", "status", value: .int(raw), note: "packed status word; not deep-sleep")
    }
    if let v = readDType(frame, 77, "u16") {
        fb.add(77, 2, "status_word_1", "status", value: .int(v),
               note: "raw; near-static sibling of status_word@75 (low nibble = 1)")
    }
    if let v = readDType(frame, 79, "u16") {
        fb.add(79, 2, "status_word_2", "status", value: .int(v),
               note: "raw; sibling of @75 (low nibble = 2)")
    }
    if let sb = readDType(frame, 81, "u8") {
        // High nibble (bits 4-5) tracks a scored night: 0 wake / 1 still / 2 asleep / 3 up; low nibble =
        // sub-flags. Deep/REM/light are computed off-band, not present here.
        let state = (sb >> 4) & 3
        fb.add(81, 1, "sleep_state", "sleep", value: .int(state),
               note: "0 wake/1 still/2 asleep/3 up (band state; not deep/REM/light)")
        fb.add(81, 1, "onwrist", "sleep", value: .int(sb & 3),
               note: "on-wrist/validity flag (b0-1)")
        fb.add(81, 1, "wake_quality", "sleep", value: .int((sb >> 2) & 3),
               note: "quality code (b2-3); observed nonzero only in wake")
    }
    if let v = readDType(frame, 82, "u8") {
        fb.add(82, 1, "aux_byte_82", "status", value: .int(v),
               note: "raw; observed nonzero only while sleep_state = asleep (meaning not pinned)")
    }
    if let d = readF32(frame, 113), d.isFinite {
        // A float32 at @113 (observed range ~ -5.3…0, 0 = unset); purpose unknown, carried raw.
        fb.add(113, 4, "unknown_f32_113", "aux", value: .double(d), note: "float32, purpose unknown")
    }
    // The remaining bytes (optical/perfusion + the unmapped tail) are not ground-truth-mapped; keep them
    // as one honest raw region.
    if let payloadEnd = payloadEnd, 82 < payloadEnd, payloadEnd <= frame.count {
        fb.region(82, payloadEnd, "unmapped (optical/perfusion + tail)", "unknown")
    }
}

/// Decode a WHOOP 5.0 type-47 **version-26** record — the high-rate optical PPG buffer.
///
/// Unlike the v18 per-second summary, v26 is a 24 Hz waveform: **24 little-endian i16 samples at bytes
/// [27:75]**, one record per second (`unix` u32 LE @15, the same slot v18 uses). It was verified to be
/// an OPTICAL PPG trace — not IMU/motion — using the heart rate as *internal* ground truth (no external
/// reference): the concatenated waveform's autocorrelation peaks at the HR (lag 14 = 102.9 bpm vs a
/// measured 101.7 bpm), trough-detection gives a 563 ms inter-beat interval (≈106 bpm), the pulse stays
/// HR-locked even when the wrist is still, and its amplitude is not motion-driven. (Reproduce with
/// `tools/linux-capture/analyze_v26_waveform.py`; see docs §5.)
///
/// The samples are raw AC-coupled ADC counts — PPG has no absolute unit — so they are exposed verbatim
/// as `ppg_waveform` with NO invented scale. The bytes before [27] (header + a block index) and the
/// footer after [75] are not mapped; SpO₂/skin-temp have no internal proxy and are left untouched.
private func decodeWhoop5HistoricalV26(_ frame: [UInt8], fb: FieldBuilder) {
    // Optical channel index @21: the strap time-multiplexes 26 optical channels, sweeping 1→26 in
    // ~40-frame blocks (one channel per block, revisited ~20 min later). Verified against a 22 h overnight
    // corpus — frame[21] takes exactly 26 distinct values (1–26), and on our own two real fixtures reads
    // 1 then 2. An earlier read at frame[12] (the 0x41/0x46 "two channels") was a high-entropy counter byte
    // mistaken for the channel during a short 2-burst capture. Gate to 1…26 so a wrong offset stores nothing.
    if let ch = readDType(frame, 21, "u8"), (1...26).contains(ch) {
        fb.add(21, 1, "ppg_channel", "ppg", value: .int(ch),
               note: "time-multiplexed optical channel 1–26 (40-frame blocks)")
    }
    // Record index @11 (u32): the same per-record counter the v18 and v20/v21 records already decode at this
    // offset (see those branches) — it just wasn't being read on v26.
    if let idx = readDType(frame, 11, "u32") {
        fb.add(11, 4, "record_index", "meta", value: .int(idx), note: "per-record counter")
    }
    if let unix = readDType(frame, 15, "u32") {
        fb.add(15, 4, "unix", "time", value: .int(unix), note: "real unix seconds")
    }
    // Segment value @19 (u16): constant across the records of one burst; its values are integer multiples of
    // ~327.68 (= 32768 / 100), i.e. it encodes an integer 0…99.
    if let seg = readDType(frame, 19, "u16") {
        fb.add(19, 2, "segment_id", "meta", value: .int(seg),
               note: "u16, constant across a burst; values are multiples of ~327.68 (encode an integer 0…99)")
    }
    // Further per-record optical fields around the waveform.
    if let v = readDType(frame, 23, "u16") {
        fb.add(23, 2, "frontend_meta", "optical", value: .int(v), note: "u16")
    }
    if let v = readDType(frame, 25, "u8") {
        fb.add(25, 1, "subchannel_index", "optical", value: .int(v), note: "u8 (0…7)")
    }
    if let d = readF32(frame, 75), d.isFinite {
        fb.add(75, 4, "signal_quality", "optical", value: .double(d), note: "float32")
    }
    if let v = readDType(frame, 79, "u16") {
        fb.add(79, 2, "subchannel_config", "optical", value: .int(v), note: "u16")
    }
    if let v = readDType(frame, 81, "u8") {
        fb.add(81, 1, "quality_flag_1", "optical", value: .int(v), note: "u8 (0/1)")
    }
    if let v = readDType(frame, 82, "u8") {
        fb.add(82, 1, "quality_flag_2", "optical", value: .int(v), note: "u8 (0/1)")
    }
    var samples: [Int] = []
    for off in stride(from: 27, to: 75, by: 2) {
        guard let v = readI16(frame, off) else { break }
        samples.append(v)
    }
    if !samples.isEmpty {
        fb.add(27, samples.count * 2, "ppg_waveform", "ppg", value: .intArray(samples),
               note: "optical PPG @24 Hz, LE-i16 ADC counts")
        fb.parsed["ppg_sample_count"] = .int(samples.count)
    }
}

/// Decode WHOOP 5.0 type-47 **version-20 / version-21** records — the bulk multi-channel sensor stream
/// the strap serves alongside the v18 per-second summary. These are large records (v20 = 2140 B, v21 =
/// 1244 B) that earlier builds could not decode (issue #344): the offload "completed" but stored nothing.
///
/// Both versions reuse the v18 record header — layout version @9, a marker byte @10 (0x81 on v20, 0x80 on
/// v21), the monotonic u32 record index @11 (the same lifetime counter as v18), and the u32 unix second
/// @15. Their bodies are blocks of fixed-length sample channels, established from captured frames:
///   • v21 (1244 B): a (100, 100, 3) descriptor near @22, then three 100-sample i16 channels at @28 /
///     @228 / @428 (200 B apart), each a bounded pulsatile waveform at its own DC baseline.
///   • v20 (2140 B): five channel blocks, each preceded by a presence byte (0x19 = active, 0x00 =
///     empty / zero-filled); an active block holds two 50-sample i32 channels. The presence bytes sit at
///     @0x1a / @0x1c0 / @0x366 / @0x50c / @0x6b2; the ten channel slots start at
///     @0x2f / 0xf7 / 0x1d5 / 0x29d / 0x37b / 0x443 / 0x521 / 0x5e9 / 0x6c7 / 0x78f.
///
/// Channels are exposed as raw sample arrays with NO invented scale or unit (an optical waveform has no
/// absolute unit). Which channel maps to which optical LED — and which carries motion — needs a labelled
/// capture (e.g. a deliberate moving window), so no per-channel identity is asserted here.
private func decodeWhoop5HistoricalV2021(_ frame: [UInt8], fb: FieldBuilder, version: Int, payloadEnd: Int?) {
    if frame.count > 10 {
        fb.add(10, 1, "layout_marker", "meta", value: .int(Int(frame[10])))
    }
    if let idx = readU32(frame, 11) {
        fb.add(11, 4, "record_index", "meta", value: .int(idx), note: "monotonic lifetime record index")
    }
    if let unix = readU32(frame, 15) {
        fb.add(15, 4, "unix", "time", value: .int(unix), note: "real unix seconds")
    }
    if version == 21 {
        // Three 100-sample i16 channels, 200 B apart.
        for (ch, start) in [(0, 28), (1, 228), (2, 428)] {
            var samples: [Int] = []
            for i in 0..<100 {
                guard let v = readI16(frame, start + i * 2) else { break }
                samples.append(v)
            }
            if samples.count == 100 {
                fb.add(start, 200, "optical_ch\(ch)", "ppg", value: .intArray(samples),
                       note: "raw i16 channel samples (no absolute unit)")
            }
        }
        fb.parsed["sensor_channel_samples"] = .int(100)
        return
    }
    // version == 20: five blocks of two 50-sample i32 channels, each block gated by a presence byte.
    let blocks: [(present: Int, ch0: Int, ch1: Int)] = [
        (0x1a, 0x2f, 0xf7), (0x1c0, 0x1d5, 0x29d), (0x366, 0x37b, 0x443),
        (0x50c, 0x521, 0x5e9), (0x6b2, 0x6c7, 0x78f),
    ]
    var present = 0
    for (b, blk) in blocks.enumerated() {
        guard frame.count > blk.present, frame[blk.present] != 0 else { continue }
        for (half, start) in [(0, blk.ch0), (1, blk.ch1)] {
            var samples: [Int] = []
            for i in 0..<50 {
                guard let v = readI32(frame, start + i * 4) else { break }
                samples.append(v)
            }
            if samples.count == 50 {
                fb.add(start, 200, "channel_b\(b)_\(half)", "sensor", value: .intArray(samples),
                       note: "raw i32 channel samples (no absolute unit)")
                present += 1
            }
        }
    }
    fb.parsed["sensor_channel_samples"] = .int(50)
    fb.parsed["sensor_channels_present"] = .int(present)
}

/// Decode WHOOP 5.0 METADATA (type 49) chunk fields so the historical-offload state machine can act
/// on them. `meta_type` is already added by the static-schema walk (4.0 @6 → 5.0 @10); a HISTORY_END
/// additionally carries the chunk's `unix` and `trim_cursor`, which `classifyHistoricalMeta` needs to
/// drive the `HISTORICAL_DATA_RESULT` ack. Offsets are the 4.0 metadata post-hook positions + 4,
/// verified on real WHOOP 5 HISTORY_END frames (trim decodes consistently across a whole capture).
/// `end_data` to echo back in the ack is `frame[21..29]` (trim u32 + next u32).
private func decodeWhoop5Metadata(_ frame: [UInt8], fb: FieldBuilder) {
    if let unix = readDType(frame, 11, "u32") { fb.add(11, 4, "unix", "time", value: .int(unix)) }
    if let ss = readDType(frame, 15, "u16") { fb.add(15, 2, "subsec", "time", value: .int(ss)) }
    if let trim = readDType(frame, 21, "u32") {
        fb.add(21, 4, "trim_cursor", "meta", value: .int(trim), note: "ack with this to advance")
    }
}

/// Build the WHOOP 5.0 historical-offload ack (`HISTORICAL_DATA_RESULT`, cmd 23) for one HISTORY_END
/// chunk. `endData` is the chunk's verbatim 8-byte trim block (`frame[21..29]`); the payload is
/// `[0x01] + endData`, framed as a puffin COMMAND. This is the WHOOP 5 image of `ackHistoricalChunk`
/// in `BLEManager`, and the byte-for-byte twin of the Python `build_history_ack` proven on hardware.
public func whoop5HistoricalAckFrame(endData: [UInt8], seq: UInt8) -> [UInt8] {
    puffinCommandFrame(cmd: 23, seq: seq, payload: [0x01] + endData)
}

/// Decode a WHOOP 5.0 COMMAND_RESPONSE (type 36) — battery %, history data-range, firmware version.
///
/// The response command is at frame[10] (the 4.0 frame[6] + 4) and its payload at frame[11]. WHOOP 5
/// reuses the 4.0 command NUMBERS, but the response PAYLOADS differ from 4.0 — so each field below is
/// mapped from a real WHOOP 5 capture (firmware 50.38.1.0), not ported on faith. Commands that return
/// a short stub on this firmware (REPORT_VERSION_INFO / GET_EXTENDED_BATTERY_INFO) or aren't served
/// (GET_CLOCK — unneeded, since realtime + historical carry real unix) are intentionally left undecoded.
private func decodeWhoop5CommandResponse(_ frame: [UInt8], fb: FieldBuilder, schema: Schema, payloadEnd: Int?) {
    guard let payloadEnd = payloadEnd, 11 < payloadEnd, payloadEnd <= frame.count else { return }
    let respCmd = Int(frame[10])
    let name = schema.enumName("CommandNumber", respCmd)   // e.g. "GET_BATTERY_LEVEL(26)"
    let pay = Array(frame[11..<payloadEnd])
    fb.region(11, payloadEnd, "response payload", "cmd")
    if name.hasPrefix("GET_BATTERY_LEVEL"), pay.count >= 3 {
        // Direct percent at pay[2] (47% confirmed against the app) — the 4.0 deci-percent ÷10 is gone.
        fb.add(11 + 2, 1, "battery_pct", "battery", value: .double(Double(pay[2])), note: "%")
    } else if name.hasPrefix("GET_DATA_RANGE"), pay.count >= 7 {
        // The long response carries record cursors + real-unix timestamps as 4-byte-aligned u32s from
        // pay[3]; the history window is their min/max. (A short ack response also exists — no
        // timestamps — so this no-ops on it.)
        var oldest = UInt32.max, newest: UInt32 = 0
        var o = 3
        while o + 4 <= pay.count {
            let v = UInt32(pay[o]) | (UInt32(pay[o + 1]) << 8) | (UInt32(pay[o + 2]) << 16) | (UInt32(pay[o + 3]) << 24)
            if v >= 1_600_000_000 && v <= 1_800_000_000 { oldest = min(oldest, v); newest = max(newest, v) }
            o += 4
        }
        if newest > 0 {
            fb.parsed["history_oldest"] = .int(Int(oldest))
            fb.parsed["history_newest"] = .int(Int(newest))
        }
    } else if respCmd == 145, pay.count >= 26 {
        // GET_HELLO info block. We surface the two user-facing fields the app shows — the device NAME
        // (the model-style label the strap calls itself) and the firmware VERSION — and deliberately never
        // read the session token (also in this response). Both offsets are anchored to a real
        // 50.38.1.0 capture: the name is printable ASCII at pay[16]; the version is 4 bytes at pay[93],
        // after the (fixed-width on this firmware) name+token region. Re-verify the version offset
        // across firmwares; the guards (printable name / pay[93]==50 "5.0" generation) fail closed.
        var nameBytes: [UInt8] = []
        var i = 16
        while i < pay.count, pay[i] != 0, (32...126).contains(pay[i]), nameBytes.count < 24 {
            nameBytes.append(pay[i]); i += 1
        }
        if nameBytes.count >= 6 {
            fb.parsed["device_name"] = .string(String(decoding: nameBytes, as: UTF8.self))
        }
        if pay.count >= 97, pay[93] == 50 {
            fb.parsed["fw_version"] = .string("\(pay[93]).\(pay[94]).\(pay[95]).\(pay[96])")
        }
    }
}

/// Decode a WHOOP 5.0 EVENT (type 48) per-event payload.
///
/// `event` (u8 @10, EventNumber) and `event_timestamp` (u32 @12, real unix) are already set by the
/// static +4 walk. This hook adds the BATTERY_LEVEL payload, which follows the same +4 rule as the
/// rest of the 5.0 layout: 4.0's soc@17 / mv@21 / charge@26 become soc@21 / mv@25 / charge@30. Unlike
/// the 5.0 COMMAND_RESPONSE (which switched to a DIRECT percent), the EVENT battery keeps 4.0's
/// deci-percent (÷10) — confirmed by a clean monotonic discharge across a real capture (49.9 → 47.7 %).
/// The same range guards as the 4.0 `event` post-hook fail closed.
///
/// Event NAMES come only from the shared `EventNumber` schema, so an unnamed number the firmware emits
/// (e.g. 123) stays the raw `0x7B(123)` set by the static walk and gets no payload here — never a name
/// borrowed from another enum (`CommandNumber` 123 is `SELECT_WRIST`) or invented. Other events'
/// payloads (EXTENDED_BATTERY_INFORMATION, STRAP_CONDITION_REPORT) lack on-device 5.0 ground truth and
/// are intentionally left raw rather than ported from 4.0 on faith.
private func decodeWhoop5Event(_ frame: [UInt8], fb: FieldBuilder, schema: Schema) {
    guard let evVal = readDType(frame, 10, "u8") else { return }
    guard schema.enums["EventNumber"]?[String(evVal)] == "BATTERY_LEVEL" else { return }
    if let raw = readDType(frame, 21, "u16"), raw <= 1100 {
        fb.add(21, 2, "battery_pct", "battery", value: .double(Double(raw) / 10), note: "%")
    }
    if let mv = readDType(frame, 25, "u16"), (3000...4300).contains(mv) {
        fb.add(25, 2, "battery_mV", "battery", value: .int(mv), note: "mV")
    }
    if let ch = readDType(frame, 30, "u8"), ch <= 1 {
        fb.add(30, 1, "battery_charging", "battery", value: .int(ch & 1))
    }
}

@inline(__always)
private func hexFrameSlice(_ f: [UInt8], _ start: Int, _ end: Int) -> String {
    guard start >= 0, end <= f.count, start < end else { return "" }
    return f[start..<end].map { String(format: "%02x", $0) }.joined()
}

// Post-hook registry (populated in PostHooks.swift by Task B7).
// name -> (FieldBuilder, frame, length, schema) -> Void
typealias PostHook = (FieldBuilder, [UInt8], Int?, Schema) -> Void
var postHooks: [String: PostHook] = [:]
