# Координация агентов Corvex

## Правила
- Ведущий агент ставит задачи и принимает инженерный результат.
- Исполнитель меняет только файлы в назначенной области.
- Перед изменением общих файлов согласовать владельца.
- Не удалять историю сообщений и не перезаписывать чужие записи.
- Не записывать секреты, токены, пароли и содержимое реальных писем.

## Доска задач

### AV-001 — Строгая обработка ответов ClamAV
- Постановщик: ведущий агент / пользователь
- Исполнитель: второй агент
- Статус: REVIEW
- Область:
  - services/mail-antivirus-service/src/main/java/ru/corvex/mailav/ClamAvClient.java + тест
  - services/mail-storage-service/src/main/java/ru/corvex/mailstorage/av/ClamAvClient.java + тест
  - services/chat-file-service/src/main/java/ru/corvex/chatfile/av/ClamAvClient.java + тест
- Не менять: James, Compose и маршруты доставки без согласования
- Критерии приёмки: см. раздел AV-001 ниже
- Результат: ссылка на commit или список изменённых файлов
- Проверки: точные команды и результаты
- Ограничения: что не проверено

## Сообщения

### [2026-11-14, утро UTC] Второй агент → Ведущий агент / пользователь [AV-001]
Взял задачу AV-001 в работу. Все три клиента имеют одинаковую уязвимую логику: `endsWith("OK")`, `contains("FOUND")`, чтение ответа до EOF без лимита размера, у mail-antivirus-service нет connect-таймаута. Реализую строгое распознавание ответов clamd (zINSTREAM): только `stream: OK` → CLEAN, `stream: <сигнатура> FOUND` → INFECTED, всё остальное → ERROR; ответ читается с ограничением размера; таймауты подключения/чтения везде одинаковые. Публичное API клиентов (Result, ScanResult) и поведение вызывающих сервисов не меняются.

### [2026-11-14, день UTC] Второй агент → Ведущий агент / пользователь [AV-001]
Задача готова к ревью (REVIEW). Изменения не закоммичены (согласование git-операций с ведущим агентом); файлы в рабочей копии ветки arena/1755bb1a-corvex.

Что сделано:
- Все три клиента приведены к строгому распознаванию ответов clamd (zINSTREAM): только `stream: OK` → CLEAN, только `stream: <сигнатура> FOUND` → INFECTED, всё остальное (включая текст, заканчивающийся на "OK", пустой, усечённый, недотерминованный без "\n", слишком длинный) → ERROR.
- Чтение ответа ограничено MAX_REPLY_SIZE = 1 MiB; читается до первой строки/закрытия соединения/лимита.
- Добавлен connect-таймаут 5 с и чтения 60 с во всех трёх клиентах (у mail-antivirus-service не было connect-таймаута).
- Публичное API (Result, ScanResult, scan(InputStream)) и поведение вызывающих сервисов не меняются: обработка ERROR в AntivirusCheckListener/MailApiService/ChatFileService осталась как была (fail-closed по вложениям/файлам, письмо доставляется).
- Тесты: по 10 на клиент (4 существующие + 6 новые: ответ, заканчивающийся на "OK"; пустой; усечённый; без завершения "\n"; слишком длинный; таймаут чтения на подвисшем clamd).

Не проверено (ограничения среды):
- Сборка и прогон тестов: в песочнице нет JDK/Maven — не удалось выполнить `mvn ... test`. Точные команды для приёмки см. в отчёте пользователю.
- Настоящий ClamAV: clamav/clamav:1.5.4 есть только в docker-compose, запустить нельзя — команда `clamdscan --ping` / реальное сканирование не проверялось.

### [2026-11-14, день UTC] Второй агент → Ведущий агент / пользователь [AV-001]
Исправлено по замечанию: терминатор ответа clamd для zINSTREAM — NUL (`\0`), а не LF (`\n`), подтверждено официальной документацией ClamAV (docs.clamav.net/manual/Usage/ClamdProtocol.html): z-команды получают NUL-терминированные ответы.

Что изменено во всех трёх клиентах:
- readReply читает до NUL (`\0`), а не до `\n`
- parseReply проверяет `rawReply.endsWith("\0")` как признак полного ответа; отсутствие NUL → ERROR
- Fake-серверы в тестах отправляют NUL-терминированные ответы (`stream: OK\0`, `... FOUND\0`, `... ERROR\0`)
- Новые тесты: nonNulTerminatedCleanReplyIsError (ответ без NUL → ERROR), overlyLongReplyIsError, readTimeoutIsError
- Тест replyEndingWithOkIsNotAutomaticallyClean подтверждает: `stream: OK only\0` → ERROR, а не CLEAN

Не проверено (ограничение среды): нет JDK/Maven/Docker — `mvn test` и реальный ClamAV не запускались.

### [2026-11-14, поздний день UTC] Второй агент → Ведущий агент / пользователь [AV-001]
Исправления по замечанию ведущего агента:

1. Выходной поток сокета не закрывается до чтения ответа — `try-with-resources` убран с `DataOutputStream`; сокет закрывается после завершения обмена (внешний `try (Socket ...)`).
2. CLEAN проверяется точно без `trim()`: `"stream: OK".equals(reply)` где `reply` — строка до NUL без обрезки. Лидирующий/закрывающий пробел и `\n` перед NUL приводят к ERROR.
3. Тест `replyEndingWithOkIsNotAutomaticallyClean` теперь использует `stream: NOT OK\0` (действительно заканчивающийся на OK), а не `stream: OK only`.
4. Добавлен второй конструктор `ClamAvClient(String, int, int connectTimeoutMs, int readTimeoutMs)` для тестов; production-READ_TIMEOUT_MS=60_000 не меняется. Тест `readTimeoutIsError` использует 1500 мс вместо 60 с.
5. Добавлены тесты: `cleanReplyWithLeadingSpaceIsError`, `cleanReplyWithTrailingSpaceBeforeNulIsError`, `cleanReplyWithNewlineBeforeNulIsError`.

Не проверено: нет JDK/Maven/Docker — `mvn test` и реальный ClamAV не запускались.

### [2026-11-14, вечер UTC] Второй агент → Ведущий агент / пользователь [AV-001]
Исправления по замечанию ведущего агента (блокер commit 3ceb2c2):

1. **DI/Spring**: добавлен `@Autowired` на основной конструктор с `@Value` параметрами. Теперь Spring явно выбирает правильный конструктор при наличии второго (с настраиваемыми таймаутами).
2. **Тест Spring-контекста**: добавлен `ClamAvClientContextTest` для каждого клиента (`@SpringBootTest(classes = ClamAvClient.class)` + `@TestPropertySource`), проверяющий, что бин создаётся через реальный контекст.
3. **Цикл чтения InputStream**: `read() == 0` больше не интерпретируется как конец потока — цикл продолжается пока не придёт `-1`. Добавлен тест `inputStreamWithZeroReadsBeforeDataIsScannedFully` с `ZeroThenDataInputStream`.
4. **Сохранено**: NUL-терминатор, `MAX_REPLY_SIZE = 1 MiB`, тестовый таймаут 1500 мс (production 60 с без изменений).

Не проверено: нет JDK/Maven/Docker — `mvn test` и реальный ClamAV не запускались.

### [2026-11-14, поздний вечер UTC] Второй агент → Ведущий агент / пользователь [AV-001]
Исправления по замечанию ведущего агента:

1. **Убран дублирующий ClamAvClientContextTest из ClamAvClientTest.java** — класс оставлен только в отдельном файле `ClamAvClientContextTest.java` для каждого модуля.
2. **read() == 0 не вызывает бесконечный цикл** — после 100 последовательных нулевых чтений сканирование безопасно завершается с `Result.ERROR` ("входной поток не передаёт данные (read() == 0)").
3. **Добавлен тест inputStreamAlwaysReturningZeroProducesError** — constantly-returning-zero stream безопасно приводит к ERROR.
4. **Тест inputStreamWithZeroReadsBeforeDataIsScannedFully** сохранён — проверяет, что временные нули не прерывают сканирование преждевременно.
5. **Сохранено**: NUL-терминатор, `MAX_REPLY_SIZE = 1 MiB`, тестовый таймаут 1500 мс (production 60 с без изменений), `@Autowired` на конструкторе Spring.

Не проверено: нет JDK/Maven/Docker — `mvn test` и реальный ClamAV не запускались.

### [2026-11-14, вечер UTC] Второй_agent → Ведущий агент / пользователь [AV-001 follow-up]
Подготовлен набор для проверки трёх клиентов ClamAV с настоящим clamd через существующий Docker Compose:

- `scripts/av-001-clamav-integration-check.sh` — основной запускаемый набор (PASS/FAIL)
- `scripts/av-001-zinstream-client.py` — Python-клиент zINSTREAM протокола

Особенности проверки:
- Используются только синтетические данные: чистый поток и EICAR test string
- Не отправляет реальных писем
- Порт 3310 не публикуется наружу (работа через docker exec внутри контейнера clamav)
- Контейнеры не пересоздаются
- James, Compose и почтовые маршруты не меняются
- Проверка недоступного сканера через закрытый порт 13310 (без остановки общего ClamAV)
- Итоговый PASS/FAIL, без секретов в выводе

Не запускалось: в среде нет Docker — проверка не выполнялась. Запуск запланирован на ПК пользователя.

### [2026-11-14, поздний вечер UTC] Второй_agent → Ведущий агент / пользователь [AV-001 follow-up 2]
Подготовлен набор для проверки трёх Java-клиентов ClamAV с настоящим clamd:

- `scripts/av-001-java-clients-check.ps1` — PowerShell скрипт для запуска на Windows
- `scripts/src/Av001ClientCheck.java` — тестовый класс, использующий все три ClamAvClient

Особенности проверки:
- Вызывает каждый из трёх Java-клиентов (mail-antivirus-service, mail-storage-service, chat-file-service)
- Чистый поток → CLEAN, EICAR → INFECTED, недоступный порт 13310 → ERROR
- Работает через существующий Java-контейнер в сети app (обращение к clamav:3310)
- Порт 3310 не публикуется наружу, контейнеры не пересоздаются, docker compose up не запускается
- Реальные письма не отправляются, James/Compose/маршруты не меняются
- Уникальный временный путь (av001-<timestamp>) без перезаписи чужих файлов
- Итоговый PASS/FAIL, без секретов в выводе

Не запускалось: в среде нет Docker/Java/Maven — проверка не выполнялась. Запуск запланирован на ПК пользователя.

### [2026-11-14, вечер UTC] Второй_agent → Ведущий агент / пользователь [AV-002]
Подготовлен исправленный набор для проверки трёх Java-клиентов ClamAV с настоящим ClamAV:

- `scripts/av-002-java-clients-check.ps1` — PowerShell скрипт для Windows
- `scripts/src/Av002ClientCheck.java` — Java harness, вызывает scan() всех трёх классов напрямую

Исправления по сравнению с откомитированным ce7e626:
1. Harness вызывает scan(InputStream) каждого класса напрямую (без рефлексии, без повторной реализации протокола)
2. Classpath разделитель `:` (Linux), не `;` (Windows)
3. Используется `sh`, не `bash` (Alpine JRE в контейнере mail-storage-service)
4. Контролируемый fake endpoint для имитации сбоя (не порт 13310, не гарантированно закрытый)
5. Уникальный временный каталог с очисткой в finally
6. Компиляция на хосте (Maven), так как в контейнере только JRE без javac

Сценарии (9 результатов): чистый→CLEAN, EICAR→INFECTED, fake endpoint→ERROR для каждого из 3 клиентов.

Не запускалось: в среде нет Docker/Java/Maven — проверка не выполнялась.
