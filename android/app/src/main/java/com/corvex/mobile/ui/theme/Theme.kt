package com.corvex.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Светлая схема мобильного клиента. Тёмной темы в вебе пока нет, поэтому и здесь
 * её нет: обещать переключение, которого не существует, хуже, чем не обещать.
 */
private val CorvexColorScheme = lightColorScheme(
    primary = CorvexColors.Accent,
    onPrimary = Color.White,
    primaryContainer = CorvexColors.AccentSoft,
    onPrimaryContainer = CorvexColors.AccentDark,
    secondary = CorvexColors.InkSoft,
    onSecondary = Color.White,
    // Без этих двух строк выбранный FilterChip берёт фиолетовую пару Material
    // по умолчанию: `onSecondary` от неё не помогает. На первой установке
    // «Сервер Corvex» светился фиолетовым на белом экране — то есть тема не
    // применялась, хотя весь остальной интерфейс был проектный.
    secondaryContainer = CorvexColors.AccentSoft,
    onSecondaryContainer = CorvexColors.AccentDark,
    // Поле в фокусе и его рамка — тоже акцент, а не фиолетовый tertiary.
    tertiary = CorvexColors.AccentDark,
    onTertiary = Color.White,
    tertiaryContainer = CorvexColors.AccentMuted,
    onTertiaryContainer = CorvexColors.AccentDark,
    background = CorvexColors.Canvas,
    onBackground = CorvexColors.Ink,
    surface = CorvexColors.Surface,
    onSurface = CorvexColors.Ink,
    surfaceVariant = CorvexColors.LineSubtle,
    onSurfaceVariant = CorvexColors.Muted,
    outline = CorvexColors.LineControl,
    outlineVariant = CorvexColors.Line,
    error = CorvexColors.Danger,
    onError = Color.White
)

/** Скругления как в вебе: строки списка — 6 dp, карточки и поля — 8–10 dp, чипы — 8 dp. */
private val CorvexShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(14.dp)
)

@Composable
fun CorvexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CorvexColorScheme,
        typography = CorvexTypography,
        shapes = CorvexShapes,
        content = content
    )
}
