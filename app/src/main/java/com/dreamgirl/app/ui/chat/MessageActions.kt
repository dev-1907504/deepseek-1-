package com.dreamgirl.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamgirl.app.data.MessageEntity
import com.dreamgirl.app.ui.theme.LocalWxPalette
import com.dreamgirl.app.ui.theme.WxPalette

/**
 * 长按气泡弹出的菜单 —— 微信那套：复制 / 引用 / 删除 / 多选。
 *
 * 做成**贴着气泡浮出来的一个小条**，而不是屏幕居中的大对话框：
 * 微信是这样，用户一眼就认；而且不遮住上下文，他知道自己在操作哪一句。
 */
@Composable
fun MessageActionMenu(
    mine: Boolean,
    canCopy: Boolean,
    onCopy: () -> Unit,
    onQuote: () -> Unit,
    onDelete: () -> Unit,
    onMultiSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wx = LocalWxPalette.current
    Surface(
        modifier = modifier,
        color = wx.menu,
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canCopy) {
                ActionItem("复制", Icons.Filled.ContentCopy, wx, onCopy)
            }
            ActionItem("引用", Icons.Filled.FormatQuote, wx, onQuote)
            ActionItem("多选", Icons.Filled.DoneAll, wx, onMultiSelect)
            ActionItem("删除", Icons.Filled.Delete, wx, onDelete, danger = true)
        }
    }
}

@Composable
private fun ActionItem(
    label: String,
    icon: ImageVector,
    wx: WxPalette,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    Column(
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (danger) wx.danger else wx.icon,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (danger) wx.danger else wx.icon,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 输入栏上方的引用条：显示"正在引用哪一句"，右边一个叉取消。
 */
@Composable
fun QuoteStrip(
    quote: MessageEntity,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wx = LocalWxPalette.current
    Surface(color = wx.panel, modifier = modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(color = wx.divider, thickness = 0.5.dp)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .size(width = 3.dp, height = 34.dp)
                        .background(wx.green),
                )
                Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        text = quoteAuthor(quote),
                        fontSize = 11.sp,
                        color = wx.green,
                    )
                    Text(
                        text = quotePreview(quote),
                        fontSize = 13.sp,
                        color = wx.subText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "取消引用",
                    tint = wx.subText,
                    modifier = Modifier.size(20.dp).clickable(onClick = onCancel),
                )
            }
        }
    }
}

/**
 * 多选模式下的顶部条：已选几条 + 全选 + 删除 + 退出。
 */
@Composable
fun MultiSelectBar(
    count: Int,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wx = LocalWxPalette.current
    Surface(color = wx.panel, modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "退出多选",
                    tint = wx.icon,
                    modifier = Modifier.size(22.dp).clickable(onClick = onCancel).padding(0.dp),
                )
                Text(
                    text = "已选 $count 条",
                    fontSize = 15.sp,
                    color = wx.text,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).padding(start = 16.dp),
                )
                Text(
                    text = "全选",
                    fontSize = 15.sp,
                    color = wx.green,
                    modifier = Modifier.clickable(onClick = onSelectAll).padding(horizontal = 12.dp),
                )
                Text(
                    text = "删除",
                    fontSize = 15.sp,
                    color = if (count > 0) wx.danger else wx.subText,
                    modifier = Modifier
                        .clickable(enabled = count > 0, onClick = onDelete)
                        .padding(horizontal = 12.dp),
                )
            }
            HorizontalDivider(color = wx.divider, thickness = 0.5.dp)
        }
    }
}

/**
 * 删完之后的撤销条。误删聊天记录很难受，所以给一次反悔的机会。
 */
@Composable
fun UndoBar(
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wx = LocalWxPalette.current
    Surface(color = wx.panel, modifier = modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(color = wx.divider, thickness = 0.5.dp)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.RestoreFromTrash,
                    contentDescription = null,
                    tint = wx.subText,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "已删除",
                    fontSize = 13.sp,
                    color = wx.text,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                Text(
                    text = "撤销",
                    fontSize = 14.sp,
                    color = wx.green,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(onClick = onUndo).padding(horizontal = 8.dp),
                )
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = wx.subText,
                    modifier = Modifier.size(16.dp).clickable(onClick = onDismiss),
                )
            }
        }
    }
}

/** 多选模式下每条消息左边的勾选框。 */
@Composable
fun SelectCheckbox(checked: Boolean, modifier: Modifier = Modifier) {
    val wx = LocalWxPalette.current
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (checked) wx.green else Color.Transparent)
            .then(
                if (checked) Modifier else Modifier.background(wx.chip, RoundedCornerShape(11.dp)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Filled.DoneAll,
                contentDescription = "已选",
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** 引用条上显示"谁说的"。 */
fun quoteAuthor(m: MessageEntity): String = if (m.role == "user") "我" else "她"

/** 引用条上的预览文字（媒体消息给个占位符）。 */
fun quotePreview(m: MessageEntity): String {
    if (m.content.isNotBlank()) return m.content.replace("\n", " ")
    return when (m.kind) {
        MessageEntity.KIND_IMAGE -> "[图片]"
        MessageEntity.KIND_VIDEO -> "[视频]"
        MessageEntity.KIND_FILE -> "[文件]"
        MessageEntity.KIND_STICKER -> "[表情]"
        else -> ""
    }
}
