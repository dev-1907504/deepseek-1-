package com.dreamgirl.app.ui.persona

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dreamgirl.app.data.PersonaEntity
import com.dreamgirl.app.ui.AppViewModels
import com.dreamgirl.app.ui.theme.SoftText

/**
 * 人格页（方案 §3 v1）：把 2.1 的设定做成可编辑表单。
 *
 * 出厂值就是方案原文；改了会存进 Room。页面顶部会明确告诉你「现在还是不是原文」——
 * 这是我们承诺「人格原文不被悄悄改动」的可见凭证。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaScreen(
    onBack: (() -> Unit)? = null,
    viewModel: PersonaViewModel = viewModel(factory = AppViewModels.Factory),
) {
    val draft = viewModel.draft
    var showResetConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("她是谁", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (draft == null) {
            Column(Modifier.fillMaxSize().padding(padding)) { }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VerbatimBadge(verbatim = viewModel.verbatim)

            // 显示名：界面上怎么叫她。和下面那七段原文是分开的，改它不会动人设。
            Column {
                Text("名字（界面上怎么叫她）", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                OutlinedTextField(
                    value = viewModel.displayName,
                    onValueChange = { viewModel.updateDisplayName(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("dream") },
                )
                Text(
                    "改这里只影响界面标题，不会改动下面的人设原文。",
                    fontSize = 11.sp,
                    color = SoftText,
                )
            }

            Field("名字（人设原文那句话）", draft.name) { v -> viewModel.edit { it.copy(name = v) } }
            Field("形象", draft.image, minLines = 3) { v -> viewModel.edit { it.copy(image = v) } }
            Field("性格", draft.character, minLines = 4) { v -> viewModel.edit { it.copy(character = v) } }
            Field("见地", draft.insight, minLines = 4) { v -> viewModel.edit { it.copy(insight = v) } }
            Field("信仰", draft.faith, minLines = 5) { v -> viewModel.edit { it.copy(faith = v) } }
            Field("说话方式", draft.speech, minLines = 5) { v -> viewModel.edit { it.copy(speech = v) } }
            Field("行为原则", draft.principles, minLines = 7) { v -> viewModel.edit { it.copy(principles = v) } }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = { viewModel.save() }, modifier = Modifier.weight(1f)) {
                    Text("保存")
                }
                TextButton(onClick = { showResetConfirm = true }, modifier = Modifier.weight(1f)) {
                    Text("恢复方案原文")
                }
            }

            if (showResetConfirm) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "恢复出厂会把上面七段全部换回方案 2.1 节的原文（逐字），你改过的内容会丢。",
                            fontSize = 13.sp,
                        )
                        Row {
                            TextButton(onClick = {
                                viewModel.resetToVerbatim(); showResetConfirm = false
                            }) { Text("确认恢复") }
                            TextButton(onClick = { showResetConfirm = false }) { Text("算了") }
                        }
                    }
                }
            }

            viewModel.savedAt?.let {
                Text("已保存", fontSize = 12.sp, color = SoftText)
            }
            Text(
                "提示：下面这七段出厂状态就是《设计方案》2.1 节的原文，一字未改；" +
                    "全部都可以改，改完点「保存」。想还原随时点「恢复方案原文」。" +
                    "「名字」是独立的显示项，不在原文里。",
                fontSize = 12.sp,
                color = SoftText,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun VerbatimBadge(verbatim: Boolean) {
    val color = if (verbatim) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Text(
            text = if (verbatim) {
                "✓ 与《设计方案》2.1 节逐字一致（未改动）"
            } else {
                "✎ 已被你改过，不再是方案原文"
            },
            modifier = Modifier.padding(12.dp),
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    minLines: Int = 1,
    onChange: (String) -> Unit,
) {
    Column {
        Text(label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = minLines,
            textStyle = MaterialTheme.typography.bodyMedium,
        )
    }
}
