# Monitoring notifications: recipients, frequency, categories, and external channels

## Independent delivery paths

Alertmanager fans every alert group out to two webhooks:

```text
Prometheus → Alertmanager
              ├─→ notification-service → RabbitMQ → SMTP → internal email
              └─→ alert-relay → external SMTP / Telegram / MAX

Control plane (not the delivery path):
admin UI → admin-service/admin_db → internal config API → both consumers
```

External delivery by the dependency-free Python `alert-relay` does not use PostgreSQL,
RabbitMQ, Java services, or Corvex SMTP. The relay fetches configuration from admin-service
but caches the last validated document in `alert-relay-data` with mode `0600`. Once loaded,
an admin-service outage does not disable external delivery. Before the first successful
configuration load, `.env` remains the fallback.

The relay publishes no host port. Its `monitoring` and `app` networks carry the two webhooks
and the internal config API; they do not expose the relay to browsers or external clients.

This is **not an external watchdog**. A total host, Docker, network, Alertmanager, or relay
failure still requires monitoring from another location. Corvex does not claim that case is
covered by this sidecar.

## Super-admin UI (primary method)

Open **Administration → Settings → Alerts and delivery**. The panel requires the
`SUPER_ADMIN` group in addition to `ADMIN_SERVER_CONFIG`; delegated server-config permission
alone receives 403.

The panel applies real runtime policy:

- **Where:** internal recipients/sender, independent external SMTP, Telegram, and MAX.
  External channels are opt-in.
- **How often:** separate regular and `critical` repeat intervals, from 1 to 43200 minutes.
  New incidents and `resolved` notifications do not wait for a repeat interval.
- **Which failures:** `ServiceDown`, `HighHttp5xxRate`, `MailQueueBacklog`, `DiskSpaceLow`,
  and `JvmMemoryPressure`. Disabled categories are filtered by both consumers. Unknown
  future categories remain enabled by default.

Click **Apply alert settings**. The service atomically increments `revision` in
`admin_db.alert_runtime_config` and writes `ALERT_SETTINGS_CHANGED` to the audit log. No YAML
edit or container restart is needed for a settings change.

The **Actual runtime application** block reports each consumer:

- `APPLIED`: the loaded revision equals the saved revision;
- `PENDING`: the consumer still reports another revision, normally until the next 10-second
  refresh;
- `UNAVAILABLE`: the consumer or monitoring profile cannot be reached; this is never shown
  as success.

`APPLIED` proves policy loading, not delivery of a particular external message. Verify actual
external delivery separately as described below.

## Secrets and .env fallback

SMTP passwords and Telegram/MAX tokens are **write-only**. Public GET returns only
`configured` and `source` (`UI`, `ENVIRONMENT`, or `NONE`). A blank secret input keeps the
current value. A saved UI secret can be cleared to fall back to `.env`; disabling the channel
is a separate operation.

New UI secrets are stored in `admin_db` only as AES-256-GCM ciphertext. They require a
separate key:

```dotenv
CORVEX_ALERT_CONFIG_ENC_KEY=<Base64-encoded 32 random bytes>
```

`scripts/first-run.ps1` generates this key for new installations and adds only a missing key
to an existing `.env`, without overwriting other secrets. Never put the key in CLI arguments
or chat. Losing it requires re-entering UI alert secrets; protect it together with the `.env`
backup.

The relay's effective cache is included in backup/restore. Older backups without that archive
remain usable: the relay can fetch the configuration again from `admin_db` after restoration.

Existing `.env` values remain supported as the initial/fallback configuration. Once a database
revision exists, non-secret values come from the UI; a secret not replaced in the UI can still
come from `.env`.

## Repeat semantics

Environment fallback values are:

```dotenv
CORVEX_ALERT_REPEAT_INTERVAL=4h
CORVEX_ALERT_CRITICAL_REPEAT_INTERVAL=30m
```

Durations such as `15m`, `1h`, `6h`, and `1d` are supported. The UI uses ordinary minutes.
Alertmanager emits a one-minute **delivery tick**; this does not mean one email per minute.
Notification-service and the relay apply the effective repeat policy independently per
channel. After a partial failure, the next tick retries only the failed channel instead of
duplicating successful channels.

Initial delivery still depends on the Prometheus rule's `for`, `group_wait: 30s`, and
`group_interval: 5m` for updates to an existing group. `Resolved` bypasses repeat throttling
and clears incident state. Alertmanager retains grouping and silences.

## Temporary silence

Silences suppress new notifications in **both** channels while leaving monitoring and
dashboards active. They persist in `alertmanager-data` and expire automatically.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 silence -Minutes 60
powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 silence -Minutes 120 -Service chat-service:8088
powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 status
powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 unsilence -SilenceId <uuid>
```

One silence is bounded to 30 days. It cannot recall messages already sent or events already
queued. `status` reads the effective relay revision, repeat intervals, and disabled categories,
not just the `.env` fallback.

## Channels

### Internal email

The path is `notification-service → RabbitMQ → SMTP`. Enter comma-separated recipients. This
path depends on Corvex mail infrastructure, so configure an external channel for mail-outage
alerts.

### External SMTP

Use an independent provider or server, not `mail-smtp-service`. Plain SMTP is deliberately
unsupported: select `starttls` (normally port 587) or `ssl` (normally port 465). Username may
be empty only if the provider permits unauthenticated relay; TLS is always required.

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

### Telegram

Create a dedicated bot with BotFather and set its chat/channel ID (group IDs may be negative)
and token through the UI or the fallback.

For a personal recipient, use the **numeric user ID**, for example `123456789`, not a username
or `@username`. For a group or channel, also use the numeric ID, for example
`-1001234567890`. This version accepts numeric IDs only and rejects `@channelname`. The user
must start the bot first. The UI shows these examples directly in its field placeholders.

```dotenv
CORVEX_ALERT_TELEGRAM_ENABLED=true
CORVEX_ALERT_TELEGRAM_BOT_TOKEN=<token>
CORVEX_ALERT_TELEGRAM_CHAT_ID=<chat-id>
```

Telegram requires the token in the URL path. The relay never logs URLs, response bodies, or
the token. Redirects are not followed, so credentials and alert text cannot be forwarded to
an unexpected URL.

### MAX

MAX uses the official `https://platform-api2.max.ru/messages` endpoint and the
`Authorization` header, never a query token. Set exactly one chat ID or user ID.
Both are numeric IDs without `@` or a username; the UI placeholder shows `123456789`:

```dotenv
CORVEX_ALERT_MAX_ENABLED=true
CORVEX_ALERT_MAX_BOT_TOKEN=<token>
CORVEX_ALERT_MAX_CHAT_ID=<chat-or-channel-id>
CORVEX_ALERT_MAX_USER_ID=
```

If MAX requires the Russian Ministry of Digital Development CA chain, put the public PEM
bundle in `infra/alert-ca` (or `CORVEX_ALERT_CA_DIR`) and set, for example:

```dotenv
CORVEX_ALERT_EXTERNAL_CA_FILE=/etc/corvex/alert-ca/mincifry-chain.pem
```

The bundle is added to system roots. The read-only directory contains no Corvex private keys.
Messenger text is bounded to 3900 characters; MAX calls are serialized with at least 0.55
seconds between sends.

## Deploying the code update

After pulling a new Corvex version, rebuild the changed images and start monitoring:

```powershell
docker compose up -d --build admin-service notification-service frontend
docker compose --profile monitoring up -d --build alert-relay alertmanager
```

This installs the **new code**; it is not required for every UI settings change. Do not pass
passwords, JWTs, or encryption keys in commands. Existing volumes are preserved.

## Verifying delivery

Alerts from one webhook are combined into one external message. Partial success does not
make Alertmanager retry and duplicate a delivered channel; the failed channel gets the next
delivery tick. Total failure returns HTTP 502 for retry.

Use the bounded synthetic alert for the internal path:

```powershell
py -3 scripts/alert_e2e.py --host mail.company.local --username admin@company.local --ca-file C:\corvex-pki\corvex-test-ca.crt --wait-seconds 180 --report artifacts\smoke\alert-e2e.json
```

Before creating the alert, it reads the notification-service effective snapshot and verifies
that the internal channel is enabled and `--username` is in `internalRecipients` (not the stale
`.env`). Its JSON PASS proves the **internal IMAP path only**. Confirm external delivery in the actual
external mailbox, Telegram, or MAX and inspect sanitised relay errors. Do not stop a
production service merely to generate a test alert.

## Diagnostics without secrets

```powershell
powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 status
docker compose logs --tail 100 alertmanager alert-relay notification-service admin-service
```

`refresh=FAILED` means the latest configuration refresh failed; the previous valid cache is
still active. Never publish `.env`, rendered Compose environment, SMTP passwords, bot tokens,
or complete external HTTP requests. Channel name, revision, HTTP status, and sanitised error
are sufficient for a report.
