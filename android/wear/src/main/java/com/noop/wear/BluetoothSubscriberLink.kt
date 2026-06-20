package com.noop.wear

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.noop.wearlink.WatchLink
import com.noop.wearlink.WatchSnapshot
import com.noop.wearlink.WatchSnapshotCodec
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** App-specific RFCOMM service UUID — must match the phone's BluetoothPublisherLink. */
val WATCH_RFCOMM_UUID: UUID = UUID.fromString("7f3e2d1c-8b4a-4c9e-a1d6-0f5b2e9c3a47")
private const val TAG = "WearBtSubscriber"
private const val RECONNECT_DELAY_MS = 1500L

/**
 * Watch-side [WatchLink] over Bluetooth RFCOMM: connects out to the bonded phone offering the
 * [WATCH_RFCOMM_UUID] service and reads line-delimited JSON snapshots. SUBSCRIBER role only.
 * Requires an existing bond and BLUETOOTH_CONNECT (granted out-of-band for the PoC).
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT granted via adb for the PoC
class BluetoothSubscriberLink(context: Context) : WatchLink {

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val running = AtomicBoolean(true)
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var readThread: Thread? = null

    override fun observe(onSnapshot: (WatchSnapshot) -> Unit) {
        if (readThread != null) return
        readThread = Thread { loop(onSnapshot) }.apply { isDaemon = true; start() }
    }

    private fun loop(onSnapshot: (WatchSnapshot) -> Unit) {
        while (running.get()) {
            val s = connectToPhone()
            if (s != null) {
                try {
                    socket = s
                    val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
                    Log.d(TAG, "connected to ${s.remoteDevice?.address}")
                    while (running.get()) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        WatchSnapshotCodec.decodeOrNull(line)?.let(onSnapshot)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "read loop: ${e.message}")
                } finally {
                    closeQuietly()
                }
            }
            if (!running.get()) break
            try { Thread.sleep(RECONNECT_DELAY_MS) } catch (ie: InterruptedException) { break }
        }
    }

    /** Try each bonded device's RFCOMM service; the phone is the one that accepts. */
    private fun connectToPhone(): BluetoothSocket? {
        val a = adapter ?: run { Log.w(TAG, "no bluetooth adapter"); return null }
        for (device in a.bondedDevices.orEmpty()) {
            try {
                // NB: no cancelDiscovery() here — we connect to *bonded* devices, never scan, and
                // cancelDiscovery() requires the extra BLUETOOTH_SCAN permission (it throws before
                // connect() otherwise). RFCOMM connect to a bonded peer needs only BLUETOOTH_CONNECT.
                // Insecure RFCOMM to match the phone's insecure server — a companion-app bond often
                // won't carry a secure link key for third-party apps (secure connect → "read failed").
                val s = device.createInsecureRfcommSocketToServiceRecord(WATCH_RFCOMM_UUID)
                s.connect() // blocks; throws if this device isn't our server
                return s
            } catch (e: Exception) {
                Log.d(TAG, "connect ${device.address} failed: ${e.message}")
            }
        }
        return null
    }

    override fun publish(snapshot: WatchSnapshot) { /* subscriber role: no-op */ }

    override fun close() {
        running.set(false)
        readThread?.interrupt()
        closeQuietly()
    }

    private fun closeQuietly() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}
