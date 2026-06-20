package com.noop.wearbridge

import com.noop.ble.LiveState
import com.noop.wearlink.WatchLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Observes the phone's LiveState and publishes each change to the watch via [link].
 * Lifecycle is owned by the caller (WhoopConnectionService): [start] on an existing scope,
 * [stop] from onDestroy. PoC-only.
 *
 * Single-use: stop() closes the link permanently; construct a new instance to restart.
 */
class WearBridge(
    private val state: StateFlow<LiveState>,
    private val link: WatchLink,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            state.collect { live -> link.publish(live.toWatchSnapshot(now())) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        link.close()
    }
}
