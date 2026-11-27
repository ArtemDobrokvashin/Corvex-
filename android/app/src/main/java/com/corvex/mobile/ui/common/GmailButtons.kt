package com.corvex.mobile.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.corvex.mobile.ui.theme.CorvexColors

/**
 * Кнопки в стиле Gmail для Android.
 *
 * Что именно повторяем из Gmail, а не «на глаз красиво»:
 *
 *  - **таблетка** (полностью скруглённые торцы), высота 40 dp — попасть
 *    пальцем легко, но кнопка не выглядит тяжёлой;
 *  - **иконка слева от текста**, 18 dp, отступ 8 dp — в Gmail действие
 *    узнаётся по иконке раньше, чем читается слово;
 *  - **одна главная кнопка** с заливкой акцентом, остальные — без заливки:
 *    два равных акцента на экране спорят друг с другом;
 *  - горизонтальные отступы 20 dp — Gmail не прижимает текст к краю.
 *
 * Раньше в клиенте были `Button` и `OutlinedButton` по умолчанию: жёсткие
 * прямоугольники с почти квадратными углами, из-за которых письмо выглядело
 * как форма, а не как почта.
 */
@Composable
fun GmailPillButton(
    text: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = CorvexColors.Accent,
            contentColor = Color.White,
            disabledContainerColor = CorvexColors.LineSubtle,
            disabledContentColor = CorvexColors.Faint
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
    ) {
        PillContent(text = text, icon = icon)
    }
}

/**
 * Вторая по важности кнопка: без заливки, но с тонкой рамкой.
 *
 * Совсем без рамки она сливалась бы с фоном письма — человек не понял бы,
 * что это кнопка, а не подпись.
 */
@Composable
fun GmailPillOutlinedButton(
    text: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
        border = BorderStroke(1.dp, CorvexColors.LineControl),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = CorvexColors.InkSoft,
            disabledContentColor = CorvexColors.Faint
        )
    ) {
        PillContent(text = text, icon = icon)
    }
}

@Composable
private fun PillContent(text: String, icon: ImageVector?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}
