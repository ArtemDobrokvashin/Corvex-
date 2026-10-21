# 6. Примеры кода ключевых компонентов

> К пункту 6 формата вывода. Код лежит в репозитории (не картинками в документе) —
> ниже карта: какой файл какой архитектурный вопрос закрывает и что смотреть на ревью.

## 6.1. Аутентификация и безопасность

| Файл | Что демонстрирует |
|---|---|
| `services/auth-service/.../security/TokenService.java` | выпуск JWT RS256: claims (`sub`, `permissions`, `jti`, `amr`), TTL 15 мин из конфига; javadoc фиксирует, почему refresh — opaque, а не JWT |
| `services/auth-service/.../security/TotpService.java` | полноценный TOTP RFC 6238 без внешних зависимостей: генерация Base32-секрета, окно ±1 шаг; покрывается unit-тестами на тестовых векторах RFC |
| `services/web-gateway/.../security/GatewaySecurityConfig.java` | реактивная проверка JWT по JWKS, публичные маршруты только для login/refresh/autoconfig |
| `services/web-gateway/src/main/resources/application.yml` | маршруты `/api/v1/**`, rate limiting (Redis) отдельно на login и API, circuit breaker на почте, CORS |
| `services/admin-service/.../rbac/Permission.java` | каталог из 25 атомарных прав (почта/чат/админ/аналитика) |
| `services/admin-service/.../rbac/EffectivePermissionService.java` | union прав всех групп + точные наборы 6 ролей по умолчанию из ТЗ §6.1 (синхронизированы с SQL-сидом `V2__seed_default_groups.sql`) |

## 6.2. Почтовый контур

| Файл | Что демонстрирует |
|---|---|
| `services/mail-smtp-service/.../server/InboundMessageHandlerFactory.java` | приём через SubEtha SMTP: анти-open-relay на уровне RCPT, лимит размера, мгновенная постановка в конвейер событием; протокол не парсится руками |
| `services/mail-storage-service/.../domain/MessageMetadata.java` | JPA-модель метаданных: IMAP UID/флаги отдельными колонками, индексы под списки папок, `spam_score`, `av_status` |
| `services/mail-storage-service/.../storage/MaildirStorage.java` | Maildir tmp→new (атомарность), CAS вложений по SHA-256 с дедупликацией, защита от path traversal, учёт ограничений NTFS-имен |
| `services/mail-spam-service/.../SpamAssassinClient.java` | протокол SPAMC к spamd, **fail-open** при недоступности фильтра |
| `services/mail-antivirus-service/.../ClamAvClient.java` | протокол INSTREAM к clamd (поток чанками), **fail-closed по вложению** |
| `services/mail-imap-service/.../server/ImapServerPort.java` + `README.md` сервиса | порт-адаптер над Apache James: почему протокольная машина не пишется вручную, обязательный набор расширений IMAP |
| `services/mail-pop3-service/README.md` | семантика POP3 поверх общего хранилища (UIDL, DELE/QUIT) |

## 6.3. Чат и уведомления

| Файл | Что демонстрирует |
|---|---|
| `services/chat-service/.../config/WebSocketConfig.java` | STOMP broker relay в RabbitMQ (масштабирование чата), SockJS-fallback, heartbeat |
| `services/chat-service/.../ws/ChatMessageController.java` | адресация `/app`/`/topic`/`/user`, идемпотентность по `clientMessageId`, авторизация на каждое сообщение |
| `services/notification-service/.../AlertListener.java` | алерты уходят через отдельный (рекомендуется внешний) SMTP — уведомление о падении почты не зависит от почты |

## 6.4. Стандарты кода (применяются ко всем сервисам)

- Java 17, Spring Boot 3.2; records для DTO/событий, Lombok только в сущностях;
- зависимости слоёв и порты/адаптеры — см. docs/02 §2.2;
- конфиг только env-переменными, секреты не в репозитории (`.gitignore`: `.env`, `*.pem`);
- каждая публичная единица имеет Javadoc с «почему так», а не пересказом «что»;
- JaCoCo-гейт в каждом `pom.xml`: сборка падает при покрытии строк < 70% (ТЗ §12);
- тела писем и тела логов никогда не логируются (PII): только id, размер, статусы.

## 6.5. Честный статус каркаса

Репозиторий — архитектурный каркас первой итерации: все 12 сервисов имеют pom,
Dockerfile, конфигурацию, точки входа, миграции своих БД и перечисленные выше
ключевые компоненты с рабочей логикой транспортных клиентов (SPAMC, INSTREAM,
TOTP, Maildir, STOMP).

**Этап 1 (Identity и админка) реализован полностью:** auth-service — сущности и
репозитории (`domain/`, `repository/`), вход с Argon2id и lockout, ротация refresh
с детектом кражи токена, TOTP и пароли приложений (`service/AuthService`),
внутренний CRUD пользователей и verify-password (`InternalUserService`,
`api/InternalUserController`), сид суперадмина (`bootstrap/`); admin-service —
группы/права/членство, оркестрация пользователей через internal API auth с
компенсацией, аудит, настройки, здоровье, аналитика (`service/Admin*Service`,
контроллеры `api/Admin*Controller`), назначение SUPER_ADMIN при старте.
Межсервисная связка: права для JWT auth получает из admin
(`AdminAccessClient` → `/internal/users/{id}/access`) с безопасной деградацией.

Остальные тела сервисного слоя (почтовый конвейер и James-адаптеры IMAP/POP3,
DKIM-подписант, персистенция чата, LDAP-синхронизатор) реализуются по этапам
2–5 плана разработки ([09](09-development-plan.md)) без изменения контрактов.
