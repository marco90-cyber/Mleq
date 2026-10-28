package com.marco.mleq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marco.mleq.util.AppLog

@Composable
fun LogScreen(env: AppEnv) {
    val rev = AppLog.revision
    val lines = AppLog.snapshot()
    val listState = rememberLazyListState()

    LaunchedEffect(rev) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0C0E))
            .safeDrawingPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { env.overlay = Overlay.None }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro", tint = Color.White)
            }
            Text(
                "Log (${lines.size})", color = Color.White, fontSize = 18.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { env.actions?.shareLog() }) {
                Icon(Icons.Filled.Share, contentDescription = "Condividi", tint = Color.White)
            }
            IconButton(onClick = { AppLog.clear() }) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = "Cancella", tint = Color.White)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(lines) { _, line ->
                val color = when {
                    line.contains(" E/") -> Color(0xFFFF6B6B)
                    line.contains(" W/") -> Color(0xFFFFC857)
                    line.contains(" D/") -> Color(0xFF8E96A8)
                    else -> Color(0xFFDCE1EC)
                }
                Text(line, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
