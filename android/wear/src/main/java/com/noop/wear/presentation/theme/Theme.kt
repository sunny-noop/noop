package com.noop.wear.presentation.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.MaterialTheme

@Composable
fun NoopWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
