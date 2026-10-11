package com.dreamgirl.app.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dreamgirl.app.data.DayClock
import com.dreamgirl.app.data.DaySummaryEntity
import com.dreamgirl.app.data.MemoryItemEntity
import com.dreamgirl.app.domain.JsonUtil
import com.dreamgirl.app.ui.AppViewModels
import com.dreamgirl.app.ui.theme.SoftText
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    viewModel: MemoryViewModel = viewModel(factory = AppViewModels.Factory),
) {
    val summaries by viewModel.summaries.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<MemoryItemEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("她记得的事", fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (viewModel.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).padding(end = 4.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        TextButton(onClick = { viewModel.compressOld() }) { Text("压缩旧记") }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Text("每天一条总结，分三层：一句话 / 要点 / 细节。",
                            fontSize = 12.sp, color = SoftText)
                        Text(
                            "这些是她自己记的。新的一天开始时，她会把前一天聊过的事整理成一条，" +
                                "漏掉的也会补上 —— 你什么都不用点。",
                            fontSize = 12.sp, color = SoftText,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            viewModel.status?.let { s ->
                item {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(s, modifier = Modifier.weight(1f), fontSize = 13.sp)
                            TextButton(onClick = { viewModel.clearStatus() }) { Text("×") }
                        }
                    }
                }
            }

            if (summaries.isEmpty()) {
                item {
                    Text("还没有任何总结。和她聊几句，然后点「总结今天」。",
                        color = SoftText, fontSize = 13.sp)
                }
            }

            items(summaries, key = { it.date }) { s ->
                SummaryCard(
                    summary = s,
                    onDelete = { viewModel.deleteDay(s.date) },
                )
            }

            if (memories.isNotEmpty()) {
                item {
                    Spacer(Modifier.size(4.dp))
                    Text("记忆条目（${memories.size}）", fontWeight = FontWeight.Medium)
                }
                items(memories, key = { it.id }) { m ->
                    Card {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(m.content, fontSize = 14.sp)
                                Text(
                                    "${m.sourceDate} · L${m.layer} · 权重 ${"%.1f".format(m.weight)}" +
                                        if (m.tags.isNotBlank()) " · ${m.tags}" else "",
                                    fontSize = 11.sp, color = SoftText,
                                )
                            }
                            IconButton(onClick = { editing = m }) {
                                Icon(Icons.Filled.Edit, contentDescription = "改")
                            }
                            IconButton(onClick = { viewModel.deleteMemory(m) }) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = "删除")
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { item ->
        var text by remember(item.id) { mutableStateOf(item.content) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("改这条记忆") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateMemory(item, text)
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("算了") }
            },
        )
    }
}

@Composable
private fun SummaryCard(summary: DaySummaryEntity, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val l2 = remember(summary.date) { JsonUtil.parseStringArray(summary.l2Json) }
    val l3 = remember(summary.date) { JsonUtil.parseStringArray(summary.l3Json) }
    val date = runCatching { LocalDate.parse(summary.date) }.getOrNull()

    Card {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        date?.let { "${summary.date}（${DayClock.label(it)}）" } ?: summary.date,
                        fontSize = 12.sp, color = SoftText,
                    )
                    Text(summary.l1, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                    )
                }
            }
            if (expanded) {
                if (l2.isNotEmpty()) {
                    Spacer(Modifier.size(6.dp))
                    Text("要点", fontSize = 12.sp, color = SoftText)
                    l2.forEach { Text("· $it", fontSize = 13.sp) }
                }
                if (l3.isNotEmpty()) {
                    Spacer(Modifier.size(6.dp))
                    Text("细节", fontSize = 12.sp, color = SoftText)
                    l3.forEach { Text("· $it", fontSize = 13.sp) }
                }
                summary.mood?.let {
                    Spacer(Modifier.size(6.dp))
                    Text("状态：$it", fontSize = 12.sp, color = SoftText)
                }
                // 方案 §6.3：敏感内容"识别到就跳过并在 UI 上标注已跳过"。
                // 只显示条数，不显示内容 —— 否则跳过就没意义了。
                if (summary.skippedCount > 0) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "已跳过 ${summary.skippedCount} 条敏感内容（密码/卡号/证件号之类，不记）",
                        fontSize = 11.sp,
                        color = SoftText,
                    )
                }
                TextButton(onClick = onDelete) { Text("删掉这天", fontSize = 12.sp) }
            }
        }
    }
}
