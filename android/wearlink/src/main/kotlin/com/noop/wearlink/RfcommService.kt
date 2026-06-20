package com.noop.wearlink

/**
 * The app-specific RFCOMM SDP service the phone advertises and the watch connects to. Defined once
 * here (pure-JVM, no Android dep — it's just strings) so the phone server and watch client can never
 * drift to mismatched values. The Android transports call `UUID.fromString(RfcommService.UUID)`.
 */
object RfcommService {
    const val UUID = "7f3e2d1c-8b4a-4c9e-a1d6-0f5b2e9c3a47"
    const val SDP_NAME = "noop-wear"
}
