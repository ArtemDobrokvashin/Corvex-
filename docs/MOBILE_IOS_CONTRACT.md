# MOBILE_IOS_CONTRACT.md — Контракт для iOS-клиента Corvex

Документ фиксирует архитектуру, интерфейсы и требования безопасности для нативного iOS-приложения (Swift + SwiftUI), симметричного Android-клиенту.

## 1. Безопасность и Сеть (`URLSession`)
- **Хранение секретов:** Только `Keychain` (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`). Запрещено использовать `UserDefaults` для `accessToken`, `refreshCookie` и `pinnedCertSha256`.
- **Динамический сервер:** `BaseUrlInterceptor` (эквивалент в `URLProtocol` или `RequestAdapter`) подменяет `scheme/host/port` на сохраненный в Keychain адрес self-hosted сервера.
- **Certificate Pinning:** Реализуется через `URLSessionDelegate` (`urlSession(_:didReceive:completionHandler:)`) со сравнением SPKI SHA-256 (`sha256/<base64>`) публичного ключа сервера. Пустой пин = pinning выключен (явно отображается в UI); невалидный пин = fail-closed (соединение блокируется).
- **Ротация JWT RS256 и HttpOnly Cookies:**
  - `HTTPCookieStorage` перехватывает `HttpOnly` cookie `corvex_refresh` (`Path=/api/v1/auth`, `SameSite=Strict`, `Max-Age`) и отправляет её **только** на `/api/v1/auth/**` (не на `/mail/**`) с проверкой срока жизни.
  - Запрос `POST /api/v1/auth/refresh` **не** должен нести заголовок `Authorization: Bearer ...` (иначе просроченный токен блокирует обновление).
  - При получении `401 Unauthorized` сетевой слой сериализует (через `actor TokenRefresher`) запрос `POST /api/v1/auth/refresh` и повторяет исходный запрос с новым `Authorization: Bearer <token>`.
- **Авторизация и MFA:** Логин отправляет `{"username": "...", "password": "..."}`. При включённом TOTP сервер возвращает `202 Accepted` с `{"mfaRequired": true, "mfaTicket": "..."}`; подтверждение — `POST /api/v1/auth/mfa/verify` с `{"mfaTicket": "...", "code": "123456"}`.

## 2. Интерфейсы Repository / ViewModel
```swift
protocol AuthRepositoryProtocol {
    func login(serverUrl: String, email: String, password: String) async throws -> AuthResult
    func verifyTotp(mfaToken: String, code: String) async throws -> AuthResult
    func logout() async
}

protocol MailRepositoryProtocol {
    func observeFolders() -> AsyncStream<[FolderModel]>
    func observeMails(folderId: String) -> AsyncStream<[MailModel]>
    func syncFolder(folderId: String, cursorUid: Int64?) async throws
    func markRead(mailId: String, isRead: Bool) async throws
}
```

## 3. Локальная БД (CoreData / SQLite.swift)
Схема полностью повторяет Android Room (`CorvexDatabase`):
- `folders`: `id (PK)`, `name`, `parentId`, `unreadCount`
- `mails`: `id (PK)`, `folderId`, `uid`, `subject`, `sender`, `snippet`, `bodyHtml`, `bodyText`, `isRead`, `isStarred`, `hasAttachments`, `receivedAt`

## 4. Фоновая синхронизация (Background Fetch)
- Использовать `BGAppRefreshTask` (`BGTaskScheduler`) с интервалом не реже 15 минут.
- При запуске в фоне: обновить список папок и первые 30 писем `INBOX` по курсору, сохранить в локальную БД.

## 5. Чат (STOMP), Предпросмотр и режим IMAP/SMTP
- **Чат:** `URLSessionWebSocketTask` поверх `wss://<host>/ws/chat/websocket`. Токен `Authorization: Bearer <jwt>` передаётся **в кадре `CONNECT`** (не в HTTP-рукопожатии). Подписки (`/topic/rooms.<uuid>`) выполняются только после получения `CONNECTED`; отправка — `/app/rooms/<uuid>/send|typing|read` с `clientMessageId` для идемпотентности. Heartbeat 10 с в обе стороны.
- **Предпросмотр Office/PDF:** Использовать нативный `PDFKit` (`PDFView`) для отображения PDF, полученных от `/api/v1/mail/messages/{id}/attachments/{part}/preview` и `/api/v1/files/{fileRef}/preview`. При кодах `501/413/415/422` предлагать скачивание оригинала.
- **Режим обычной почты (IMAP/SMTP):** Поддерживается через единый протокол `MailBackend` и `Capabilities`. UI читает `Capabilities` и скрывает недоступные для IMAP функции (чат, календарь, диск, серверный предпросмотр Office).
