package com.dreamgirl.app.ui.sessions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.dreamgirl.app.R
import com.dreamgirl.app.data.DayClock
import com.dreamgirl.app.data.MediaStore
import com.dreamgirl.app.data.SessionPreview
import com.dreamgirl.app.ui.AppViewModels
import com.dreamgirl.app.ui.theme.LocalWxPalette
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 会话列表 —— 参照 QQ「消息」那一屏。
 *
 * 顶部是**她的资料头**（头像可换、在线状态、当前心情、你的签名），
 * 下面一行一天，点进去就是那天的聊天记录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    onOpenDate: (LocalDate) -> Unit,
    viewModel: SessionListViewModel = viewModel(factory = AppViewModels.Factory),
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val wx = LocalWxPalette.current
    val today = DayClock.today()
    val context = LocalContext.current
    val scope = rememberCoroutineScopeCompat()
    var editing by remember { mutableStateOf(false) }

    // 换头像：选一张图 → 复制进私有目录 → 记住文件名
    val pickAvatar = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                MediaStore.importAvatar(context, uri)?.let { viewModel.setAvatarFile(it) }
            }
        }
    }

    Scaffold(
        containerColor = wx.panel,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(profile.displayName, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = wx.text)
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = wx.panel,
                    titleContentColor = wx.text,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(0.dp),
        ) {
            item(key = "profile") {
                HerProfileHeader(
                    profile = profile,
                    onPickAvatar = {
                        pickAvatar.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onEdit = { editing = true },
                )
                HorizontalDivider(color = wx.divider, thickness = 0.5.dp)
            }

            items(sessions, key = { it.sessionDate }) { row ->
                SessionRow(row = row, today = today) {
                    runCatching { LocalDate.parse(row.sessionDate) }.getOrNull()?.let(onOpenDate)
                }
                HorizontalDivider(
                    color = wx.divider,
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(start = 76.dp),
                )
            }
        }
    }

    if (editing) {
        EditProfileDialog(
            name = profile.displayName,
            signature = profile.signature,
            onDismiss = { editing = false },
            onSave = { n, s ->
                viewModel.setName(n)
                viewModel.setSignature(s)
                editing = false
            },
        )
    }
}

@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()

/**
 * 她的资料头。
 * 左边头像（点一下换），右边：名字 + 在线状态 + 当前心情 + 我的签名。
 */
@Composable
private fun HerProfileHeader(
    profile: HerProfile,
    onPickAvatar: () -> Unit,
    onEdit: () -> Unit,
) {
    val wx = LocalWxPalette.current
    val context = LocalContext.current
    val avatarModel = remember(profile.avatarFile) {
        profile.avatarFile?.let { MediaStore.avatarFile(context, it) } ?: R.drawable.avatar_her
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(wx.panel)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            if (avatarModel is java.io.File) {
                AsyncImage(
                    model = avatarModel,
                    contentDescription = "她的头像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(32.dp)).clickable(onClick = onPickAvatar),
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.avatar_her),
                    contentDescription = "她的头像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(32.dp)).clickable(onClick = onPickAvatar),
                )
            }
            // 在线/离线小圆点
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(wx.panel)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(if (profile.online) wx.green else wx.subText),
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.displayName,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = wx.text,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (profile.online) "在线 · 随时能说话" else "离线 · 去设置里配一下 API Key",
                fontSize = 12.sp,
                color = if (profile.online) wx.green else wx.subText,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "她的心情：${profile.mood}",
                fontSize = 12.sp,
                color = wx.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = profile.signature,
                fontSize = 11.sp,
                color = wx.subText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        TextButton(onClick = onEdit) { Text("编辑", color = wx.green, fontSize = 13.sp) }
    }
}

@Composable
private fun EditProfileDialog(
    name: String,
    signature: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var n by remember { mutableStateOf(name) }
    var s by remember { mutableStateOf(signature) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑资料") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = n, onValueChange = { n = it },
                    label = { Text("她的名字") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = s, onValueChange = { s = it },
                    label = { Text("我的签名（只给你看，不影响她）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(n, s) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SessionRow(row: SessionPreview, today: LocalDate, onClick: () -> Unit) {
    val wx = LocalWxPalette.current
    val date = runCatching { LocalDate.parse(row.sessionDate) }.getOrNull()
    val isToday = date == today

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(wx.panel)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.avatar_her),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = date?.let { DayClock.label(it, today) } ?: row.sessionDate,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = wx.text,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = previewText(row),
                fontSize = 13.sp,
                color = wx.subText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(text = trailingTime(row, isToday), fontSize = 11.sp, color = wx.subText)
    }
}

private fun previewText(row: SessionPreview): String = when {
    row.messageCount == 0 -> "还没说话，点进去找她"
    row.content.isBlank() -> "[媒体]"
    row.role == "user" -> row.content
    row.proactive -> "她主动找过你：${row.content}"
    else -> row.content
}

private val hhmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val shortDate: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")

private fun trailingTime(row: SessionPreview, isToday: Boolean): String {
    if (row.messageCount == 0) return ""
    val dt = Instant.ofEpochMilli(row.createdAt).atZone(ZoneId.systemDefault())
    return if (isToday) dt.toLocalTime().format(hhmm) else dt.toLocalDate().format(shortDate)
}
