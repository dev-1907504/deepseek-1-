package com.dreamgirl.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dreamgirl.app.domain.PromptAssembler
import com.dreamgirl.app.ui.AppViewModels
import com.dreamgirl.app.ui.theme.SoftText

/**
 * 「她实际收到的设定」——只读。
 *
 * 为什么值得做一个页面：用户说"她不像她"的时候，最容易出问题的就是
 * **实际发出去的那段字**。能直接看到它，反馈就从"感觉不对"变成"这里不对"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptViewerScreen(
    onBack: (() -> Unit)? = null,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModels.Factory),
) {
    val cfg by viewModel.settings.collectAsStateWithLifecycle()
    val assembler = remember { PromptAssembler() }
    // 只在称呼变化时重算（称呼是唯一会进入固定前缀的动态项）
    val prefix = remember(cfg.userAddress) { assembler.staticSystemPrefix(cfg.userAddress) }
    val charCount = prefix.length

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("她收到的设定", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text("这一整段是**固定前缀**（$charCount 字）", fontSize = 13.sp)
                    Text(
                        "它永远排在最前面、永远不变 —— 这既保证她的性格稳定，" +
                            "也命中 DeepSeek 的上下文缓存（便宜一个数量级）。" +
                            "下面这些字**一字不改**地来自《设计方案》2.1 节。",
                        fontSize = 11.sp,
                        color = SoftText,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("这段之后，每次请求还会追加：", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "① 最近 8 轮对话\n" +
                            "② 和这次话题相关的记忆（没命中就不加）\n" +
                            "③ 你刚说的那一句",
                        fontSize = 12.sp,
                        color = SoftText,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Card {
                Text(
                    text = prefix.ifEmpty { "（读取中…）" },
                    modifier = Modifier.padding(12.dp),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Text(
                "如果你觉得她不像，先看这里：多半是某一条 few-shot 或某句人设的理解偏了。",
                fontSize = 11.sp,
                color = SoftText,
            )
        }
    }
}
