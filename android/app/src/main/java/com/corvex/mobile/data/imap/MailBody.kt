package com.corvex.mobile.data.imap

/**
 * Тело письма, загруженное по требованию.
 *
 * Отдельно от `MailModel`: список не носит с собой тела писем, иначе каждое
 * обновление тянуло бы мегабайты. Загружается при открытии письма — и только
 * тогда.
 */
data class MailBody(
    val subject: String,
    val from: String?,
    val bodyText: String,
    /** `Message-ID` — нужен для `In-Reply-To`/`References` в ответе и пересылке. */
    val messageId: String? = null
)
