package com.noop.wearbridge

import com.noop.BuildConfig

/**
 * PoC-only configuration for the phone->watch HR demo. This whole package is feature-branch
 * scaffolding, not a shipping feature. The PoC runs in debug builds only; release builds have
 * ENABLED=false so no TCP connection is ever attempted. The phone publishes to 127.0.0.1 because
 * the dev host relay is exposed on the device via `adb reverse tcp:8787 tcp:8787` (real phone) —
 * see docs/WEAR_POC.md.
 */
object WearPoc {
    enum class Transport { BLUETOOTH, TCP }

    val ENABLED = BuildConfig.DEBUG

    /** Selects the phone->watch transport. BLUETOOTH = real RFCOMM; TCP = emulator/dev fallback. */
    val TRANSPORT = Transport.BLUETOOTH

    // TCP fallback config (used only when TRANSPORT == TCP):
    const val HOST = "127.0.0.1"
    const val PORT = 8787
}
