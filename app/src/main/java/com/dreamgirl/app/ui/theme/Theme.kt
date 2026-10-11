package com.dreamgirl.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = WxLightPalette.green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6F5E3),
    onPrimaryContainer = Color(0xFF075E3A),
    secondary = MidBlue,
    onSecondary = FoamWhite,
    background = WxLightPalette.background,
    onBackground = WxLightPalette.text,
    surface = WxLightPalette.panel,
    onSurface = WxLightPalette.text,
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = WxLightPalette.subText,
    outline = WxLightPalette.divider,
    error = Color(0xFFFA5151),
    onError = Color.White,
    errorContainer = Color(0xFFFDE7E7),
    onErrorContainer = Color(0xFF8C1F1F),
)

private val Dark = darkColorScheme(
    primary = WxDarkPalette.green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF14432C),
    onPrimaryContainer = Color(0xFFB7E7CC),
    secondary = SkyBlue,
    onSecondary = DeepBlue,
    background = WxDarkPalette.background,
    onBackground = WxDarkPalette.text,
    surface = WxDarkPalette.panel,
    onSurface = WxDarkPalette.text,
    surfaceVariant = WxDarkPalette.chip,
    onSurfaceVariant = WxDarkPalette.subText,
    outline = WxDarkPalette.divider,
    error = Color(0xFFF16A6A),
    onError = Color(0xFF2A0A0A),
    errorContainer = Color(0xFF3A1717),
    onErrorContainer = Color(0xFFF5C6C6),
)

/**
 * 跟随系统深浅色（和微信一致）。
 * 具体色值集中在 [WxPalette] / [LocalWxPalette]，聊天与会话列表直接取用。
 */
@Composable
fun DreamGirlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) WxDarkPalette else WxLightPalette
    CompositionLocalProvider(LocalWxPalette provides palette) {
        MaterialTheme(
            colorScheme = if (darkTheme) Dark else Light,
            content = content,
        )
    }
}
