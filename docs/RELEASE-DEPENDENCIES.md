# Corvex: зависимости и ворота первого релиз-кандидата

_Last reviewed: 20 October 2026._

Этот документ фиксирует, на каких версиях Corvex идёт к первому полному тесту,
какие исключения допущены осознанно и что запрещено называть «готовым к
production», пока не пройдены все ворота ниже. Его цель простая: зависимости не
обновляются хаотично, а ошибки не маскируются одновременной сменой нескольких
платформ.

## 1. Правила работы

1. **Lockfile обязателен.** Frontend ставится только `npm ci`; ручные правки
   `package-lock.json` запрещены.
2. **Нельзя `npm audit fix --force`** и массовые major-апгрейды одной командой.
   Сначала patch/minor, затем по одному major-пласту: тесты, build, audit,
   ручная проверка маршрутов.
3. **Один релизный пласт за раз.** Нельзя одновременно менять Spring, Jackson,
   James, базу данных и брокер: первая ошибка должна иметь одну вероятную
   причину.
4. **Поддерживаемая ветка важнее самой свежей цифры.** Для базы, брокера и JVM
   выбирается поддерживаемый LTS/стабильный ряд, а не сегодняшний `.0`, если
   тот ещё не получил patch-исправлений.
5. **Неиспользуемая зависимость удаляется**, а не обновляется «на всякий
   случай». Так убраны неиспользуемые Swagger annotations, GreenMail и прямая
   MIME4J-зависимость SMTP-сервиса.
6. **Исключение без срока и владельца не допускается.** Каждое исключение ниже
   имеет причину и ворота, на которых оно закрывается.

## 2. Frontend: закрытый и проверенный пласт

Текущий baseline:

- Node.js `^22.22.2 || ^24.15.0 || >=26.0.0` (нижняя граница задана jsdom 30; в Docker — актуальный Node 22);
- React/ReactDOM 19.3.1, React Router DOM 7.18.4;
- Vite 8.3.4, `@vitejs/plugin-react` 6.1.2, `@vitejs/plugin-legacy` 8.2.3;
- Vitest 5.0.5 + jsdom 30.1.3 для DOM-тестов;
- TypeScript 7.0.2;
- Marked 18.1.1, Zustand 5.0.15, DOMPurify 3.4.17 через lockfile;
- Markdown в demo- и реальном чате вынесен в один проверенный тестами путь
  `frontend/src/lib/markdown.ts`; прямые вызовы Marked/DOMPurify из страниц
  запрещены.

Проверено фактически:

- `npm test`: 4 файла, 15/15 тестов;
- `npm run build`: TypeScript, modern bundle и legacy bundle для Chrome 109;
- `npm audit --omit=dev`: **0 уязвимостей production-графа**.

### Осознанное исключение: Tailwind CSS 3.4 и Babel 7

Tailwind CSS 4 рассчитан на Chrome 111+, а Corvex обязан работать в Chrome 109
на Windows 7. Поэтому остаются Tailwind 3.4.19 и связанная legacy-цепочка
Babel 7. Полный `npm audit` показывает 7 build-time находок в цепочке Tailwind
(`braces`, `chokidar`, `fast-glob`, `micromatch`, `postcss-nested`,
`postcss-selector-parser`, `tailwindcss`). Эти пакеты не попадают в
production-граф; сборка должна выполняться только из доверенного lockfile и
доверенных исходников репозитория. Исключение закрывается одним из двух путей:
выход исправленной совместимой цепочки Tailwind 3 или отдельная миграция CSS
без потери Chrome 109. Случайные `overrides` и принудительный Tailwind 4
запрещены.

`@babel/core` закреплён в major 7 как явная build-зависимость: он нужен
legacy-трансформации и снимает peer-конфликт optional Babel-цепочки
`@vitejs/plugin-react` 6.

## 3. Backend: baseline первого compile-теста

Для первого настоящего Maven-прогона выбран последний patch/SR существующей
линии, а не одновременный прыжок через несколько major-платформ:

- Java 17, Spring Boot 3.2.12, Spring Cloud 2023.0.6;
- Apache James 3.8.2 в IMAP и POP3 (в POP3 добавлено отсутствовавшее
  `james.version`, без которого POM был неработоспособен);
- SMTP переведён с заброшенного `org.subethamail:subethasmtp` 3.1.7 (2012) на
  поддерживаемый форк `com.github.davidmoten:subethasmtp` 7.2.2; код адаптирован
  к Builder API, `String data(...)`, `Optional<AuthenticationHandler>` и
  трёхаргументному `UsernamePasswordValidator` по исходникам версии 7.2.2;
- dnsjava 3.6.5;
- JaCoCo 0.8.15.

Это **не** production-платформа: Spring Boot 3.2 и Spring Cloud 2023.0 уже вне
OSS-поддержки. Baseline нужен ровно для одного: получить первую честную
компиляцию, тесты и runtime-сигнатуру существующего кода без одновременной
миграции Jackson 3/Hibernate 7/Spring Security 7.

### Обязательный platform gate до пилота

После первого зелёного Maven/Docker baseline следующим отдельным релизным
пластом выполняется:

1. Spring Boot 4.1.x + совместимый Spring Cloud Oakwood;
2. миграция Jackson 2 → Jackson 3 и связанных Spring AMQP/HTTP-конвертеров;
3. проверка Hibernate/Spring Data/Spring Security/Gateway API тестами;
4. Apache James 3.8.2 → актуальный поддерживаемый 3.9.x с повторной сверкой
   каждого реализованного SPI;
5. повторный полный прогон Maven, Docker, webmail, чата и SMTP/IMAP/POP3.

До закрытого platform gate Corvex нельзя выпускать даже как «production
pilot», даже если baseline соберётся и заработает локально.

## 4. Docker-инфраструктура

Закреплены поддерживаемые ряды:

| Компонент | Версия | Примечание |
|---|---:|---|
| PostgreSQL | `postgres:16-alpine` | major 16 поддерживается до 2028 года; тег получает последний patch |
| Redis | `redis:7-alpine` | ряд 7.4 получает security-поддержку; миграция на 8.x отдельным gate |
| RabbitMQ | `4.3.6-management-alpine` | поддерживаемый 4.3; 3.13 убран |
| Elasticsearch | `8.19.23` | поддерживаемый ряд 8.19, совместимый с Java-клиентом major 8 |
| SpamAssassin | `4.0.2` | актуальный patch 4.0.x |
| ClamAV | `1.5.4` | актуальный security patch |
| nginx | `1.30-alpine` | stable-ветка |
| Prometheus | `v3.13.4` | LTS-ряд 3.13 |
| Alertmanager | `v0.34.1` | актуальный релиз |
| Grafana | `13.2.4` | актуальный patch поддерживаемого ряда |
| Loki | `3.7.8` | актуальный patch |
| Promtail | `3.6.11` | последний ряд перед EOL; только optional `monitoring` profile |

Promtail достиг EOL и не является production-решением. Его последний patch
допустим лишь для первого optional monitoring smoke. До production-мониторинга
Promtail заменяется на Grafana Alloy с конвертацией `infra/loki/promtail.yml` и
проверкой доставки Docker-логов в Loki.

## 5. Ворота релиза

### G0 — статика и frontend (пройдено для этого кандидата)

- frontend tests/build, production audit 0;
- Java syntax, POM/XML/YAML/JSON, Markdown links;
- Compose: все build-контексты существуют;
- для каждого POM нет неопределённых локальных свойств.

### G1 — Maven на ПК

- `mvn -DskipTests compile` по всем 12 сервисам;
- затем тесты по одному сервису в порядке первой ошибки;
- `mvn package` без отключения JaCoCo и без массовых понижений версий.

### G2 — Docker и первый запуск

- `docker compose config`, build всех образов;
- `.env`, сертификаты, first-run super-admin;
- все healthcheck зелёные, логи без restart loop.

### G3 — функциональная приёмка

- вход, refresh, 2FA, RBAC и аудит;
- webmail и реальный чат после рестарта контейнеров;
- SMTP submission/relay, IMAP и POP3 в Thunderbird;
- backup → чистая среда → restore.

### G4 — поддерживаемая платформа

- Spring Boot 4.1/Spring Cloud/Jackson 3;
- James 3.9.x;
- Promtail → Alloy;
- повтор G1–G3 без сокращений.

### G5 — пилот и production

- Windows 7 + Chrome 109 на реальной VM;
- SPF/DKIM/DMARC, PTR, внешняя доставляемость и abuse-сценарии;
- нагрузка на целевой объём пользователей, security review, CSP;
- документированный rollback и проверенный restore из резервной копии.

## 6. Каденс обновлений после baseline

- Security patch: вне очереди, отдельным PR, с полным G0 и релевантным G1/G2.
- Patch/minor: ежемесячно, группами frontend/backend/Docker.
- Major: Dependabot автоматически не предлагает. Такая миграция готовится
  вручную одним отдельным изменением, проходит сверку API и отдельный gate;
  десятки однотипных PR по каталогам сервисов не создаются.
- Каждый PR с зависимостями обязан указывать: что изменилось, какие тесты
  реально запущены, какой audit-результат получен и какое исключение осталось.
