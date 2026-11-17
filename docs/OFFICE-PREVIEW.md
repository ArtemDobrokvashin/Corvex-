# Предпросмотр Office-документов (DOCX/XLSX/PPTX/ODT/RTF)

Статус: **реализовано в коде, но в песочнице не компилировалось** — здесь нет JDK/Maven/Docker.
Фронтенд, конвертер, endpoint'ы, compose-профиль и тесты добавлены; перед включением нужен
`mvn -B clean verify` на машине с JDK 17 и приёмка из раздела «Приёмка». Пока профиль
`office-preview` не поднят, endpoint'ы отвечают `501`, и диалог честно предлагает скачать файл.

## Принятые решения

| Вопрос | Решение |
| --- | --- |
| Где LibreOffice | Отдельный внутренний контейнер `office-preview-service`; **не** в `mail-storage-service` и не на Alpine |
| Когда включён | По умолчанию выключен: `CORVEX_OFFICE_PREVIEW_ENABLED=false`; выключенный даёт `501` |
| Лимит исходника | 25 МБ (проверяют и фронтенд, и сервер) |
| Таймаут | 30 с на HTTP-вызов, 20 с на сам `soffice`, process tree убивается |
| Параллельность | 2 конвертации одновременно, остальные — `429` (фронт покажет fallback) |
| Кэш | `sha256` + версия LibreOffice, TTL 30 дней с последнего обращения, общий лимит 5 ГБ |
| Шрифты | `fonts-dejavu-core` + `fonts-liberation2`; при необходимости Carlito/Caladea или корпоративные |
| Пароль/макросы | Не поддерживаются: понятный отказ, никаких попыток «открыть» |
| Что скачивается | Всегда оригинал; PDF существует только для просмотра |
| Охват v1 | Почта и чат. Диск — после появления серверного API диска (сейчас в репозитории только фронтенд) |
| Публичный маршрут | Конвертер **не** публикуется через gateway; наружу видны только `.../preview` у почты и чата |

## Что лежит в репозитории

| Файл | Назначение |
| --- | --- |
| `services/office-preview-service/` | Spring Boot 3.5 / Java 17: `POST /internal/office-preview/convert`, кэш, `SofficeRunner`, `InternalTokenGuard`, тесты и Dockerfile на Debian + LibreOffice |
| `services/mail-storage-service/.../client/OfficePreviewClient.java` | HTTP-клиент конвертера (таймаут 30 с, internal-токен, base64url имени) |
| `services/mail-storage-service/.../api/MailAttachmentPreviewController.java` | `GET /api/v1/mail/messages/{id}/attachments/{part}/preview` после AV-гейта `downloadAttachment` |
| `services/chat-file-service/.../client/OfficePreviewClient.java` | тот же клиент для чата |
| `services/chat-file-service/.../api/ChatFilePreviewController.java` | `GET /api/v1/files/{fileRef}/preview` после `download` (членство + AV `423`) |
| `docker-compose.yml` | сервис под профилем `office-preview`, том `office-preview-cache`, флаги у почты и чата |
| `frontend/src/features/preview/` | Office-ветка диалога, лимит 25 МБ, маппинг `501/415/422/423/503` |

## Архитектура

```
браузер
  └─ GET /api/v1/mail/messages/{id}/attachments/{part}/preview   (MAIL_READ_OWN)
     или /api/v1/files/{fileRef}/preview                        (CHAT_READ)
        └─ mail-storage-service / chat-file-service
             ├─ проверка владельца/членства (уже есть в download)
             ├─ антивирус: INFECTED → 403/423, сканер недоступен → 503
             └─ POST /internal/office-preview/convert  (X-Corvex-Internal-Token)
                  └─ office-preview-service (Debian + LibreOffice, без сети)
                       ├─ проверка размера и имени
                       ├─ кэш sha256 + версия LO
                       ├─ soffice --headless ... → PDF во временном каталоге
                       └─ проверка сигнатуры %PDF и лимита страниц
```

Почему отдельный контейнер:

- LibreOffice ставится из Debian-пакетов; текущие сервисы собраны на `eclipse-temurin:17-jre-alpine`;
- конвертация требует 150–400 МБ RAM на процесс и может падать/зависать — это не должно ронять почту;
- контейнер можно запускать без egress-сети, с read-only rootfs и лимитами, отдельно обновлять по CVE;
- конвертер остаётся stateless: права, AV и аудит — в вызывающих сервисах.

## Публичный контракт

### Почта

`GET /api/v1/mail/messages/{messageId}/attachments/{partIndex}/preview`

- права: `MAIL_READ_OWN` + владелец письма (тот же `loadOwned`, что при скачивании);
- AV: `INFECTED` → `403`, не-`CLEAN` → синхронный скан CAS-файла, ошибка сканера → `503`;
- успех: `200`, `Content-Type: application/pdf`, `Content-Disposition: inline`,
  `Cache-Control: private, no-store`, `X-Content-Type-Options: nosniff`;
- имя PDF: исходное имя без пути и управляющих символов + `.pdf` (как в существующем скачивании).

### Чат

`GET /api/v1/files/{fileRef}/preview`

- права: `CHAT_READ` + членство в комнате (переиспользовать `ChatFileService.download`);
- AV уже проверяется на загрузке; `INFECTED`/не-`CLEAN` при `CORVEX_CHAT_FILE_AV_REQUIRED=true`
  уже дают `423 LOCKED` — этот код сохраняется;
- заголовки ответа те же.

### Коды ошибок для фронтенда

| Код | Когда | Что показывает диалог |
| --- | --- | --- |
| `501` | конвертер выключен | «Не удалось подготовить предпросмотр», кнопка «Скачать» |
| `413` | больше 25 МБ (сервер) | то же |
| `415` | формат не поддерживается | то же |
| `422` | конвертация не удалась/битый файл | то же |
| `403` / `423` | AV или нет прав | «Антивирус заблокировал файл» / «Нет доступа» |
| `503` | сканер или конвертер недоступен/занят | «Антивирус временно недоступен» + «Повторить» |
| `404` | файл/вложение удалены | «Файл не найден» |

Фронтенд уже маппит эти коды (`frontend/src/features/preview/previewFailure.ts`) и вызывает:

- `mailApi.previewAttachment(id, partIndex)` → `.../attachments/{partIndex}/preview`;
- `chatApi.previewFile(fileRef)` → `/api/v1/files/{fileRef}/preview`.

## Внутренний контракт конвертера

`POST /internal/office-preview/convert`

Заголовки запроса:

- `X-Corvex-Internal-Token: <CORVEX_INTERNAL_API_TOKEN>` — constant-time сравнение, пустой токен → `503`;
- `X-Office-File-Name: <base64url UTF-8>` — имя нужно для выбора фильтра и расширения ответа;
- `Content-Type: application/octet-stream`, `Content-Length` ≤ 25 МБ.

Ответ:

- `200 application/pdf`, `Content-Length`, `X-Office-Cache: hit|miss`, `Cache-Control: no-store`;
- `400` — пустое тело/некорректное имя; `401` — токен; `413` — больше лимита;
  `415` — расширение не из allowlist; `422` — конвертация не дала валидный PDF;
  `429` — нет свободного слота; `503` — конвертер выключен или `soffice` не найден;
  `504` — таймаут.

Allowlist расширений: `doc docx odt rtf xls xlsx ods ppt pptx odp`.
Всё остальное — `415` (вызывающие сервисы и так вызывают конвертер только для Office).

## Пайплайн и кэш

1. Вызывающий сервис проверяет права и AV **до** передачи байтов.
2. Проверяет размер ≤ 25 МБ и расширение; иначе `413`/`415` без обращения к конвертеру.
3. Конвертер принимает поток во временный файл `in-<random>.<ext>` в `tmpfs`, считает sha256.
4. Если в кэше есть `<sha256>.pdf` и файл версии совпадает — отдаёт его (`X-Office-Cache: hit`),
   байты уже приняты, но конвертация не запускается.
5. Иначе — `soffice --headless --norestore --nolockcheck --nodefault --nofirststartwizard`
   с отдельным `-env:UserInstallation=file:///tmp/lo-profile-<random>`.
6. Проверка результата: файл существует, начинается с `%PDF-`, размер ≤ 50 МБ,
   число страниц ≤ 500 (быстрый подсчёт `/Type /Page`; при сомнении — отказ `422`).
7. Атомарное перемещение в кэш (`Files.move(..., ATOMIC_MOVE)`), отдача потока.
8. Временные файлы и профиль удаляются в `finally`; process tree убивается по таймауту.

Кэш:

- каталог `/var/cache/office-preview`, файлы `<ab>/<cd>/<sha256>.pdf`, метаданные рядом (`.json`);
- при старте сервис сравнивает `soffice --version` с сохранённой версией; при смене — чистит кэш;
- фоновая уборка: TTL 30 дней с последнего обращения и общий размер ≤ 5 ГБ (LRU по mtime);
- одновременные запросы одного sha256 сериализуются по ключу, второй ждёт результат первого;
- **AV-статус в кэше не хранится**: его проверяет вызывающий сервис при каждом запросе.

## Безопасность

- Контейнер: non-root (`10001`), `read_only: true`, `tmpfs: /tmp:size=512m`, `cap_drop: [ALL]`,
  `security_opt: [no-new-privileges:true]`, сеть только `app` (без egress), лимиты 1 vCPU / 1.5 ГБ.
- Никакого shell: только `ProcessBuilder` с фиксированным списком аргументов.
- Макросы отключены на уровне профиля LibreOffice:
  `registrymodifications.xcu` с `MacroSecurityLevel=3` (Very High) и пустым списком доверенных мест;
  профиль создаётся в tmpfs при старте конвертации.
- Парольные документы не поддерживаются: `soffice` вернёт ошибку → `422`, без попыток подбора.
- Внешние ссылки в документах не загружаются (нет сети).
- Имя файла: только basename, управляющие символы и `/ \` заменяются; расширение — из allowlist.
- Логи: requestId, sha256, длительность, код; **никогда** не содержимое и не имя в открытом виде
  (имя — только в debug).
- Обновление LibreOffice — часть обновления образа; версия попадает в ключ кэша.

## Конфигурация

Сервис `office-preview-service`:

| Переменная | По умолчанию | Смысл |
| --- | --- | --- |
| `CORVEX_INTERNAL_API_TOKEN` | — (обязателен) | общий internal-токен платформы |
| `CORVEX_OFFICE_PREVIEW_ENABLED` | `false` | выключенный сервис отвечает `503` |
| `CORVEX_OFFICE_SOFFICE_PATH` | `/usr/bin/soffice` | путь к бинарю |
| `CORVEX_OFFICE_TIMEOUT` | `20s` | таймаут процесса |
| `CORVEX_OFFICE_MAX_BYTES` | `26214400` | 25 МБ |
| `CORVEX_OFFICE_CACHE_DIR` | `/var/cache/office-preview` | кэш |
| `CORVEX_OFFICE_CACHE_TTL` | `30d` | TTL |
| `CORVEX_OFFICE_CACHE_MAX_BYTES` | `5368709120` | 5 ГБ |
| `CORVEX_OFFICE_CONCURRENCY` | `2` | слоты |
| `CORVEX_OFFICE_MAX_PDF_BYTES` | `52428800` | лимит результата |
| `CORVEX_OFFICE_MAX_PAGES` | `500` | приблизительный лимит страниц |
| `CORVEX_OFFICE_WORK_DIR` | `/tmp` | временные файлы и профиль LO |

Вызывающие сервисы:

| Переменная | По умолчанию | Смысл |
| --- | --- | --- |
| `CORVEX_OFFICE_PREVIEW_URL` | `http://office-preview-service:8092` | внутренний адрес |
| `CORVEX_OFFICE_PREVIEW_ENABLED` | `false` | без него endpoint отвечает `501` |
| `CORVEX_OFFICE_PREVIEW_TIMEOUT` | `30s` | HTTP-таймаут |

## Развёртывание

Сервисы на Alpine оставляем как есть; конвертер — отдельный Debian-образ.
Dockerfile уже лежит в `services/office-preview-service/Dockerfile`; ниже — его содержимое.

```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends \
      libreoffice-writer libreoffice-calc libreoffice-impress \
      fonts-dejavu-core fonts-liberation2 fonts-crosextra-carlito fonts-crosextra-caladea \
      ca-certificates curl && rm -rf /var/lib/apt/lists/*
RUN useradd --uid 10001 --create-home --shell /usr/sbin/nologin office \
 && mkdir -p /var/cache/office-preview && chown -R office /var/cache/office-preview
USER office
ENV HOME=/tmp
COPY --from=build /src/target/office-preview-service.jar /app/app.jar
EXPOSE 8092
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/app.jar"]
```

`docker-compose.yml`: сервис вынесен в профиль `office-preview`, потому что образ тянет
LibreOffice (~1 ГБ) и нужен не каждому стенду. Портов наружу нет, сеть только `app`.

```yaml
  office-preview-service:
    profiles: [office-preview]
    build: ./services/office-preview-service
    restart: unless-stopped
    read_only: true
    tmpfs: ["/tmp:size=512m"]
    cap_drop: [ALL]
    security_opt: ["no-new-privileges:true"]
    environment:
      TZ: Europe/Moscow
      CORVEX_INTERNAL_API_TOKEN: ${CORVEX_INTERNAL_API_TOKEN:?Set CORVEX_INTERNAL_API_TOKEN in .env}
      CORVEX_OFFICE_PREVIEW_ENABLED: "true"
      CORVEX_OFFICE_TIMEOUT: ${CORVEX_OFFICE_TIMEOUT:-20s}
      CORVEX_OFFICE_CONCURRENCY: ${CORVEX_OFFICE_CONCURRENCY:-2}
      CORVEX_OFFICE_MAX_BYTES: ${CORVEX_OFFICE_MAX_BYTES:-26214400}
      CORVEX_OFFICE_CACHE_DIR: /var/cache/office-preview
    volumes: [office-preview-cache:/var/cache/office-preview]
    networks: [app]
    deploy: { resources: { limits: { memory: 1500M, cpus: "1.0" } } }
    logging:
      driver: json-file
      options: { max-size: "10m", max-file: "5" }

volumes:
  office-preview-cache:
```

`mail-storage-service` и `chat-file-service` получают:

```yaml
      CORVEX_OFFICE_PREVIEW_ENABLED: ${CORVEX_OFFICE_PREVIEW_ENABLED:-false}
      CORVEX_OFFICE_PREVIEW_URL: http://office-preview-service:8092
```

`depends_on` намеренно нет: без профиля сервиса в сети нет, и compose не должен отказываться
поднимать почту. Включение (Linux/macOS):

```bash
CORVEX_OFFICE_PREVIEW_ENABLED=true docker compose --profile office-preview up -d --build
```

PowerShell не понимает Unix-синтаксис `VAR=value command` — там переменная задаётся отдельно:

```powershell
$env:CORVEX_OFFICE_PREVIEW_ENABLED = "true"
docker compose --profile office-preview up -d --build
```

Если `docker compose config --services` не показывает `office-preview-service`, команда выполняется
не из корня репозитория или дерево не обновлено до коммита с этим файлом (`git log -1`, `git status`).

## Изменения в существующих сервисах

### `mail-storage-service`

Файлы уже добавлены: `client/OfficePreviewClient.java`, `api/MailAttachmentPreviewController.java`.

1. `OfficePreviewClient` (пакет `client`), по образцу `AuthUserClient`:
   `java.net.http.HttpClient` с connect timeout 3 с, request timeout 30 с, токен из `InternalTokenGuard`,
   тело — `InputStream` (без чтения в память), заголовок имени в base64url.
   Статусы конвертера → `ResponseStatusException`: `415/422 → 422`, `429/503/504 → 503`,
   прочее → `502`; `501` вызывающий отдаёт сам при выключенном флаге.
2. `MailAttachmentPreviewController` — отдельный контроллер, чтобы не менять конструктор
   `MailController` и его тесты:

```java
@GetMapping("/messages/{id}/attachments/{partIndex}/preview")
@PreAuthorize("hasAuthority('MAIL_READ_OWN')")
ResponseEntity<InputStreamResource> preview(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                            @PathVariable int partIndex) throws IOException {
    var file = mail.downloadAttachment(userId(jwt), id, partIndex); // владелец + AV
    if (!preview.enabled()) {
        try (InputStream ignored = file.stream()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Предпросмотр Office выключен");
        }
    }
    InputStream pdf;
    try (InputStream source = file.stream()) {
        pdf = preview.convert(source, file.sizeBytes(), file.fileName());
    }
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                    .filename(pdfName(file.fileName()), StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(new InputStreamResource(pdf));
}
```

3. Проверка размера до вызова: `sizeBytes > 25 МБ` → `413`.
4. Закрывать исходный и результирующий потоки (try-with-resources / `InputStreamResource` с `close`).
5. Метрики конвертера: таймер `corvex.office.preview.convert`, счётчики `corvex.office.preview.cache{result}` и `corvex.office.preview.failures`.

Замечание: `AttachmentDownload` уже содержит `fileName` и `sizeBytes`; `sha256` для кэша не нужен —
конвертер считает его сам.

### `chat-file-service`

Файлы уже добавлены: `client/OfficePreviewClient.java`, `api/ChatFilePreviewController.java`.

1. Тот же `OfficePreviewClient` (можно вынести в общий internal-модуль позже).
2. Контроллер переиспользует `ChatFileService.download(...)`: членство, AV-код `423`,
   `Resource` и `StoredChatFile` уже есть; отдельный метод сервиса не понадобился.
3. `ChatFilePreviewController`:

```java
@GetMapping("/{fileRef}/preview")
@PreAuthorize("hasAuthority('CHAT_READ')")
ResponseEntity<InputStreamResource> preview(@AuthenticationPrincipal Jwt jwt,
                                            @PathVariable String fileRef) throws IOException {
    ChatFileService.Download download = files.download(ChatFilePrincipal.from(jwt), fileRef);
    if (!preview.enabled()) {
        try (InputStream ignored = download.resource().getInputStream()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Предпросмотр Office выключен");
        }
    }
    InputStream pdf;
    try (InputStream source = download.resource().getInputStream()) {
        pdf = preview.convert(source, download.metadata().sizeBytes(), download.metadata().fileName());
    }
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                    .filename(pdfName(download.metadata().fileName()), StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(new InputStreamResource(pdf));
}
```

Порядок маппинга `/{fileRef}` и `/{fileRef}/preview` в Spring MVC не конфликтует.

### `web-gateway`

Изменений не требуется: маршруты `/api/v1/mail/**` и `/api/v1/files/**` уже есть,
конвертер наружу не публикуется.

## Тесты

В песочнице Java не компилировалась (нет JDK/Maven). Синтаксис всех новых файлов проверен
tree-sitter; ниже — тесты, которые нужно прогнать на машине с JDK 17.

Сервис конвертера (`OfficeFileNamesTest`, `OfficeConversionServiceTest`, `ConversionCacheTest`,
`OfficePreviewControllerTest`):

- `InternalTokenGuard`: пустой токен → `503`, неверный → `401`, верный — проходит;
- allowlist расширений: `.docx` ok, `.exe` → `415`, имя с `/` и управляющими символами очищается;
- размер: 25 МБ ровно — ok, +1 байт → `413`;
- `soffice` отсутствует → `503`; ненулевой код → `422`; пустой/битый PDF → `422`; таймаут → `504`;
- фейковый `soffice` (shell-скрипт в `src/test/resources`) копирует фикстуру PDF: успех,
  проверка аргументов (`--headless`, отдельный `UserInstallation`, без shell);
- кэш: второй вызов того же sha256 не запускает процесс, `X-Office-Cache: hit`;
- параллельность: 3 запроса при `CONCURRENCY=2` → третий `429`;
- уборка TTL/quota на временном каталоге.

`mail-storage-service` / `chat-file-service`:

- `MailAttachmentPreviewControllerTest` / `ChatFilePreviewControllerTest`: `200` + заголовки; `403` AV проходит насквозь;
  `501` при выключенном флаге; `415/422 → 422`; `503 → 503`; `413` для 26 МБ;
- клиент: таймаут, `401/429/500` → корректные исключения; поток закрывается;
- `chat`: `423 LOCKED` для не-CLEAN файла, членство проверяется до конвертера;
- существующие тесты скачивания не меняются.

Интеграционные (нужен реальный LibreOffice, локально/в CI):

- docx, xlsx, pptx, odt, rtf с кириллицей → PDF > 0 страниц, текст извлекается (`pdftotext` в тесте);
- запароленный docx → `422`; файл 25 МБ+ → `413`; два параллельных запроса укладываются в лимит;
- smoke в compose: письмо с docx и файл в чате открываются в UI, второй раз — быстрее (кэш).

Фронтенд (уже покрыт):

- `FilePreviewDialog`: успешная конвертация, `501`, `503` + retry, лимит 25 МБ, отсутствие конвертера;
- `mailApi`/`chatApi`: точные URL `/preview`;
- `previewFailure`: `415/422/501 → conversion-unavailable`, `423 → blocked`.

## Приёмка

1. `mvn -pl services/office-preview-service -am test`
2. `mvn -pl services/mail-storage-service,services/chat-file-service test`
3. `docker compose --profile office-preview build office-preview-service mail-storage-service chat-file-service`
4. `CORVEX_OFFICE_PREVIEW_ENABLED=true docker compose --profile office-preview up -d`
5. `curl -s -H "Authorization: Bearer …" -D - "…/attachments/0/preview" -o /tmp/a.pdf` →
   `200`, `application/pdf`, файл открывается; повторный запрос — `X-Office-Cache: hit`.
6. UI: письмо с `.docx`, файл `.xlsx` в чате → просмотр; `.exe` → только скачивание;
   PENDING-вложение → скан и просмотр; INFECTED → блокировка.
7. Отключить флаг → `501`, UI показывает fallback; почта и чат работают.

## Ограничения и риски

- Точность вёрстки зависит от шрифтов; Calibri/Arial заменяются метрически совместимыми
  Carlito/Caladea, но сложные шаблоны могут отличаться.
- LibreOffice — большой парсер недоверенных файлов: обновлять образ по CVE, не давать сеть,
  не запускать от root, следить за памятью.
- Очень большие XLSX/PPTX могут не уложиться в таймаут — это штатный `422/504` с fallback,
  а не бесконечная загрузка.
- Кэш занимает диск; нужен контроль свободного места и уборка.
- На Alpine LibreOffice не ставим: только отдельный Debian-образ.
- Диск (`/api/v1/drive/**`) ещё не имеет серверной реализации; когда появится,
  endpoint `GET /api/v1/drive/{id}/preview` добавляется по тому же контракту.

## Что уже сделано

Бэкенд:

- конвертер с таймаутом, убийством дерева процессов, allowlist, лимитами, кэшем по sha256
  и версии LibreOffice, healthcheck и метриками;
- endpoint'ы почты и чата, которые сначала проверяют права/AV и только потом конвертируют;
- compose-профиль `office-preview`, том кэша, флаги у вызывающих сервисов;
- тесты конвертера и клиентов/контроллеров (запуск на ПК).

Фронтенд:

- диалог предпросмотра: PDF/изображения/текст/CSV/медиа, стрелки, Esc, зум, AV-сообщения;
- Office-ветка вызывает `loadPdf` и рендерит полученный PDF через тот же просмотрщик;
- при `501/415/422` показывает «Не удалось подготовить предпросмотр» и кнопку «Скачать»;
- `503` — «Антивирус временно недоступен» с повтором;
- лимит 25 МБ проверяется до запроса;
- тесты: `FilePreviewDialog`, `previewFailure`, `mailApi`, `chatApi`.
