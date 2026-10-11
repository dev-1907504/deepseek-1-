package com.dreamgirl.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.dreamgirl.app.R
import com.dreamgirl.app.data.DayClock
import com.dreamgirl.app.data.MediaStore
import com.dreamgirl.app.data.MessageEntity
import com.dreamgirl.app.ui.AppViewModels
import com.dreamgirl.app.ui.theme.LocalWxPalette
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalClipboardManager
import com.dreamgirl.app.ui.theme.WxPalette
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Panel { NONE, EMOJI, PLUS, STICKER }

/**
 * 聊天页 —— 微信/QQ 对话界面。
 * 对方在左、自己在右；气泡带小尖角；时间戳居中且只在间隔超过 5 分钟时出现。
 * 底部输入栏：[表情] [输入框] [＋]，点加号可以发图片/视频/文件/表情包。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    initialDate: String? = null,
    onBack: (() -> Unit)? = null,
    viewModel: ChatViewModel = viewModel(factory = AppViewModels.Factory),
    modifier: Modifier = Modifier,
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val wx = LocalWxPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var preview by remember { mutableStateOf<File?>(null) }
    var stickers by remember { mutableStateOf<List<File>>(emptyList()) }

    // 长按菜单：当前正在操作哪一条（null = 没弹）
    var actionTarget by remember { mutableStateOf<MessageEntity?>(null) }
    val clipboard = LocalClipboardManager.current
    val herName = viewModel.displayName

    fun refreshStickers() { stickers = MediaStore.listStickers(context) }
    LaunchedEffect(panel) { if (panel == Panel.STICKER) refreshStickers() }

    // ---- 选图 / 选视频 / 选文件 / 加表情包 ----
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) scope.launch {
            val name = MediaStore.displayName(context, uri)
            MediaStore.importAttachment(context, uri)?.let {
                viewModel.sendMedia(MessageEntity.KIND_IMAGE, it, name)
            }
        }
    }
    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) scope.launch {
            val name = MediaStore.displayName(context, uri)
            MediaStore.importAttachment(context, uri)?.let {
                viewModel.sendMedia(MessageEntity.KIND_VIDEO, it, name)
            }
        }
    }
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            val name = MediaStore.displayName(context, uri)
            MediaStore.importAttachment(context, uri)?.let {
                viewModel.sendMedia(MessageEntity.KIND_FILE, it, name)
            }
        }
    }
    val addSticker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            MediaStore.importSticker(context, uri)
            refreshStickers()
        }
    }

    LaunchedEffect(initialDate) {
        val d = initialDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (d != null && d != selectedDate) viewModel.selectDate(d)
    }

    val streaming = viewModel.streamingText
    val rowCount = messages.size + if (streaming.isNotEmpty()) 1 else 0
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1
        }
    }
    LaunchedEffect(rowCount, streaming.length) {
        if (rowCount > 0 && atBottom) listState.scrollToItem(rowCount - 1)
    }

    Scaffold(
        modifier = modifier,
        containerColor = wx.background,
        topBar = {
            if (viewModel.selecting) {
                // 多选模式：顶栏变成操作条，不再显示标题
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(wx.panel).padding(horizontal = 8.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { viewModel.cancelSelecting() }) {
                            Icon(Icons.Filled.Close, contentDescription = "退出多选", tint = wx.icon)
                        }
                        Text(
                            text = "已选 ${viewModel.selectedIds.size} 条",
                            fontSize = 16.sp,
                            color = wx.text,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.selectAll(messages.map { it.id }) }) {
                            Text("全选", color = wx.green, fontSize = 15.sp)
                        }
                        TextButton(
                            onClick = { viewModel.deleteSelected() },
                            enabled = viewModel.selectedIds.isNotEmpty(),
                        ) {
                            Text(
                                "删除",
                                color = if (viewModel.selectedIds.isNotEmpty()) wx.danger else wx.subText,
                                fontSize = 15.sp,
                            )
                        }
                    }
                    HorizontalDivider(color = wx.divider, thickness = 0.5.dp)
                }
            } else CenterAlignedTopAppBar(
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = wx.icon)
                        }
                    }
                },
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(viewModel.displayName, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = wx.text)
                        Text(DayClock.label(selectedDate), fontSize = 11.sp, color = wx.subText)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.regenerate() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重说一遍", tint = wx.icon)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = wx.panel,
                    titleContentColor = wx.text,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            viewModel.errorMessage?.let { msg ->
                ErrorBanner(msg, onRetry = { viewModel.regenerate() }, onDismiss = { viewModel.dismissError() })
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(wx.background)
                    .clickable(enabled = panel != Panel.NONE) { panel = Panel.NONE },
            ) {
                if (messages.isEmpty() && streaming.isEmpty()) {
                    EmptyHint(modifier = Modifier.align(Alignment.Center))
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    itemsIndexed(messages, key = { _, m -> m.id }) { index, msg ->
                        if (showTimestamp(messages.getOrNull(index - 1), msg)) TimeDivider(formatTime(msg.createdAt))
                        // 长按菜单浮在这一条上；点别处关掉
                        // 她的内心独白：居中灰字小行，不是气泡（像系统消息）
                        if (msg.kind == MessageEntity.KIND_INNER) {
                            InnerThoughtRow(msg.content)
                        } else Box {
                            MessageRow(
                                msg = msg,
                                herAvatarFile = viewModel.avatarFile,
                                onOpenMedia = { preview = it },
                                selecting = viewModel.selecting,
                                selected = msg.id in viewModel.selectedIds,
                                onLongPress = { actionTarget = msg },
                                onToggleSelect = { viewModel.toggleSelected(msg.id) },
                                onDismissMenu = { actionTarget = null },
                            )
                            actionTarget?.takeIf { it.id == msg.id }?.let { target ->
                                MessageActionMenu(
                                    mine = target.role == "user",
                                    canCopy = target.content.isNotBlank(),
                                    onCopy = {
                                        clipboard.setText(AnnotatedString(target.content))
                                        actionTarget = null
                                    },
                                    onQuote = {
                                        viewModel.startQuote(target)
                                        actionTarget = null
                                    },
                                    onDelete = {
                                        viewModel.delete(target)
                                        actionTarget = null
                                    },
                                    onMultiSelect = {
                                        viewModel.startSelecting(target)
                                        actionTarget = null
                                    },
                                    modifier = Modifier.padding(
                                        start = if (target.role == "user") 0.dp else 56.dp,
                                        top = 34.dp,
                                    ),
                                )
                            }
                        }
                    }
                    if (streaming.isNotEmpty()) {
                        item(key = "streaming") {
                            MessageRow(
                                MessageEntity(
                                    id = -1,
                                    sessionDate = selectedDate.toString(),
                                    role = "assistant",
                                    content = streaming,
                                    createdAt = System.currentTimeMillis(),
                                ),
                                viewModel.avatarFile,
                                streaming = true,
                                onOpenMedia = {},
                                selecting = false,
                                selected = false,
                                onLongPress = {},
                                onToggleSelect = {},
                                onDismissMenu = {},
                            )
                        }
                    }
                }
            }

            // 引用条：正在引用哪一句
            viewModel.quoting?.let { q ->
                QuoteStrip(quote = q, onCancel = { viewModel.cancelQuote() })
            }

            // 撤销条：刚删掉一条
            viewModel.undoMessage?.let {
                UndoBar(
                    onUndo = { viewModel.undoDelete() },
                    onDismiss = { viewModel.dismissUndo() },
                )
            }

            viewModel.memoryHint?.let {
                Text(
                    text = it, fontSize = 11.sp, color = wx.subText, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                )
            }

            when (panel) {
                Panel.EMOJI -> EmojiPanel(onPick = { input += it })
                Panel.PLUS -> PlusPanel(
                    onImage = { panel = Panel.NONE; pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onVideo = { panel = Panel.NONE; pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                    onFile = { panel = Panel.NONE; pickFile.launch(arrayOf("*/*")) },
                    onStickers = { panel = Panel.STICKER },
                )

                Panel.STICKER -> StickerPanel(
                    stickers = stickers,
                    onAdd = { addSticker.launch(arrayOf("image/*")) },
                    onBack = { panel = Panel.PLUS },
                    onPick = { f ->
                        panel = Panel.NONE
                        viewModel.sendMedia(MessageEntity.KIND_STICKER, f.name, "表情")
                    },
                    onDelete = { f ->
                        MediaStore.deleteSticker(context, f.name)
                        refreshStickers()
                    },
                )

                Panel.NONE -> Unit
            }

            if (viewModel.selecting) {
                // 多选时不需要输入栏
            } else InputBar(
                value = input,
                onValueChange = { input = it },
                sending = viewModel.isSending,
                panel = panel,
                onToggleEmoji = { panel = if (panel == Panel.EMOJI) Panel.NONE else Panel.EMOJI },
                onTogglePlus = { panel = if (panel == Panel.PLUS) Panel.NONE else Panel.PLUS },
                onSend = {
                    val t = input
                    input = ""
                    panel = Panel.NONE
                    viewModel.send(t)
                },
            )
        }
    }

    preview?.let { f ->
        ImageViewer(file = f, onDismiss = { preview = null })
    }
}

// ------------------------------------------------------------------ 时间

private const val TIME_GAP_MS = 5 * 60_000L

private fun showTimestamp(previous: MessageEntity?, current: MessageEntity): Boolean {
    if (previous == null) return true
    if (previous.sessionDate != current.sessionDate) return true
    return current.createdAt - previous.createdAt > TIME_GAP_MS
}

/**
 * 她的内心独白 —— **居中灰字**，和系统消息同一档视觉。
 *
 * 这是"她这回没开口，但你看到了她心里那句"。
 * 它不是一个气泡：她没说出口，所以不该长得像一句话。
 */
@Composable
private fun InnerThoughtRow(text: String) {
    val wx = LocalWxPalette.current
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = wx.subText,
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = 48.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(wx.chip)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun TimeDivider(text: String) {
    val wx = LocalWxPalette.current
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(text = text, fontSize = 11.sp, color = wx.subText, modifier = Modifier.padding(vertical = 8.dp))
    }
}

// ------------------------------------------------------------------ 消息

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    msg: MessageEntity,
    herAvatarFile: String?,
    streaming: Boolean = false,
    onOpenMedia: (File) -> Unit,
    selecting: Boolean = false,
    selected: Boolean = false,
    onLongPress: () -> Unit = {},
    onToggleSelect: () -> Unit = {},
    onDismissMenu: () -> Unit = {},
) {
    if (msg.role == "assistant" && msg.content.isBlank() && msg.kind == MessageEntity.KIND_TEXT &&
        msg.errorKind == null && !streaming
    ) {
        return
    }

    val mine = msg.role == "user"
    val wx = LocalWxPalette.current
    val context = LocalContext.current
    val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.68f).dp
    val media = remember(msg.mediaPath) {
        MediaStore.attachmentFile(context, msg.mediaPath)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        // 多选模式：左边一个勾，整行都可点（微信就是这样，不用精准点到气泡）
        if (selecting) {
            SelectCheckbox(checked = selected)
            Spacer(modifier = Modifier.width(10.dp))
        }

        if (!mine) {
            HerAvatar(herAvatarFile)
            BubbleTail(mine = false)
        }

        // 气泡外面的手势层：长按弹菜单，多选模式下点击勾选，其它情况点一下收起菜单
        Box(
            modifier = Modifier.combinedClickable(
                onClick = {
                    onDismissMenu()
                    if (selecting) onToggleSelect()
                },
                onLongClick = { if (!selecting) onLongPress() },
            ),
        ) {
            Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                // 引用块：显示他/她回的是哪一句
                msg.quoteText?.takeIf { it.isNotBlank() }?.let { q ->
                    QuoteBlock(
                        author = quoteAuthor(msg),
                        text = q,
                        mine = mine,
                        maxWidth = maxBubble,
                    )
                }
                when (msg.kind) {
                    MessageEntity.KIND_STICKER -> StickerBubble(media)
                    MessageEntity.KIND_IMAGE -> MediaBubble(media, mine, isVideo = false, onOpen = onOpenMedia)
                    MessageEntity.KIND_VIDEO -> MediaBubble(media, mine, isVideo = true, onOpen = onOpenMedia)
                    MessageEntity.KIND_FILE -> FileBubble(media, msg.content, mine)
                    else -> TextBubble(
                        text = msg.content, mine = mine, maxWidth = maxBubble,
                        streaming = streaming, interrupted = msg.interrupted,
                    )
                }
            }
        }

        if (mine) {
            BubbleTail(mine = true)
            MyAvatar()
        }
    }
}

/**
 * 气泡上方那个小小的引用块（微信的样式：灰底、一条竖线、两行小字）。
 * 它不属于气泡本体，所以不参与气泡的尖角。
 */
@Composable
private fun QuoteBlock(author: String, text: String, mine: Boolean, maxWidth: androidx.compose.ui.unit.Dp) {
    val wx = LocalWxPalette.current
    Row(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(wx.chip)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(3.dp).height(28.dp).background(wx.green))
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text = author, fontSize = 11.sp, color = wx.green, maxLines = 1)
            Text(
                text = text,
                fontSize = 12.sp,
                color = wx.subText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HerAvatar(avatarFile: String?) {
    val context = LocalContext.current
    val model = remember(avatarFile) {
        avatarFile?.let { MediaStore.avatarFile(context, it) } ?: R.drawable.avatar_her
    }
    Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp))) {
        if (model is File) {
            AsyncImage(
                model = model, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        } else {
            Image(
                painter = painterResource(R.drawable.avatar_her), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun MyAvatar() {
    Image(
        painter = painterResource(R.drawable.ic_avatar_me),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
    )
}

@Composable
private fun BubbleTail(mine: Boolean) {
    val wx = LocalWxPalette.current
    val tailColor = if (mine) wx.bubbleMine else wx.bubbleHer
    Canvas(modifier = Modifier.padding(top = 13.dp).size(width = 5.dp, height = 9.dp)) {
        val path = Path().apply {
            if (mine) {
                moveTo(size.width, 0f); lineTo(0f, size.height / 2f); lineTo(size.width, size.height)
            } else {
                moveTo(0f, 0f); lineTo(size.width, size.height / 2f); lineTo(0f, size.height)
            }
            close()
        }
        drawPath(path, tailColor)
    }
}

@Composable
private fun TextBubble(
    text: String,
    mine: Boolean,
    maxWidth: Dp,
    streaming: Boolean = false,
    interrupted: Boolean = false,
) {
    val wx = LocalWxPalette.current
    if (Emoji.isEmojiOnly(text)) {
        // 纯 emoji 放大显示，像微信那样
        Text(text = text, fontSize = 34.sp, modifier = Modifier.padding(vertical = 2.dp))
        return
    }
    Surface(
        color = if (mine) wx.bubbleMine else wx.bubbleHer,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.widthIn(min = 40.dp, max = maxWidth),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(
                text = renderWithActions(text, wx),
                fontSize = 16.sp, lineHeight = 22.sp, color = wx.text,
            )
            if (streaming) Text("▍", fontSize = 13.sp, color = wx.green)
            if (interrupted) Text("（这句被打断了）", fontSize = 11.sp, color = wx.subText)
        }
    }
}

/** 图片 / 视频：圆角缩略图，点开看大图。 */
@Composable
private fun MediaBubble(file: File?, mine: Boolean, isVideo: Boolean, onOpen: (File) -> Unit) {
    val wx = LocalWxPalette.current
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.55f).dp
    Surface(
        color = if (mine) wx.bubbleMine else wx.bubbleHer,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.widthIn(min = 80.dp, max = maxW),
    ) {
        Box(
            modifier = Modifier
                .size(width = maxW, height = maxW * 0.75f)
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = !isVideo && file != null) { file?.let(onOpen) },
            contentAlignment = Alignment.Center,
        ) {
            if (file != null && !isVideo) {
                AsyncImage(
                    model = file, contentDescription = "图片",
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.PlayCircle, contentDescription = "视频",
                        tint = wx.icon, modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = file?.name?.take(18) ?: "视频已丢失",
                        fontSize = 11.sp, color = wx.subText,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FileBubble(file: File?, name: String, mine: Boolean) {
    val wx = LocalWxPalette.current
    val sizeLabel = file?.let { MediaStore.humanSize(it.length()) } ?: "已丢失"
    Surface(
        color = if (mine) wx.bubbleMine else wx.bubbleHer,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.widthIn(min = 120.dp, max = 240.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.InsertDriveFile, contentDescription = "文件",
                tint = wx.icon, modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name.ifBlank { file?.name ?: "文件" },
                    fontSize = 14.sp, color = wx.text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(text = sizeLabel, fontSize = 11.sp, color = wx.subText)
            }
        }
    }
}

/** 表情包：不套气泡，直接显示图片。 */
@Composable
private fun StickerBubble(file: File?) {
    if (file == null) {
        val wx = LocalWxPalette.current
        Surface(color = wx.chip, shape = RoundedCornerShape(6.dp), modifier = Modifier.size(80.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("表情已丢失", fontSize = 11.sp, color = wx.subText)
            }
        }
        return
    }
    AsyncImage(
        model = file, contentDescription = "表情",
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(110.dp).clip(RoundedCornerShape(8.dp)),
    )
}

@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    val wx = LocalWxPalette.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("（她一屁股坐在你旁边，尾巴翘着）", color = wx.subText, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Text("说吧，今天怎么了？", color = wx.subText, fontSize = 15.sp)
    }
}

/** 把 （动作/神态） 渲染成次要色斜体。 */
fun renderWithActions(text: String, palette: WxPalette): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val fullOpen = text.indexOf('（', i)
        val halfOpen = text.indexOf('(', i)
        val open = when {
            fullOpen < 0 -> halfOpen
            halfOpen < 0 -> fullOpen
            else -> minOf(fullOpen, halfOpen)
        }
        if (open < 0) { append(text.substring(i)); break }
        val fullClose = text.indexOf('）', open)
        val halfClose = text.indexOf(')', open)
        val close = when {
            fullClose < 0 -> halfClose
            halfClose < 0 -> fullClose
            else -> minOf(fullClose, halfClose)
        }
        if (close < 0) { append(text.substring(i)); break }
        if (open > i) append(text.substring(i, open))
        withStyle(SpanStyle(color = palette.subText, fontStyle = FontStyle.Italic)) {
            append(text.substring(open, close + 1))
        }
        i = close + 1
    }
}

// ------------------------------------------------------------------ 面板

@Composable
private fun EmojiPanel(onPick: (String) -> Unit) {
    val wx = LocalWxPalette.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(8),
        // 固定高度：网格才能自己滚动，不然会被父级量成无限高
        modifier = Modifier.fillMaxWidth().height(220.dp).background(wx.panel),
        contentPadding = PaddingValues(8.dp),
    ) {
        items(Emoji.common) { e ->
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).clickable { onPick(e) },
                contentAlignment = Alignment.Center,
            ) {
                Text(e, fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun PlusPanel(
    onImage: () -> Unit,
    onVideo: () -> Unit,
    onFile: () -> Unit,
    onStickers: () -> Unit,
) {
    val wx = LocalWxPalette.current
    @Suppress("UNUSED_EXPRESSION")
    Row(
        modifier = Modifier.fillMaxWidth().background(wx.panel).padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        PlusItem("图片", Icons.Filled.PhotoLibrary, wx.icon, onImage)
        PlusItem("视频", Icons.Filled.Movie, wx.icon, onVideo)
        PlusItem("文件", Icons.Filled.Description, wx.icon, onFile)
        PlusItem("表情包", Icons.Filled.Face, wx.icon, onStickers)
    }
}

@Composable
private fun PlusItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    val wx = LocalWxPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            color = wx.chip, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.size(52.dp).clickable(onClick = onClick),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 11.sp, color = wx.subText)
    }
}

@Composable
private fun StickerPanel(
    stickers: List<File>,
    onAdd: () -> Unit,
    onBack: () -> Unit,
    onPick: (File) -> Unit,
    onDelete: (File) -> Unit,
) {
    val wx = LocalWxPalette.current
    Column(modifier = Modifier.fillMaxWidth().height(260.dp).background(wx.panel)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回", color = wx.subText, fontSize = 13.sp) }
            Text(
                "我的表情包（${stickers.size}）",
                fontSize = 12.sp, color = wx.subText,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAdd) { Text("＋ 添加", color = wx.green, fontSize = 13.sp) }
        }
        if (stickers.isEmpty()) {
            Text(
                "还没有表情包。点「＋ 添加」从相册选几张图，之后你和她都能用。",
                fontSize = 12.sp, color = wx.subText,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                // 固定高度：网格自己滚动（外层高度已定，这里用 weight 吃掉剩余空间）
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                items(stickers) { f ->
                    Box(
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
                            .clickable { onPick(f) }
                            .padding(4.dp),
                    ) {
                        AsyncImage(
                            model = f, contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageViewer(file: File, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(color = Color.Black, shape = RoundedCornerShape(10.dp)) {
            AsyncImage(
                model = file, contentDescription = "大图",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp).clickable(onClick = onDismiss),
            )
        }
    }
}

// ------------------------------------------------------------------ 错误 / 输入

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val wx = LocalWxPalette.current
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message, modifier = Modifier.weight(1f), fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onRetry) { Text("重发", color = wx.green) }
            TextButton(onClick = onDismiss) { Text("知道了", color = wx.subText) }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    panel: Panel,
    onToggleEmoji: () -> Unit,
    onTogglePlus: () -> Unit,
    onSend: () -> Unit,
) {
    val wx = LocalWxPalette.current
    val canSend = value.isNotBlank() && !sending
    Surface(color = wx.panel) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            HorizontalDivider(color = wx.divider, thickness = 0.5.dp)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(onClick = onToggleEmoji) {
                    Icon(
                        Icons.Filled.SentimentSatisfied,
                        contentDescription = "表情",
                        tint = if (panel == Panel.EMOJI) wx.green else wx.icon,
                    )
                }
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("和她说点什么…", fontSize = 15.sp, color = wx.subText) },
                    maxLines = 5,
                    shape = RoundedCornerShape(6.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, color = wx.text),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = wx.field,
                        unfocusedContainerColor = wx.field,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                )
                IconButton(onClick = onTogglePlus) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "更多",
                        tint = if (panel == Panel.PLUS) wx.green else wx.icon,
                    )
                }
                if (value.isNotBlank()) {
                    Surface(
                        color = if (canSend) wx.green else wx.chip,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.clickable(enabled = canSend) { onSend() },
                    ) {
                        Box(
                            modifier = Modifier.height(48.dp).padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("发送", fontSize = 15.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun formatTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFormatter)
