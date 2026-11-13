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
