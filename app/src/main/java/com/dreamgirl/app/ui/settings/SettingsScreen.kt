package com.dreamgirl.app.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dreamgirl.app.ui.AppViewModels
import com.dreamgirl.app.ui.theme.SoftText
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenPersona: (() -> Unit)? = null,
    onOpenPrompt: (() -> Unit)? = null,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModels.Factory),
) {
    val cfg by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var keyInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置", fontWeight = FontWeight.SemiBold) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------------- API Key ----------------
            Section("DeepSeek API Key") {
                Text(
                    text = viewModel.maskedKey?.let { "已保存：$it" } ?: "还没配置",
                    fontSize = 13.sp,
                    color = if (viewModel.maskedKey != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("粘贴新的 Key（sk-…）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.saveKey(keyInput); keyInput = "" },
                        enabled = keyInput.isNotBlank(),
                    ) { Text("保存") }
                    TextButton(onClick = { viewModel.testConnection() }) { Text("测试连接") }
                    if (viewModel.maskedKey != null) {
                        TextButton(onClick = { viewModel.clearKey() }) { Text("删除") }
                    }
                }
                when (val t = viewModel.testState) {
                    is SettingsViewModel.TestState.Running -> Text("正在连…", fontSize = 12.sp, color = SoftText)
                    is SettingsViewModel.TestState.Ok -> Text(
                        "✓ 连接正常（${t.ms}ms）", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    is SettingsViewModel.TestState.Fail -> Text(
                        "✗ ${t.message}", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )

                    SettingsViewModel.TestState.Idle -> Unit
                }
                Text(
                    "Key 只存在本机加密容器里（Android Keystore + AES256-GCM），" +
                        "不会打进 APK、不会上传到任何地方。",
                    fontSize = 11.sp, color = SoftText,
                )
            }

            // ---------------- 模型参数 ----------------
            Section("模型与语气") {
                OutlinedTextField(
                    value = cfg.baseUrl,
                    onValueChange = { viewModel.setBaseUrl(it) },
                    label = { Text("接口地址") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = cfg.chatModel,
                    onValueChange = { viewModel.setChatModel(it) },
                    label = { Text("模型") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                SwitchRow(
                    title = "遇到难题时切到推理模型",
                    subtitle = "只在你说「怎么办 / 该不该 / 帮我分析」这类话时启用；" +
                        "日常闲聊刻意不用，否则她会变得又慢又爱讲道理",
                    checked = cfg.useReasonerForHardQuestions,
                    onCheckedChange = { viewModel.setUseReasoner(it) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("温度 ${"%.2f".format(cfg.temperature)}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("人格要活一点就往 1.2–1.4 试", fontSize = 11.sp, color = SoftText)
                }
                Slider(
                    value = cfg.temperature.toFloat(),
                    onValueChange = { viewModel.setTemperature(it.toDouble()) },
                    valueRange = 0.3f..1.5f,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("回复上限 ${cfg.maxTokens} token", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("她的人设要求「不写小作文」", fontSize = 11.sp, color = SoftText)
                }
                Slider(
                    value = cfg.maxTokens.toFloat(),
                    onValueChange = { viewModel.setMaxTokens(it.roundToInt()) },
                    valueRange = 100f..800f,
                    steps = 13,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("单条最长 ${cfg.replyCharLimit} 字", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("嫌她啰嗦就往下拧", fontSize = 11.sp, color = SoftText)
                }
                Slider(
                    value = cfg.replyCharLimit.toFloat(),
                    onValueChange = { viewModel.setReplyCharLimit(it.roundToInt()) },
                    valueRange = 150f..800f,
                    steps = 12,
                )
            }

            // ---------------- 称呼 ----------------
            Section("她怎么叫你") {
                OutlinedTextField(
                    value = cfg.userAddress,
                    onValueChange = { viewModel.setUserAddress(it) },
                    label = { Text("称呼（默认「你」）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            // ---------------- 感知与主动 ----------------
            Section("感知与主动") {
                SwitchRow(
                    title = "让她主动来找你",
                    subtitle = "熬夜 / 久坐 / 好久没聊，她会自己开口",
                    checked = cfg.proactiveEnabled,
                    onCheckedChange = { viewModel.setProactive(it) },
                )
                SwitchRow(
                    title = "允许读取使用时长",
                    subtitle = "只在本机聚合成一句话，原始日志永不外发",
                    checked = cfg.usageStatsEnabled,
                    onCheckedChange = {
                        viewModel.setUsageStats(it)
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { openUsageAccess(context) }) { Text("去授权「使用情况访问」") }
                }
                TextButton(onClick = { openBatterySettings(context) }) { Text("加进电池白名单（防被杀）") }
                SwitchRow(
                    title = "通知",
                    subtitle = "她主动说话时，发一条通知栏消息",
                    checked = cfg.notificationsEnabled,
                    onCheckedChange = { viewModel.setNotifications(it) },
                )
                Text(
                    "通知感知和读屏是 v3 的功能，默认关闭，这一版没有打开。",
                    fontSize = 11.sp, color = SoftText,
                )
            }

            if (onOpenPrompt != null) {
                Section("她收到的设定") {
                    Text(
                        "看看模型实际收到的提示词长什么样 —— 人设原文、few-shot、" +
                            "以及每次请求会追加什么。",
                        fontSize = 12.sp,
                        color = SoftText,
                    )
                    Button(onClick = onOpenPrompt) { Text("查看提示词") }
                }
            }

            if (onOpenPersona != null) {
                Section("她是谁") {
                    Text(
                        "人设原文、few-shot、说话方式都在这里。出厂就是《设计方案》2.1 节的原文。",
                        fontSize = 12.sp,
                        color = SoftText,
                    )
                    Button(onClick = onOpenPersona) { Text("打开人格设定") }
                }
            }

            Section("关于") {
                Text("${cfg.displayName} · 独立聊天 App", fontSize = 13.sp)
                Text(
                    "她的人格设定逐字取自《设计方案》2.1 节，出厂不改一字。" +
                        "所有对话、总结、记忆都只存在这台手机的本地数据库里。",
                    fontSize = 11.sp, color = SoftText,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.sp, color = SoftText)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun openUsageAccess(context: Context) {
    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
}

private fun openBatterySettings(context: Context) {
    val pkg = context.packageName
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData("package:$pkg".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
