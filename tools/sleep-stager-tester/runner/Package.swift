// swift-tools-version:5.9
import PackageDescription

// sleep-stager-cli — a tiny runner that stages a capture.json with either SleepStager (v1) or
// SleepStagerV2 (v2) and emits a hypnogram, so the two can be compared on real data. It depends on the
// StrandAnalytics package and uses its public API (SleepStager.detectSleep / SleepStagerV2.detectSleep)
// — no copied or symlinked sources. Opt-in developer tool: NOT part of the app's Packages workspace, so
// it has no effect on the app build or CI. See ../README.md.
//
// Builds on macOS. (StrandAnalytics pulls WhoopStore → Apple's Compression, which does not build on
// Linux — so this tool is macOS-only, matching where the app and its tests are built.)
let package = Package(
    name: "sleep-stager-cli",
    platforms: [.macOS(.v13)],
    dependencies: [
        .package(path: "../../../Packages/WhoopProtocol"),
        .package(path: "../../../Packages/StrandAnalytics"),
    ],
    targets: [
        .executableTarget(name: "sleep-stager-cli", dependencies: ["WhoopProtocol", "StrandAnalytics"]),
    ]
)
