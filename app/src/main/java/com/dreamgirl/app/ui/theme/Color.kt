package com.dreamgirl.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 微信配色。深浅两套，跟随系统 —— 和微信本身的行为一致。
 *
 * 为什么不用 MaterialTheme.colorScheme 直接搞定：
 * 气泡色（自己的绿、对方的白/深灰）在微信里是**固定的品牌色**，
 * 不随主题色变化，所以单独抽一层调色板。
 */
@Immutable
data class WxPalette(
    /** 聊天区 / 列表背景 */
    val background: Color,
    /** 顶栏 / 输入栏 / 会话列表行 */
    val panel: Color,
    /** 自己的气泡 */
    val bubbleMine: Color,
    /** 对方的气泡 */
    val bubbleHer: Color,
    val text: Color,
    val subText: Color,
    val divider: Color,
    val icon: Color,
    /** 微信绿：强调、选中、发送 */
    val green: Color,
    /** 快捷语小胶囊底 */
    val chip: Color,
    /** 输入框底 */
    val field: Color,
    /** 长按浮出的操作菜单底色（要比 panel 更亮一点才浮得起来） */
    val menu: Color,
    /** 危险操作（删除）*/
    val danger: Color,
)

/** 浅色：浅灰底 + 白气泡 + 微信绿。 */
val WxLightPalette = WxPalette(
    background = Color(0xFFEDEDED),
    panel = Color(0xFFF7F7F7),
    bubbleMine = Color(0xFF95EC69),
    bubbleHer = Color(0xFFFFFFFF),
    text = Color(0xFF191919),
    subText = Color(0xFF9A9A9A),
    divider = Color(0xFFE0E0E0),
    icon = Color(0xFF4C4C4C),
    green = Color(0xFF07C160),
    chip = Color(0xFFF2F2F2),
    field = Color(0xFFFFFFFF),
    menu = Color(0xFF4C4C4C),
    danger = Color(0xFFFA5151),
)

/** 深色：近黑底 + 深灰对方气泡 + 深绿自己气泡（对照你的微信截图取的色）。 */
val WxDarkPalette = WxPalette(
    background = Color(0xFF111111),
    panel = Color(0xFF191919),
    bubbleMine = Color(0xFF3EB575),
    bubbleHer = Color(0xFF2C2C2C),
    text = Color(0xFFD8D8D8),
    subText = Color(0xFF7F7F7F),
    divider = Color(0xFF2A2A2A),
    icon = Color(0xFFB2B2B2),
    green = Color(0xFF3EB575),
    chip = Color(0xFF2C2C2C),
    field = Color(0xFF2C2C2C),
    menu = Color(0xFF3A3A3A),
    danger = Color(0xFFFF6B6B),
)

val LocalWxPalette = staticCompositionLocalOf { WxLightPalette }

// ---------------------------------------------------------------- 她的颜色

/** 深蓝到浅蓝：她的发色，用在头像、启动页。 */
val DeepBlue = Color(0xFF0B2A4A)
val MidBlue = Color(0xFF1B4F8A)
val SeaBlue = Color(0xFF2E7DD1)
val SkyBlue = Color(0xFF8FC6F2)
val FoamWhite = Color(0xFFF7FBFF)
val InkText = Color(0xFF10233A)

/** 中间灰：深浅两套背景上都能读。 */
val SoftText = Color(0xFF8A8A8A)

val WhaleRose = Color(0xFFE86A8A)
val WarmSand = Color(0xFFF2C879)
