package com.tennis.analyzer.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tennis.analyzer.data.ServeHistoryEntry
import com.tennis.analyzer.data.TrainingDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = TrainingDatabase.get(this).historyDao()
        setContent {
            val entries by dao.all().collectAsState(initial = emptyList())
            val selected = remember { mutableStateListOf<Long>() }

            Scaffold(
                containerColor = Color(0xFF101010),
                topBar = {
                    Column(Modifier.background(Color(0xFF101010)).fillMaxWidth().padding(16.dp, 36.dp, 16.dp, 8.dp)) {
                        Text("История подач", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (selected.isEmpty()) "Отметь две подачи для сравнения"
                            else "Выбрано: ${selected.size}/2",
                            color = Color(0xFF9E9E9E), fontSize = 13.sp
                        )
                    }
                },
                bottomBar = {
                    if (selected.isNotEmpty()) {
                        Button(
                            onClick = { openCompare(entries, selected) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7CB342)),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth().padding(16.dp).height(50.dp)
                        ) {
                            Text(
                                if (selected.size == 2) "⚖  Сравнить side-by-side" else "▶  Смотреть",
                                fontSize = 16.sp, color = Color.White
                            )
                        }
                    }
                }
            ) { pad ->
                if (entries.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                        Text("Пока нет записей.\nЗапиши и проанализируй подачу.",
                            color = Color(0xFF777777), fontSize = 15.sp)
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(pad),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(entries, key = { it.id }) { e ->
                            HistoryRow(
                                entry = e,
                                selected = selected.contains(e.id),
                                onToggle = {
                                    if (selected.contains(e.id)) selected.remove(e.id)
                                    else if (selected.size < 2) selected.add(e.id)
                                },
                                onDelete = { deleteEntry(e) }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun openCompare(entries: List<ServeHistoryEntry>, selected: List<Long>) {
        val paths = selected.mapNotNull { id -> entries.firstOrNull { it.id == id }?.videoPath }
        if (paths.isEmpty()) return
        startActivity(Intent(this, ComparisonActivity::class.java).apply {
            putExtra(ComparisonActivity.EXTRA_PATHS, paths.toTypedArray())
        })
    }

    private fun deleteEntry(e: ServeHistoryEntry) {
        val ctx = applicationContext
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching { File(e.videoPath).delete() }
            TrainingDatabase.get(ctx).historyDao().delete(e)
        }
    }
}

@androidx.compose.runtime.Composable
private fun HistoryRow(
    entry: ServeHistoryEntry,
    selected: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val df = remember { SimpleDateFormat("d MMM, HH:mm", Locale("ru")) }
    val sc = entry.score
    val scColor = when {
        sc >= 75 -> Color(0xFF66BB6A); sc >= 50 -> Color(0xFFFFC107); else -> Color(0xFFE53935)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1C1C1C))
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) Color(0xFF7CB342) else Color.Transparent,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable { onToggle() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(scColor.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text("$sc", color = scColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(df.format(Date(entry.createdMs)), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            entry.tip?.let {
                Text(it, color = Color(0xFFFFD54F), fontSize = 12.sp, maxLines = 2)
            }
        }
        Text("✕", color = Color(0xFF666666), fontSize = 18.sp,
            modifier = Modifier.clip(CircleShape).clickable { onDelete() }.padding(8.dp))
    }
}
