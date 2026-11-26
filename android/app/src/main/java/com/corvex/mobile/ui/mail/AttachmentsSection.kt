package com.corvex.mobile.ui.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import android.graphics.BitmapFactory
import com.corvex.mobile.data.imap.MailAttachment
import com.corvex.mobile.ui.theme.CorvexColors

/**
 * Вложения письма: список файлов под телом.
 *
 * Превью картинки показывается, если содержимое уже загружено (после
 * «Скачать»). Заранее не грузим: письмо с десятком фотографий иначе тянет
 * десятки мегабайт на открытии — ровно то, на что жаловались в первых версиях.
 */
@Composable
fun AttachmentsSection(
    attachments: List<MailAttachment>,
    downloadingName: String?,
    onDownload: (MailAttachment) -> Unit
) {
    if (attachments.isEmpty()) return

    Spacer(modifier = Modifier.height(18.dp))
    HorizontalDivider(color = CorvexColors.Line)
    Spacer(modifier = Modifier.height(12.dp))

    Text(
        text = "Вложения · ${attachments.size}",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = CorvexColors.Muted
    )
    Spacer(modifier = Modifier.height(8.dp))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.forEach { attachment ->
            AttachmentRow(
                attachment = attachment,
                isDownloading = downloadingName == attachment.name,
                onDownload = { onDownload(attachment) }
            )
        }
    }
}

@Composable
private fun AttachmentRow(
    attachment: MailAttachment,
    isDownloading: Boolean,
    onDownload: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CorvexColors.Canvas)
            .clickable(enabled = !isDownloading, onClick = onDownload)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Превью или иконка файла: картинка без загрузки содержимого — просто
        // рамка с типом, чтобы строки были одной высоты.
        val preview = rememberImageBitmap(attachment.bytes)
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CorvexColors.AccentSoft),
            contentAlignment = Alignment.Center
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Icon(
                    Icons.Filled.AttachFile,
                    contentDescription = null,
                    tint = CorvexColors.AccentDark,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.bodyMedium,
                color = CorvexColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOf(attachment.sizeLabel(), attachment.mimeType)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = CorvexColors.Faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (isDownloading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = CorvexColors.Accent
            )
        } else {
            Icon(
                Icons.Filled.Download,
                contentDescription = "Скачать ${attachment.name}",
                tint = CorvexColors.Accent,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Декодирование превью с ограничением размера.
 *
 * `BitmapFactory` на фотографии 12 МП без ограничения выделит ~48 МБ — и это
 * на экране со списком вложений. `inSampleSize` уменьшает вдвое, пока сторона
 * больше 256 px: для строки высотой 44 dp этого достаточно.
 */
@Composable
private fun rememberImageBitmap(bytes: ByteArray?): ImageBitmap? {
    if (bytes == null || bytes.isEmpty()) return null
    return androidx.compose.runtime.remember(bytes) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        }.getOrNull()
    }
}
