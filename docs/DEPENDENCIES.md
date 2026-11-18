# Зависимости и версии

Зафиксированные версии текущего `main`: из POM, `package-lock.json`, Dockerfile и
`docker-compose.yml`, а не из предполагаемого обновления. Исторические результаты
проверок не переносятся на новые версии автоматически; состояние доказательств —
в [`STATUS.md`](STATUS.md).

## 1. Правила обновления

1. **Сначала исправления и зелёные ворота, потом новый baseline.** Major-upgrade не
   совмещается с нестабильной сборкой и большим количеством независимых правок.
2. Patch/minor проходят релевантные тесты. Major Spring, James, JDK или frontend — отдельным
   изменением с повтором G0–G3.
3. Не понижать JaCoCo и не отключать тесты ради зелёного результата. Не делать массовый
   downgrade по первой ошибке: сначала проверить точную сигнатуру API и первый `Caused by`.
4. `npm ci` обязателен для воспроизводимого frontend-графа. Docker build **не заменяет**
   `mvn verify`: сервисные Dockerfile собирают артефакты с `-DskipTests`.
5. Каждый отчёт о проверке содержит точный SHA, версии JDK/Maven/Node/Docker и первую ошибку.

## 2. Backend

| Компонент | Версия | Комментарий |
|---|---|---|
| JDK | **17** | все 13 Java-сервисов, `<java.version>17</java.version>`. Runtime: `eclipse-temurin:17-jre-alpine` у 12, `17-jre-jammy` у `office-preview-service` (нужен LibreOffice) |
| Maven build image | `maven:3.9-eclipse-temurin-17` | сборка внутри Dockerfile |
| Spring Boot parent | **3.5.16** | одинаковая версия во всех 12 POM |
| Spring Cloud BOM, gateway | **2025.0.3** | вместе с Boot 3.5 |
| Spring Cloud BOM, остальные | **2023.0.6** | остаточная неоднородность, унификация — в G4 |
| Apache James protocols | **3.8.2** | IMAP/POP3; JDK 17 сохраняется до G4 |
| SubEtha SMTP | `com.github.subethasmtp:subethasmtp:7.2.2` | поддерживаемый форк |
| dnsjava | **3.7.1** | MX/DNS; 3.7 закрывает GHSA 2026 (DoS на заголовках, TSIG, DoH) |
| Jakarta Mail / Angus | **2.1.5 / 2.0.5** | External Mail IMAP/POP3/SMTP |
| BouncyCastle `bcprov-jdk18on` | **1.87** | Argon2id для паролей и app-password |
| JaCoCo | **0.8.16** | coverage-гейт 70 % не понижался |

## 3. Frontend

| Компонент | Версия |
|---|---|
| Node.js `engines` | `^22.22.2 \|\| ^24.15.0 \|\| >=26.0.0`; npm `>=10` |
| React / React DOM | **19.3.1 / 19.3.1** |
| React Router DOM | **7.18.5** |
| TypeScript / Vite / Vitest | **7.0.2 / 8.3.5 / 5.0.5** |
| Autoprefixer / PostCSS | **10.6.2 / 8.5.32** |
| jsdom | **30.1.3** |
| Tailwind CSS | **3.4.19** |
| `@vitejs/plugin-legacy` / `@babel/core` | **8.2.3 / 7.29.7** |
| Zustand | **5.1.0** |
| Lucide React | **1.72.0** (переход на 2.x — отдельная major-миграция) |
| Marked / DOMPurify | **18.2.0 / 3.4.17** |

Осознанные исключения:

- **Tailwind 4** не применяется без доказательства Chrome 109: четвёртая версия использует
  более новые браузерные возможности.
- **Babel 8** — legacy-цепочка остаётся на Babel 7 до отдельной проверенной миграции.
- **Lucide 2.x** — отдельная major-миграция, а не попутное обновление.

## 4. Docker

| Компонент | Образ |
|---|---|
| PostgreSQL | `postgres:16-alpine` |
| Redis | `redis:7-alpine` |
| RabbitMQ | `rabbitmq:4.3.7-management-alpine` |
| Elasticsearch | `docker.elastic.co/elasticsearch/elasticsearch:8.19.25` |
| SpamAssassin | `grepular/spamassassin:4.0.1-5` (community-образ, официального Apache нет) |
| ClamAV | `clamav/clamav:1.5.4` |
| nginx | `nginx:1.31-alpine` |
| Prometheus | `prom/prometheus:v3.16.0` |
| Alertmanager | `prom/alertmanager:v0.35.0` |
| Grafana | `grafana/grafana:13.3.1` |
| Loki / Promtail | `grafana/loki:3.7.9` / `grafana/promtail:3.6.11` (до миграции на Alloy) |
| External alert relay | `python:3.13-alpine`, только stdlib, non-root UID 10001 |

**RabbitMQ 4.4 намеренно не включён.** Прямой переход с 4.3.6 в образе 4.4 одновременно
меняет RabbitMQ и Erlang/OTP 27→29; release notes рекомендуют сначала 4.3.7+. Кроме того,
новые classic-queue v2 файлы 4.4 нельзя откатить на 4.3.x с тем же томом. Нужен отдельный
апгрейд с бэкапом, проверкой очередей/DLQ/restart и планом отката.

**Promtail** — временный агент: он EOL, замена на Grafana Alloy входит в G4.

Проверенные breaking changes текущего батча: Alertmanager 0.35.0 ломает
`--enable-feature=auto-gomemlimit`, неименованные outputs `eventrecorder` и схему
`events/v2` — ничего из этого Corvex не использует (entrypoint вызывает только
`--config.file` и `--storage.path`, конфиг ограничен `route`, `receivers`,
`webhook_configs`, `inhibit_rules`). Prometheus 3.16 меняет экспериментальный search API,
remote-write метрики и валидацию exemplars — scrape-конфиг и retention-флаг не затронуты.

## 5. Dependabot

Настроены еженедельные сгруппированные minor/patch-обновления по понедельникам (Europe/Moscow)
для npm, Maven, Docker base images и Compose images. Major-версии не предлагаются,
`open-pull-requests-limit: 1` на ecosystem, James (minor/major) и RabbitMQ (minor)
заблокированы до согласованной миграции. Автоматические обновления тега `maven` отключены:
один тег связывает Maven и JDK.

Dependabot PR не смешиваются с функциональными и документационными PR.

## 6. Ворота

| Ворота | Что означает | Состояние |
|---|---|---|
| G0 | фронтенд и статические проверки: `npm ci`, тесты, modern + legacy build, audit 0, YAML/XML/JSON, локальные ссылки | ✅ на текущем `main`, повторять на каждом релизном SHA |
| G1 | `mvn -B clean verify` по 13 модулям reactor и parent | 🟡 затронутые модули собраны, полный reactor — обязательный следующий шаг |
| G2 | `docker compose config -q`, build, `up -d`, `ps` | 🟡 16/16 build-контекстов на существующих томах; чистая установка впереди |
| G3 | функциональная приёмка | 🚧 идёт, см. [`TESTING.md`](TESTING.md) |
| G4 | поддерживаемая платформа: согласованные Boot/Cloud/Jackson/James/JDK, Alloy вместо Promtail | ⏳ только отдельным изменением, с повтором G1–G3 |
| G5 | пилот: нагрузка, 72 часа, hardening, ограниченная группа ящиков | ⏳ до этого production MX и публичный SMTP запрещены |

Граница доказательств для текущего Maven-батча (dnsjava 3.7.1, bcprov 1.87, JaCoCo 0.8.16):
используемый Corvex API сверен по исходникам тега dnsjava v3.7.1 (`Lookup`,
`setCache(null)` → временный кэш, `ResolverConfig.server()/servers()`, `SimpleResolver`,
`TXTBase.getStrings()`, `Cache`, `Header.setFlag`); bcprov и JaCoCo обновлены в пределах
patch/minor без изменения кода. Полный `mvn verify` и Docker-сборка для этого батча —
обязательная проверка на PC, в песочнице JDK и Maven нет.
