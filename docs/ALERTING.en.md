# Monitoring notifications: repeat policy, silences, and external channels

## Independent delivery paths

Alertmanager fans every alert group out to two independent webhooks:

```text
Prometheus → Alertmanager
              ├─→ notification-service → RabbitMQ → Corvex SMTP → internal email
              └─→ alert-relay → external SMTP / Telegram / MAX
```

The dependency-free Python `alert-relay` does not use PostgreSQL, RabbitMQ, Java services,
or Corvex SMTP. It publishes no host port and is reachable only on the `monitoring`
Docker network. A failure of notification-service, RabbitMQ, or Corvex mail therefore does
not block an external channel while Docker, Alertmanager, and the relay remain available.

This is **not an external watchdog**. A total host, Docker, network, Alertmanager, or relay
failure requires monitoring from another location (hosted health checks or an independent
Uptime Kuma/watchdog). Corvex does not claim that case is covered by this sidecar.

## Repeat intervals

Set normal and critical reminders separately in `.env`:

```dotenv
CORVEX_ALERT_REPEAT_INTERVAL=4h
CORVEX_ALERT_CRITICAL_REPEAT_INTERVAL=30m
```

Prometheus durations such as `15m`, `1h`, `6h`, and `1d` are supported and validated with
`amtool check-config` before startup. `repeat_interval` controls reminders while an alert
stays active; it does not delay initial detection. Initial delivery still depends on the
rule's `for`, `group_wait: 30s`, and `group_interval: 5m` for updates to an existing group.

Apply an interval change to Alertmanager only:

```powershell
docker compose --profile monitoring up -d --force-recreate alertmanager
powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 status
```

## Temporary silence

Silences suppress new notifications in **both** internal and external channels while
leaving monitoring and dashboards active. They persist in `alertmanager-data` and expire
automatically.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 silence -Minutes 60
powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 silence -Minutes 120 -Service chat-service:8088
powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 status
powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 unsilence -SilenceId <uuid>
```

One silence is bounded to 30 days. It cannot recall email/events already sent or queued.

## External SMTP

Use an independent provider or mail server, not `mail-smtp-service`. Plain unencrypted
SMTP is deliberately unsupported; use STARTTLS (normally port 587) or implicit TLS
(normally port 465):

```dotenv
CORVEX_ALERT_EXTERNAL_EMAIL_ENABLED=true
CORVEX_ALERT_EXTERNAL_EMAIL_RECIPIENTS=admin@example.net,backup@example.net
CORVEX_ALERT_EXTERNAL_SMTP_HOST=smtp.example.net
CORVEX_ALERT_EXTERNAL_SMTP_PORT=587
CORVEX_ALERT_EXTERNAL_SMTP_TLS_MODE=starttls
CORVEX_ALERT_EXTERNAL_SMTP_USERNAME=corvex-alert@example.net
CORVEX_ALERT_EXTERNAL_SMTP_PASSWORD=<app-password>
CORVEX_ALERT_EXTERNAL_SMTP_FROM=corvex-alert@example.net
```

The legacy `CORVEX_ALERT_RECIPIENTS` and `ALERT_SMTP_HOST` configure only internal email.
Keep app passwords in the protected `.env`, never in CLI arguments or chat.

## Telegram and MAX

```dotenv
CORVEX_ALERT_TELEGRAM_ENABLED=true
CORVEX_ALERT_TELEGRAM_BOT_TOKEN=<token>
CORVEX_ALERT_TELEGRAM_CHAT_ID=<chat-id>

CORVEX_ALERT_MAX_ENABLED=true
CORVEX_ALERT_MAX_BOT_TOKEN=<token>
CORVEX_ALERT_MAX_CHAT_ID=<chat-or-channel-id>
CORVEX_ALERT_MAX_USER_ID=
```

Create a dedicated Telegram bot with BotFather and set its chat/channel ID. For MAX, set
exactly one of `CHAT_ID` and `USER_ID`. MAX uses the official
`https://platform-api2.max.ru/messages` endpoint and the `Authorization` header; the token
is never placed in the URL. Telegram requires its bot token in the URL path, so the relay
sanitizes all outgoing HTTP errors and never logs URLs or response bodies. Redirects are
not followed, so credentials and alert text cannot be forwarded to an unexpected URL.

If MAX requires the Russian Ministry of Digital Development CA chain, put the public PEM
bundle in the separate `infra/alert-ca` (or `CORVEX_ALERT_CA_DIR`) and set, for example:

```dotenv
CORVEX_ALERT_EXTERNAL_CA_FILE=/etc/corvex/alert-ca/mincifry-chain.pem
```

The file is added to system trust roots rather than replacing them. This read-only
directory does not expose private keys from `infra/certs`. Messenger text is
bounded to 3900 characters; MAX calls are serialized to respect its two-messages/second
per-chat limit.

## Apply and verify

All external channels are opt-in. Recreate only the relay after changing its settings:

```powershell
docker compose --profile monitoring up -d --build --force-recreate alert-relay
docker compose ps alert-relay alertmanager
docker compose logs --tail 50 alert-relay
```

Alerts from one Alertmanager webhook are combined into one external message. Channels are
attempted independently. Partial success does not make Alertmanager retry and duplicate an
already delivered channel; total failure returns HTTP 502 for retry.

The existing `scripts/alert_e2e.py` creates one bounded synthetic alert without stopping
services. Its JSON PASS proves the internal IMAP path only. Confirm external delivery in
the actual external mailbox/Telegram/MAX separately, and check relay logs for sanitised
delivery errors. Do not stop a production service merely to generate a test alert.
