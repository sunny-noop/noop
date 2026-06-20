package com.noop.wearbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.noop.wearlink.RfcommService
import com.noop.wearlink.WatchLink
import com.noop.wearlink.WatchSnapshot
import com.noop.wearlink.WatchSnapshotCodec
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "WearBtPublisher"

/**
 * Phone-side [WatchLink] over Bluetooth RFCOMM: a server socket that accepts the watch and writes
 * line-delimited JSON snapshots. PUBLISHER role only. Requires an existing phone<->watch bond and
 * the BLUETOOTH_CONNECT permission (granted out-of-band for the PoC).
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT granted via adb for the PoC
class BluetoothPublisherLink(context: Context) : WatchLink {

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val running = AtomicBoolean(true)
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var client: BluetoothSocket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var acceptThread: Thread? = null

    // Eager-listen: start the RFCOMM server as soon as the link exists, so the watch can connect (and
    // show connection state) before the first LiveState is published. publish() also calls this as a
    // safety net (no-op once the accept thread is running).
    init { ensureAccepting() }

    private fun ensureAccepting() {
        if (acceptThread != null || !running.get()) return
        val a = adapter ?: run { Log.w(TAG, "no bluetooth adapter"); return }
        acceptThread = Thread {
            while (running.get()) {
                try {
                    // Insecure RFCOMM: a Wear<->phone bond made via the companion app often does not
                    // expose an authenticated (secure) link key to third-party apps, so the secure
                    // server/connect pair fails with "read failed". Insecure works over the same bond.
                    val ss = a.listenUsingInsecureRfcommWithServiceRecord(
                        RfcommService.SDP_NAME, UUID.fromString(RfcommService.UUID),
                    )
                    serverSocket = ss
                    Log.d(TAG, "listening, awaiting watch…")
                    val c = ss.accept() // blocks until the watch connects
                    try { ss.close() } catch (_: Exception) {} // serve one client at a time
                    client = c
                    out = c.outputStream
                    Log.d(TAG, "watch connected: ${c.remoteDevice?.address}")
                    // Block on the client's input so a disconnect (EOF / IOException) is detected
                    // immediately — the watch never sends, so this parks until the socket closes.
                    // Replaces a poll loop + the unreliable BluetoothSocket.isConnected.
                    val input = c.inputStream
                    val sink = ByteArray(64)
                    while (running.get()) {
                        if (input.read(sink) < 0) break // -1 = watch closed the socket
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "accept loop: ${e.message}")
                } finally {
                    closeClientQuietly()
                }
                if (!running.get()) break
                try { Thread.sleep(1000) } catch (ie: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    override fun publish(snapshot: WatchSnapshot) {
        if (!running.get()) return
        ensureAccepting()
        val o = out ?: return // no watch yet — newest-wins, the next snapshot will reach it
        val line = WatchSnapshotCodec.encodeLine(snapshot)
        try {
            o.write(line.toByteArray(Charsets.UTF_8)); o.flush()
        } catch (e: Exception) {
            Log.d(TAG, "write failed, dropping client: ${e.message}")
            closeClientQuietly() // accept loop re-listens
        }
    }

    override fun observe(onSnapshot: (WatchSnapshot) -> Unit) { /* publisher role: no-op */ }

    override fun close() {
        running.set(false)
        acceptThread?.interrupt()
        try { serverSocket?.close() } catch (_: Exception) {}
        closeClientQuietly()
    }

    private fun closeClientQuietly() {
        try { client?.close() } catch (_: Exception) {}
        client = null
        out = null
    }
}
