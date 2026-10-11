package com.dreamgirl.app.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dreamgirl.app.data.DayClock
import com.dreamgirl.app.ui.AppViewModels
import com.dreamgirl.app.ui.theme.SoftText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    viewModel: ReportViewModel = viewModel(factory = AppViewModels.Factory),
) {
    val r = viewModel.report

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("今天", fontWeight = FontWeight.SemiBold) },
                actions = { TextButton(onClick = { viewModel.refresh() }) { Text("刷新") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (r == null) {
                Text("正在算…", color = SoftText, fontSize = 13.sp)
                return@Column
            }

            // 常驻的「正在观察」指示（方案 §7-6）：一眼能看出她到底有没有在读你的作息
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (r.observing) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = if (r.observing) "◉ 正在观察" else "○ 未在观察",
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = if (r.observing) {
                            "她只在本地把你今天的用机情况压成一句话（比如「23:20 还在打游戏，已连打 95 分钟」），" +
                                "原始日志永远不会被发出去。不想让她看，去设置里一键关掉。"
                        } else {
                            "她没在读你的作息。要打开：设置 → 允许读取使用时长 → 授权 → 让她主动来找你。"
                        },
                        fontSize = 11.sp,
                        color = SoftText,
                    )
                }
            }

            if (!r.hasPermission) {
                Card(colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("还看不到你的用机情况", fontWeight = FontWeight.Medium)
                        Text(
                            "需要你手动开「使用情况访问」权限。开了之后，她才知道你是不是在熬夜。" +
                                "原始日志只在本地聚合成一句话，永远不会被发出去。",
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(DayClock.label(r.date), fontSize = 12.sp, color = SoftText)
                    Metric("今天亮屏", "${r.screenOnMinutes} 分钟")
                    Metric("当前连续使用", "${r.continuousMinutes} 分钟")
                    r.foregroundLabel?.let { Metric("现在在用", it) }
                    Metric("和她说了", "${r.messageCount} 条")
                    r.sleepAt?.let { Metric("最后一次熄屏", it) }
                    Metric("主动消息", if (r.proactiveEnabled) "已开" else "已关")
                }
            }

            if (r.topApps.isNotEmpty()) {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("哪个 App 最久", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        val max = r.topApps.maxOf { it.second }.coerceAtLeast(1)
                        r.topApps.forEach { (label, minutes) ->
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(label, fontSize = 13.sp)
                                    Text("$minutes 分钟", fontSize = 12.sp, color = SoftText)
                                }
                                LinearProgressIndicator(
                                    progress = { minutes.toFloat() / max },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }

            Text(
                "这些数字只在这个页面上给你看。她拿到的永远是本地拼好的一句话，" +
                    "比如「23:20 还在打游戏，已连打 95 分钟」。",
                fontSize = 11.sp, color = SoftText,
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 13.sp, color = SoftText)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
