package com.noop.wear

/**
 * PoC-only config for the watch subscriber. Default HOST is the emulator's host-loopback
 * (10.0.2.2) for a Wear AVD. For a REAL watch over `adb reverse tcp:8787 tcp:8787`, change
 * HOST to "127.0.0.1". See docs/WEAR_POC.md.
 */
object WearPoc {
    enum class Transport { BLUETOOTH, TCP }

    /** Selects the phone->watch transport. BLUETOOTH = real RFCOMM; TCP = emulator/dev fallback. */
    val TRANSPORT = Transport.BLUETOOTH

    // TCP fallback config (used only when TRANSPORT == TCP):
    const val HOST = "10.0.2.2"
    const val PORT = 8787
}
