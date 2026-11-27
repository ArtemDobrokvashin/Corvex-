package com.corvex.mobile.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.corvex.mobile.data.network.api.ChatEventDto
import com.corvex.mobile.data.network.stomp.StompClient
import com.corvex.mobile.data.network.stomp.StompFrame
import com.corvex.mobile.data.repository.ChatRepository
import com.corvex.mobile.data.repository.toDomain
import com.corvex.mobile.data.security.JwtSubject
import com.corvex.mobile.data.security.TokenStore
import com.corvex.mobile.domain.chat.ChatMessage
import com.corvex.mobile.domain.chat.ChatRoom
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

data class ChatUiState(
    val rooms: List<ChatRoom> = emptyList(),
    val isLoadingRooms: Boolean = false,
    val openedRoom: ChatRoom? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isLoadingMessages: Boolean = false,
    val draft: String = "",
    val error: String? = null,
    /** Есть ли живое соединение: без него сообщения уходят по REST. */
    val isLive: Boolean = false
)

/**
 * Чат.
 *
 * Две дороги к серверу, и обе нужны:
 *
 *  - **REST** — список комнат и история. Через WebSocket их не отдают.
 *  - **STOMP** — живые сообщения. Без него пришлось бы опрашивать сервер.
 *
 * Отправка идёт по REST всегда. Это осознанно: REST-путь тот же самый в
 * сервисе (`ChatService.sendMessage`), ответ приходит сразу, и сообщение не
 * теряется, если WebSocket в этот момент переподключается. Через STOMP
 * отправка — оптимизация, а не необходимость.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val stompClient: StompClient,
    private val tokenStore: TokenStore
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Кто я: `sub` из JWT, чтобы отличать свои сообщения от чужих. */
    private val ownUserId: String? = JwtSubject.from(tokenStore.accessToken)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        refreshRooms()
    }

    /**
     * @param silent `true` — обновление из фона (после отправки): спиннер на
     * единственном экране выглядел бы как повторная загрузка.
     */
    fun refreshRooms(silent: Boolean = false) {
        _uiState.update { it.copy(isLoadingRooms = !silent, error = null) }
        viewModelScope.launch {
            chatRepository.rooms()
                .onSuccess { rooms ->
                    _uiState.update { it.copy(isLoadingRooms = false, rooms = rooms) }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoadingRooms = false,
                            error = err.message ?: "Не удалось загрузить чаты"
                        )
                    }
                }
        }
    }

    fun openRoom(room: ChatRoom) {
        _uiState.update {
            it.copy(
                openedRoom = room,
                messages = emptyList(),
                isLoadingMessages = true,
                error = null
            )
        }
        connectLive(room)
        viewModelScope.launch {
            chatRepository.messages(room.id)
                .onSuccess { history ->
                    _uiState.update {
                        it.copy(
                            isLoadingMessages = false,
                            messages = history.map { msg -> msg.copy(isMine = isMine(msg)) }
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoadingMessages = false,
                            error = err.message ?: "Не удалось загрузить сообщения"
                        )
                    }
                }
        }
    }

    fun closeRoom() {
        _uiState.update { it.copy(openedRoom = null, messages = emptyList(), draft = "") }
        stompClient.disconnect()
        _uiState.update { it.copy(isLive = false) }
    }

    /**
     * Подписка на комнату.
     *
     * Сообщения приходят в `/topic/rooms.{id}` — с точкой, а не со слэшем:
     * RabbitMQ STOMP трактует хвост как routing key, и `/topic/rooms/{id}`
     * брокер отклоняет как Invalid destination.
     */
    private fun connectLive(room: ChatRoom) {
        stompClient.connect(
            onFrame = { frame -> onFrame(frame) },
            onError = { _uiState.update { it.copy(isLive = false) } }
        )
        stompClient.subscribeRoom(room.id)
        _uiState.update { it.copy(isLive = stompClient.isConnected()) }
    }

    private fun onFrame(frame: StompFrame) {
        when (frame.command) {
            "MESSAGE" -> {
                val (type, raw) = parseIncoming(frame.body) ?: return
                val message = raw.copy(isMine = isMine(raw))
                _uiState.update { state ->
                    if (message.roomId != state.openedRoom?.id) return@update state
                    // Не только добавление: правка и удаление приходят тем же
                    // конвертом, и без этого старый текст оставался бы на экране
                    // навсегда.
                    val updated = when (type) {
                        "MESSAGE_UPDATED" -> state.messages.map {
                            if (it.id == message.id) message else it
                        }
                        "MESSAGE_DELETED" -> state.messages.map {
                            if (it.id == message.id) it.copy(isDeleted = true) else it
                        }
                        else -> {
                            // Дедупликация по id: своё сообщение приходит и в
                            // ответе REST, и эхом по WebSocket.
                            if (state.messages.any { it.id == message.id }) state.messages
                            else state.messages + message
                        }
                    }
                    state.copy(messages = updated, isLive = true)
                }
            }
            "CONNECTED" -> _uiState.update { it.copy(isLive = true) }
            "ERROR" -> _uiState.update {
                it.copy(error = frame.headers["message"] ?: "Ошибка чата", isLive = false)
            }
        }
    }

    fun onDraftChange(value: String) {
        _uiState.update { it.copy(draft = value) }
    }

    fun send() {
        val state = _uiState.value
        val room = state.openedRoom ?: return
        val text = state.draft.trim()
        if (text.isEmpty()) return
        if (room.isReadOnly) {
            _uiState.update { it.copy(error = "В этой комнате нельзя писать") }
            return
        }

        val clientMessageId = UUID.randomUUID().toString()
        _uiState.update { it.copy(draft = "", error = null) }

        viewModelScope.launch {
            chatRepository.send(room.id, text, ownUserId, clientMessageId)
                .onSuccess { sent ->
                    _uiState.update { state ->
                        if (state.messages.any { it.id == sent.id }) state
                        else state.copy(messages = state.messages + sent)
                    }
                    refreshRooms(silent = true)
                }
                .onFailure { err ->
                    // Возвращаем текст в поле ввода: потерять набранное из-за
                    // обрыва сети — худшее, что может сделать чат.
                    _uiState.update {
                        it.copy(draft = text, error = err.message ?: "Сообщение не отправлено")
                    }
                }
        }
    }

    /**
     * Разбор STOMP-кадра.
     *
     * Тело — конверт `ChatEventView`, а сообщение лежит внутри в поле
     * `message`. Берём только события с сообщением: `TYPING` и `READ` не
     * должны попадать в ленту. Ошибка разбора не роняет чат: одно кривое
     * событие лучше пропустить, чем потерять соединение.
     */
    private fun parseIncoming(body: String): Pair<String, ChatMessage>? = runCatching {
        val event = json.decodeFromString<ChatEventDto>(body)
        val message = event.message?.toDomain() ?: return@runCatching null
        (event.type ?: "MESSAGE_CREATED") to message
    }.getOrNull()

    private fun isMine(message: ChatMessage): Boolean =
        ownUserId != null && message.authorId == ownUserId

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        stompClient.disconnect()
    }
}
