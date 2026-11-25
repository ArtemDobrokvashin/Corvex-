package com.corvex.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Шрифт веба — Inter. В мобильном клиенте отдельный файл шрифта не поставляется,
 * поэтому используется системный sans-serif: метрики отличаются, но иерархия
 * размеров и весов повторяет веб (11–24 sp против 10.5–24 px).
 */
private val CorvexFontFamily = FontFamily.SansSerif

val CorvexTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = CorvexFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        color = CorvexColors.Ink
    ),
    titleLarge = TextStyle(
        fontFamily = CorvexFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        color = CorvexColors.Ink
    ),
    titleMedium = TextStyle(
        fontFamily = CorvexFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        color = CorvexColors.Ink
    ),
    bodyLarge = TextStyle(
        fontFamily = CorvexFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = CorvexColors.Ink
    ),
    bodyMedium = TextStyle(
        fontFamily = CorvexFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = CorvexColors.InkSoft
    ),
    bodySmall = TextStyle(
        fontFamily = CorvexFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = CorvexColors.Faint
    ),
    labelMedium = TextStyle(
        fontFamily = CorvexFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        color = CorvexColors.Muted
    ),
    labelSmall = TextStyle(
        fontFamily = CorvexFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        color = CorvexColors.Faint
    )
)
