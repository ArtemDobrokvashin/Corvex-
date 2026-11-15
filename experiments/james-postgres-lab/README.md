# Изолированная лаборатория James + PostgreSQL

Только синтетические данные, не подключена к Corvex. У конфигурации отдельная
внутренняя сеть и отдельные именные тома Compose, нет опубликованных портов.
Тестовые пароли публичны, JMAP Basic по HTTP без TLS и генерация demo-keystore
небезопасны вне этого стенда. WebAdmin отключён. Не использовать для реальных
ящиков/паролей и не подключать к рабочим сетям или томам. Не удалять тома,
особенно не применять `down -v`.

Стартовая точка: upstream James postgres-app 3.9.1 single-node sample,
PostgreSQL 16.9, blob postgres и `implementation=scanning`. Upstream прямо
отмечает, что scanning не для production. Тег `apache/james:postgres-3.9.1`,
реальный состав, работоспособность и совместимость ещё не проверены в Docker.
Никаких выводов о готовности, производительности, интеграции или сохранности.

`jmap_probe.py` проверяет анонимный отказ, отдельные JMAP-сессии двух
фиксированных демо-пользователей и запрет чтения чужой учётной записи через
`Mailbox/get`; отчёт PASS/FAIL/SKIP, никаких содержимых писем в выводе. Он не
проверяет интеграцию с auth-service, 2FA, отзыв, TLS, AV, доставку или сбои.
Локальные unit-тесты с имитацией: `python3 -m unittest discover -s
experiments/james-postgres-lab -v`.

Два пользователя `user01@james.local` и `user02@james.local` должны быть
созданы отдельно через James CLI для синтетического теста; в отличие от
предыдущего demo-образа, это не выполняется автоматически данным Compose.
Сначала зафиксировать запуск и готовность, затем provisioning и тест. Ни один
шаг не должен затрагивать существующий Compose проекта Corvex.

Источники: [upstream compose 3.9.1](https://github.com/apache/james-project/blob/james-project-3.9.1/server/apps/postgres-app/docker-compose.yml),
[search.properties](https://github.com/apache/james-project/blob/james-project-3.9.1/server/apps/postgres-app/sample-configuration-single/search.properties),
[blob.properties](https://github.com/apache/james-project/blob/james-project-3.9.1/server/apps/postgres-app/sample-configuration/blob.properties),
[JMAP SessionRoutes](https://github.com/apache/james-project/blob/james-project-3.9.1/server/protocols/jmap-rfc-8621/src/main/scala/org/apache/james/jmap/routes/SessionRoutes.scala).

Для IMAP/SMTP suite используется `../james-lab/probe.py`: на этой сборке выбрать
`JAMES_LAB_SMTP_MODE=submission` (порт 587, STARTTLS и demo auth). Публичный
самоподписанный сертификат в эксперименте не проверяется; это не production TLS.
Без этого параметра inbound-вариант теста получает 530 Authentication Required.

Сводная JMAP-проверка также создаёт синтетическое письмо с вложением через IMAP,
затем ищет его через JMAP и проверяет subject и признак наличия вложения. Это
проверяет видимость между протоколами, но не проверяет байты вложения через JMAP,
Corvex API или интеграцию идентичности. Для этого требуется существующий
`../james-lab/probe.py`; запуск только в лабораторном Compose.

Проверка IMAP → JMAP дополнительно скачивает синтетическое вложение через
`/download/{accountId}/{blobId}` и сравнивает байты; второй лабораторный
пользователь не должен скачать вложение чужого аккаунта. Это не тест Java
клиента или JWT Corvex. Маркеры и сообщения синтетические, останутся в
лабораторном ящике. Для публичных данных и production не использовать.

### Подготовка live Java/JWT проверки (ещё не запускалась)

В `services/mail-storage-service/.../james/JamesLiveMailboxTest.java` подготовлен
отдельный opt-in тест для синтетического James с Bearer JWT и проверкой TLS.
Он отключён по умолчанию; требует `JAMES_LIVE_CONFIRM=synthetic-only`, HTTPS
origin на loopback, доверенный лабораторный сертификат в отдельном JVM truststore
и локальный PKCS#8 DER тестовый ключ в `JAMES_LIVE_TEST_KEY_PKCS8`. В нём только
две фиксированные учётные записи `user01/02@james.local`. Проверяются сессии,
папки, чужой аккаунт, неверная подпись и истёкший токен. Для него ещё нужно
создать изолированный HTTPS endpoint, выпустить отдельные ключи и включить JWT
в одноразовом James; текущий postgres-lab использует HTTP/Basic и этот тест
запускать против него нельзя. Сертификат и закрытый ключ не хранить в Git и не
использовать для реальной почты. Live проверка Corvex RBAC/2FA/отзыва, а также
миграции и доставки в этом тесте отсутствует.
