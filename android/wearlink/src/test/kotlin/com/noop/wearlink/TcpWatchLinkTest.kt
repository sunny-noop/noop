package com.noop.wearlink

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class TcpWatchLinkTest {

    @Test fun publisher_writesEncodedLine() {
        val server = ServerSocket(0)
        val received = ArrayBlockingQueue<String>(1)
        Thread {
            val c = server.accept()
            received.offer(BufferedReader(InputStreamReader(c.getInputStream())).readLine())
        }.apply { isDaemon = true; start() }

        val link = TcpWatchLink("127.0.0.1", server.localPort, WatchLinkRole.PUBLISHER)
        val snap = WatchSnapshot(hr = 65, connected = true)
        link.publish(snap)

        val line = received.poll(2, TimeUnit.SECONDS)
        assertEquals(snap, WatchSnapshotCodec.decodeOrNull(line!!))
        link.close(); server.close()
    }

    @Test fun subscriber_decodesIncomingLine() {
        val server = ServerSocket(0)
        Thread {
            val c = server.accept()
            c.getOutputStream().write((WatchSnapshotCodec.encode(WatchSnapshot(hr = 88)) + "\n").toByteArray())
            c.getOutputStream().flush()
        }.apply { isDaemon = true; start() }

        val got = ArrayBlockingQueue<WatchSnapshot>(1)
        val link = TcpWatchLink("127.0.0.1", server.localPort, WatchLinkRole.SUBSCRIBER, reconnectDelayMs = 50)
        link.observe { got.offer(it) }

        assertEquals(88, got.poll(2, TimeUnit.SECONDS)?.hr)
        link.close(); server.close()
    }

    @Test fun subscriber_reconnectsAfterDrop() {
        val server = ServerSocket(0)
        val got = ArrayBlockingQueue<WatchSnapshot>(4)
        Thread {
            val c1 = server.accept()
            c1.getOutputStream().write((WatchSnapshotCodec.encode(WatchSnapshot(hr = 1)) + "\n").toByteArray())
            c1.getOutputStream().flush()
            c1.close()
            val c2 = server.accept() // after the subscriber reconnects
            c2.getOutputStream().write((WatchSnapshotCodec.encode(WatchSnapshot(hr = 2)) + "\n").toByteArray())
            c2.getOutputStream().flush()
        }.apply { isDaemon = true; start() }

        val link = TcpWatchLink("127.0.0.1", server.localPort, WatchLinkRole.SUBSCRIBER, reconnectDelayMs = 50)
        link.observe { got.offer(it) }

        assertEquals(1, got.poll(2, TimeUnit.SECONDS)?.hr)
        assertEquals(2, got.poll(3, TimeUnit.SECONDS)?.hr)
        link.close(); server.close()
    }
}
