package com.corvex.mobile.ui.mail

import com.corvex.mobile.data.imap.OutgoingAttachment

/**
 * Вид письма, которое пишем. Один экран обслуживает все четыре случая:
 * разница только в начальном заполнении и заголовке.
 */
enum class ComposeKind { NEW, REPLY, REPLY_ALL, FORWARD }

/**
 * Черновик на экране.
 *
 * Отдельный тип, а не поля внутри `HomeUiState`: у черновика своя жизнь —
 * он открыт, пока письмо отправляется, и ошибка отправки не должна закрывать
 * то, что человек уже написал.
 *
 * Получатели хранятся списком, а не строкой через запятую: в mail.ru и Gmail
 * каждый адрес — отдельная «таблетка», которую можно удалить одним нажатием.
 * Строку с запятыми пришлось бы править посимвольно.
 */
data class ComposeState(
    val kind: ComposeKind,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String = "",
    val body: String = "",
    val originalId: String? = null,
    val inReplyTo: String? = null,
    val isSending: Boolean = false,
    val error: String? = null,
    /** Раскрыты ли поля «Копия» и «Скрытая»: по умолчанию их не видно. */
    val showCc: Boolean = false,
    val showBcc: Boolean = false,
    /** Поле ввода, которое сейчас заполняется: «Кому», «Копия» или «Скрытая». */
    val activeField: ComposeField = ComposeField.TO,
    /** Файлы, приложенные к письму. Содержимое читается сразу при выборе. */
    val attachments: List<OutgoingAttachment> = emptyList()
)

enum class ComposeField { TO, CC, BCC }
