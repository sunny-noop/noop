package com.noop.wearlink

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

private const val DEFAULT_CONNECT_TIMEOUT_MS = 3000

/**
 * Line-delimited JSON over a plain TCP socket — the GMS-free PoC transport. Both endpoints
 * connect to a shared relay (see tools/wear-poc/relay.py). PUBLISHER writes one JSON line per
 * [publish]; SUBSCRIBER runs a daemon read-loop that decodes lines and reconnects with a fixed
 * backoff after any drop. [connector] is injectable so tests drive it against a loopback server.
 */
class TcpWatchLink(
    private val host: String,
    private val port: Int,
    private val role: WatchLinkRole,
    private val reconnectDelayMs: Long = 1000L,
    private val connector: (String, Int) -> Socket = { h, p ->
        Socket().apply { connect(InetSocketAddress(h, p), DEFAULT_CONNECT_TIMEOUT_MS) }
    },
) : WatchLink {

    // Threading: a PUBLISHER's publish() runs on the caller's thread; a SUBSCRIBER owns one daemon
    // read thread. `running` (atomic) is the single shutdown signal; `socket`/`out`/`readThread` are
    // @Volatile for cross-thread visibility. The contract assumes one role and a single publish caller.
    private val running = AtomicBoolean(true)
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var onSnapshot: ((WatchSnapshot) -> Unit)? = null
    @Volatile private var readThread: Thread? = null

    override fun publish(snapshot: WatchSnapshot) {
        if (role != WatchLinkRole.PUBLISHER || !running.get()) return
        ensurePublisherConnected()
        val line = WatchSnapshotCodec.encodeLine(snapshot)
        try {
            out?.apply { write(line.toByteArray(Charsets.UTF_8)); flush() }
        } catch (e: Exception) {
            closeSocketQuietly() // reconnect on the next publish
        }
    }

    private fun ensurePublisherConnected() {
        val s = socket
        if (s != null && s.isConnected && !s.isClosed) return
        try {
            val ns = connector(host, port)
            if (!running.get()) {
                // close() raced us between the publish() running-check and here — don't leak the socket.
                try { ns.close() } catch (_: Exception) {}
                return
            }
            socket = ns
            out = ns.getOutputStream()
        } catch (e: Exception) {
            closeSocketQuietly()
        }
    }

    override fun observe(onSnapshot: (WatchSnapshot) -> Unit) {
        if (role != WatchLinkRole.SUBSCRIBER) return
        this.onSnapshot = onSnapshot
        if (readThread == null) {
            readThread = Thread { subscriberLoop() }.apply { isDaemon = true; start() }
        }
    }

    private fun subscriberLoop() {
        while (running.get()) {
            try {
                val s = connector(host, port)
                socket = s
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    WatchSnapshotCodec.decodeOrNull(line)?.let { onSnapshot?.invoke(it) }
                }
            } catch (e: Exception) {
                // fall through to reconnect
            }
            closeSocketQuietly()
            if (!running.get()) break
            try {
                Thread.sleep(reconnectDelayMs)
            } catch (ie: InterruptedException) {
                break
            }
        }
    }

    override fun close() {
        running.set(false)
        readThread?.interrupt()
        closeSocketQuietly()
    }

    private fun closeSocketQuietly() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        out = null
    }
}
