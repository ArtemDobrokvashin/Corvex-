# Эксперимент: защитный mailet James

**Не включать и не использовать для реальной почты.** Код не подключён к
Compose, не входит в образ и не является готовым AV-конвейером. Это кандидат
под API Apache James 3.9.1: он наследует `ClamAVScan` для штатного подключения к
clamd, но не использует его fail-open `service` и разбор ответа. Сканирует
сообщение заново, не доверяя пользовательским заголовкам и старым атрибутам;
только точный `stream: OK\0` оставляет исходный state. MIME сериализуется
непосредственно в ограниченный по числу байт INSTREAM, без использования
`MimeMessageInputStream` с его потенциальной полной материализацией в памяти. Инфицированный и
неизвестный результаты отправляются в отдельные состояния, которые **пока не
сконфигурированы**. При отсутствии процессоров нельзя включать mailet.

Не проверены реальные James зависимости/компиляция Maven, classloading в образе,
привязка к живому ClamAV, отображение ошибок, безопасность state transitions и
персистентность quarantine/recovery. Кандидат ограничивает общее время TCP connect тремя секундами и числом
адресов. Для обмена сокет закрывается watchdog по общему сроку 30 секунд;
при этом зависшее чтение MIME-входа и DNS в унаследованной инициализации
могут не прерваться. Планировщик и реальные ОС/James не испытаны под нагрузкой,
поэтому общий срок не является доказанной гарантией для всего сообщения. Лимит
сообщения и настройки ClamAV должны согласовываться с SMTP; пути JMAP import,
APPEND и internal могут обходить mailet. Согласно исходникам James 3.9.1,
[`Email/import`](https://github.com/apache/james-project/blob/james-project-3.9.1/server/protocols/jmap-rfc-8621/src/main/scala/org/apache/james/jmap/method/EmailImportMethod.scala) вызывает `mailbox.appendMessageReactive` напрямую, минуя spool;
[`EmailSubmission/set`](https://github.com/apache/james-project/blob/james-project-3.9.1/server/protocols/jmap-rfc-8621/src/main/scala/org/apache/james/jmap/method/EmailSubmissionSetMethod.scala) ставит письмо в spool. Это анализ исходников, не live test.

Нынешний `jmap-nginx.conf` ограничивает HTTP-пути и методы, **не** JMAP-методы
в JSON: любой обладатель bearer token и сетевого доступа к POST /jmap может
попытаться выполнить операции записи. `JamesJmapReadClient` ограничивает свои
собственные вызовы, но это не защита на границе сети. До включения путей импорта
необходимо ограничить все записи и удостовериться, что каждый путь проходит
политику AV/owner/RBAC; запрещать выдачу токенов браузерам недостаточно как
единственную меру. Кандидат mailet сам по себе не защищает JMAP import.
Не использовать этот класс, пока они не закрыты и пока реальные письма и
вложения не проходят проверку. EICAR/недоступность/сбои/restart/rollback не
проверены в James. `ToRepository` без потерь и без bounce пока лишь направление.

Локальная синтетическая проверка JDT 17 с временными заглушками James/JavaMail:
успешная компиляция, точный CLEAN, FOUND, ошибки/обрыв/пустой ответ и null MIME
переводятся в ожидаемые состояния. Это не компиляция против реальных James jar,
не Maven и не живая проверка. `scripts/tests/test_james_av_candidate.py` — лишь
структурные проверки; они не подтверждают работоспособность или защиту.

Повторяемая синтетическая проверка с JDK 17: `sh experiments/james-av-gate/test-stubs.sh`.
Она собирает кандидат против **искусственных заглушек**, запускает локальный
fake scanner и проверяет маршрутизацию результатов и зависания read/write на
сокете. Заглушки не включаются в образ; проверка не заменяет Maven с настоящими
зависимостями James или ClamAV и не доказывает безопасность всего конвейера.

Отдельный `pom.xml` подготовлен для будущей компиляции и unit-тестов с настоящими
артефактами James 3.9.1: `mvn -f experiments/james-av-gate/pom.xml test`.
**Для Maven нужен JDK 21 или новее**: исходники James 3.9.1 настроены на Java 21,
его опубликованные классы имеют версию 65. Попытка на ПК с Java 17 для исходного
POM завершилась `BUILD FAILURE` из-за несовместимой версии классов. Артефакты
были загружены, но компиляция и тесты против них не прошли; POM исправлен на 21,
повтор с JDK 21 для версии `d8e27cc` на ПК: 2 теста, 0 ошибок,
BUILD SUCCESS. **После этого код изменён**: потоковая сериализация и новые
тесты с настоящими зависимостями пока не запускались. Синтетический stub runner по-прежнему можно
запускать с JDK 17 — он не использует настоящие зависимости. Даже успешная Maven-сборка не
проверит загрузку класса внутри образа James, реальные clamd, карантин или ingress.

Важные ограничения, обнаруженные по исходникам 3.9.1: если состояние доходит до
конца processor без смены, `MailetProcessorImpl` может перевести письмо в GHOST;
пустой quarantine processor не является удержанием. У default error processor
есть Bounce. Postgres repository сначала сохраняет MIME blob, затем метаданные
с upsert по URL/ключу; атомарность между blob/БД и поведение при падении не
доказаны. Поэтому для INFECTED/UNKNOWN требуются реальная конфигурация
удержания, проверка обработки ошибок и recovery, без bounce с вложением.
[MailetProcessorImpl](https://github.com/apache/james-project/blob/james-project-3.9.1/server/mailet/mailetcontainer-impl/src/main/java/org/apache/james/mailetcontainer/impl/MailetProcessorImpl.java),
[PostgresMailRepositoryContentDAO](https://github.com/apache/james-project/blob/james-project-3.9.1/server/data/data-postgres/src/main/java/org/apache/james/mailrepository/postgres/PostgresMailRepositoryContentDAO.java).
Лимит собственного INSTREAM не гарантирует ограничения памяти внутри самой
реализации MIME, таймаута источника, настроек clamd или размера входа SMTP.
