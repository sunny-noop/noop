package com.noop.wearlink

/** Direction a [WatchLink] endpoint plays on the wire. */
enum class WatchLinkRole { PUBLISHER, SUBSCRIBER }

/**
 * Transport-agnostic phone<->watch channel. The PoC uses [TcpWatchLink]; a Bluetooth RFCOMM
 * implementation is the hardware swap-in behind this same seam. An instance plays exactly one
 * role: a PUBLISHER honors [publish] (no-op [observe]); a SUBSCRIBER honors [observe] (no-op
 * [publish]). Newest-wins: the display only needs the latest value.
 */
interface WatchLink {
    fun publish(snapshot: WatchSnapshot)
    fun observe(onSnapshot: (WatchSnapshot) -> Unit)
    fun close()
}
