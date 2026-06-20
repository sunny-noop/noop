package com.noop.wear.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noop.wear.presentation.theme.NoopWearTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NoopWearTheme {
                val vm: WatchViewModel = viewModel()
                val snapshot by vm.snapshot.collectAsState()
                val linkUp by vm.linkUp.collectAsState()
                WatchScreen(snapshot, linkUp)
            }
        }
    }
}
