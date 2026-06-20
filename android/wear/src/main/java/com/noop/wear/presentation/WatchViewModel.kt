package com.noop.wear.presentation

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noop.wear.BluetoothSubscriberLink
import com.noop.wear.WearPoc
import com.noop.wearlink.TcpWatchLink
import com.noop.wearlink.WatchLink
import com.noop.wearlink.WatchLinkRole
import com.noop.wearlink.WatchSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** No snapshot within this window → the phone link is treated as stale (dot goes grey). */
private const val LINK_STALE_MS = 5_000L

class WatchViewModel(app: Application) : AndroidViewModel(app) {

    private val link: WatchLink = when (WearPoc.TRANSPORT) {
        WearPoc.Transport.BLUETOOTH -> BluetoothSubscriberLink(app)
        WearPoc.Transport.TCP ->
            TcpWatchLink(WearPoc.HOST, WearPoc.PORT, WatchLinkRole.SUBSCRIBER)
    }

    private val _snapshot = MutableStateFlow<WatchSnapshot?>(null)
    val snapshot: StateFlow<WatchSnapshot?> = _snapshot

    /** Phone<->watch link health: true while fresh snapshots are arriving over the transport. */
    private val _linkUp = MutableStateFlow(false)
    val linkUp: StateFlow<Boolean> = _linkUp

    @Volatile private var lastRxElapsed = 0L

    init {
        link.observe { snap ->
            _snapshot.value = snap
            lastRxElapsed = SystemClock.elapsedRealtime()
            _linkUp.value = true
        }
        // Flip the indicator to grey when no snapshot has arrived within LINK_STALE_MS.
        viewModelScope.launch {
            while (true) {
                _linkUp.value = lastRxElapsed != 0L &&
                    SystemClock.elapsedRealtime() - lastRxElapsed < LINK_STALE_MS
                delay(1_000L)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        link.close()
    }
}
