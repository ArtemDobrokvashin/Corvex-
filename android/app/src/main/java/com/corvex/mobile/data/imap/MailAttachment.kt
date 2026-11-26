package com.corvex.mobile.data.imap

/**
 * Вложение письма: имя, размер и MIME-тип — без содержимого.
 *
 * Содержимое тянется отдельно, по требованию: письмо с фотографиями весит
 * десятки мегабайт, и грузить его целиком при открытии — гарантированная
 * задержка на мобильной сети.
 */
data class MailAttachment(
    val name: String,
    val sizeBytes: Int,
    val mimeType: String,
    /**
     * Содержимое после `fetchAttachment`, чтобы показать превью без второго
     * похода в сеть. `null` — ещё не загружено.
     */
    val bytes: ByteArray? = null
) {
    /** «12 КБ», «1,4 МБ» — размер для строки списка. */
    fun sizeLabel(): String = when {
        sizeBytes <= 0 -> ""
        sizeBytes < 1024 -> "$sizeBytes Б"
        sizeBytes < 1024 * 1024 -> "${Math.round(sizeBytes / 1024.0)} КБ"
        else -> {
            val mb = sizeBytes / (1024.0 * 1024.0)
            val rounded = Math.round(mb * 10) / 10.0
            "$rounded МБ"
        }
    }

    fun isImage(): Boolean = mimeType.startsWith("image/")

    // data class с массивом: equals по ссылке, поэтому сравниваем по полям.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MailAttachment) return false
        return name == other.name &&
            sizeBytes == other.sizeBytes &&
            mimeType == other.mimeType
    }

    override fun hashCode(): Int = (name.hashCode() * 31 + sizeBytes) * 31 + mimeType.hashCode()
}

/** Вложение, готовое к отправке: имя, тип и содержимое. */
data class OutgoingAttachment(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray
)
