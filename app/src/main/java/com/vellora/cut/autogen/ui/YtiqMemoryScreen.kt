package com.vellora.cut.autogen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vellora.cut.autogen.memory.ChannelMemory

/** What the AI has learned about the selected channel, and a way to wipe it. */
@Composable
fun YtiqMemoryScreen() {
    var version by remember { mutableStateOf(0) }
    val s = remember(version) { ChannelMemory.summary() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(text = "Memory", color = YtText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Searches", s.searches)
            Stat("Written", s.generations)
            Stat("Edits", s.edits)
            Stat("Results", s.withResults)
        }
        Spacer(modifier = Modifier.height(16.dp))

        Card("Playbook (given to the AI before it writes)") {
            Text(
                text = s.playbook.ifBlank { "Nothing yet. It grows as you research, generate and upload." },
                color = if (s.playbook.isBlank()) YtMuted else YtText,
                fontSize = 13.sp
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (s.recentSearches.isNotEmpty()) {
            Card("Recent searches") {
                s.recentSearches.forEach { q ->
                    Text(text = "• $q", color = YtText, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        OutlinedButton(onClick = { ChannelMemory.clear(); version++ }, shape = RoundedCornerShape(14.dp)) {
            Text(text = "Clear memory of this channel", color = YtMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Stat(label: String, value: Int) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(YtCard)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(text = value.toString(), color = YtText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(text = label, color = YtMuted, fontSize = 11.sp)
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(YtCard)
            .padding(16.dp)
    ) {
        Text(text = title, color = YtMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}
