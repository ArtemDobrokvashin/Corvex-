package com.corvex.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Палитра мобильного клиента — те же значения, что в веб-интерфейсе
 * (`frontend/tailwind.config.js`). Один продукт не должен перекрашиваться при
 * переходе с ноутбука на телефон, поэтому цвета не «примерно похожие», а
 * буквально те же hex.
 */
object CorvexColors {
    val Accent = Color(0xFF1D63ED)
    val AccentDark = Color(0xFF154FD1)
    val AccentSoft = Color(0xFFEEF4FF)
    val AccentMuted = Color(0xFFDBEAFE)

    val Canvas = Color(0xFFF8FAFC)
    val Surface = Color(0xFFFFFFFF)
    val Chrome = Color(0xFFFFFFFF)

    val Ink = Color(0xFF0F172A)
    val InkSoft = Color(0xFF1E293B)
    val Muted = Color(0xFF475569)
    val Faint = Color(0xFF64748B)

    val Line = Color(0xFFE5E7EB)
    val LineSubtle = Color(0xFFF1F5F9)
    val LineControl = Color(0xFFE2E8F0)

    val Sidebar = Color(0xFF0B1221)
    val SidebarHover = Color(0xFF131E35)
    val SidebarActive = Color(0xFF16233D)
    val SidebarLine = Color(0xFF1A253C)
    val SidebarText = Color(0xFFCBD5E1)
    val SidebarMuted = Color(0xFF94A3B8)

    val Success = Color(0xFF15803D)
    val Warning = Color(0xFF92400E)
    val Danger = Color(0xFFB91C1C)

    val BadgePositiveBg = Color(0xFFDCFCE7)
    val BadgePositiveText = Color(0xFF15803D)
    val BadgeWarningBg = Color(0xFFFEF3C7)
    val BadgeWarningText = Color(0xFF92400E)
    val BadgeInfoBg = Color(0xFFEFF6FF)
    val BadgeInfoText = Color(0xFF1D4ED8)
    val BadgeCriticalBg = Color(0xFFFEE2E2)
    val BadgeCriticalText = Color(0xFFB91C1C)
}
