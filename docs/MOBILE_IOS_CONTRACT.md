# MOBILE_IOS_CONTRACT.md — Контракт для iOS-клиента Corvex

Документ фиксирует архитектуру, интерфейсы и требования безопасности для нативного iOS-приложения (Swift + SwiftUI), симметричного Android-клиенту.

## 1. Безопасность и Сеть (`URLSession`)
- **Хранение секретов:** Только `Keychain` (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`). Запрещено использовать `UserDefaults` для `accessToken`, `refreshCookie` и `pinnedCertSha256`.
- **Динамический сервер:** `BaseUrlInterceptor` (эквивалент в `URLProtocol` или `RequestAdapter`) подменяет `scheme/host/port` на сохраненный в Keychain адрес self-hosted сервера.
- **Certificate Pinning:** Реализуется через `URLSessionDelegate` (`urlSession(_:didReceive:completionHandler:)`) со сравнением SHA-256 публичного ключа сервера.
- **Ротация JWT RS256 и HttpOnly Cookies:**
  - `HTTPCookieStorage` перехватывает `HttpOnly` refresh-cookie.
  - При получении `401 Unauthorized` сетевой слой сериализует (через `actor TokenRefresher`) запрос `POST /api/v1/auth/refresh` и повторяет исходный запрос с новым `Authorization: Bearer <token>`.

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

## 5. Чат (STOMP) и Предпросмотр
- **Чат:** `URLSessionWebSocketTask` поверх `wss://<host>/ws` с фреймами `CONNECT`, `SUBSCRIBE`, `SEND`, `DISCONNECT` и `clientMessageId` для идемпотентности.
- **Предпросмотр Office/PDF:** Использовать нативный `PDFKit` (`PDFView`) для отображения PDF, полученных от `/api/v1/mail/messages/{id}/attachments/{part}/preview` и `/api/v1/files/{fileRef}/preview`. При кодах `501/413/415/422` предлагать скачивание оригинала.
