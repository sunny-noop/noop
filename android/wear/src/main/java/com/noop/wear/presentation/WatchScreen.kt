package com.noop.wear.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.noop.wearlink.WatchSnapshot

private val LINK_LIVE = Color(0xFF34C759)  // green: fresh data from the phone
private val LINK_STALE = Color(0xFF8E8E93) // grey: link stale / waiting for phone

@Composable
fun WatchScreen(snapshot: WatchSnapshot?, linkUp: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Phone-link indicator: green while fresh snapshots arrive, grey when stale/waiting.
        Text(
            text = "●",
            color = if (linkUp) LINK_LIVE else LINK_STALE,
            style = MaterialTheme.typography.title3,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        if (snapshot == null) {
            Text("Waiting for phone…", textAlign = TextAlign.Center)
            return@Column
        }
        Text(
            text = snapshot.hr?.toString() ?: "—",
            style = MaterialTheme.typography.display1,
        )
        Text(text = "bpm", style = MaterialTheme.typography.caption2)
        Text(text = connectionLabel(snapshot), style = MaterialTheme.typography.caption1)
        snapshot.batteryPct?.let { Text(text = "Strap ${it.toInt()}%", style = MaterialTheme.typography.caption2) }
        Text(text = lastSyncLabel(snapshot.lastSyncAt), style = MaterialTheme.typography.caption2)
    }
}

private fun connectionLabel(s: WatchSnapshot): String = when {
    s.backfilling -> "Syncing…"
    s.bonded -> "Connected"
    s.connected -> "Live HR"
    else -> "Disconnected"
}

private fun lastSyncLabel(lastSyncAt: Long?): String {
    if (lastSyncAt == null) return "Never synced"
    val agoSec = (System.currentTimeMillis() / 1000L) - lastSyncAt
    return when {
        agoSec < 0 -> "Synced just now"
        agoSec < 60 -> "Synced ${agoSec}s ago"
        agoSec < 3600 -> "Synced ${agoSec / 60}m ago"
        else -> "Synced ${agoSec / 3600}h ago"
    }
}
