# Corvex — Detailed Installation & Run Guide

> Русская версия: [INSTALL.md](INSTALL.md) · Short version — in the [README](../README.md).
> ⚠️ Read [`READINESS.md`](READINESS.md) first: what already works vs. roadmap stages.
> Do **not** point production MX records at the server until roadmap stages 2–3 are complete.

Written for a Windows administrator. Commands are PowerShell, except inside the
Linux VM (bash). Docker commands are identical on every host.

---

## 0. Before you start — checklist

- [ ] Server: Windows Server 2019/2022; minimum 8 CPU / 16 GB RAM / 500 GB SSD,
      recommended 16 CPU / 32 GB / 1 TB SSD. A Windows 10/11 workstation with
      16 GB RAM is enough to evaluate.
- [ ] A domain name for web + mail (examples use `company.local`, web host
      `mail.company.local`). Real inbound mail needs a public domain.
- [ ] (For real mail) a static public IP and an ISP that allows inbound **and
      outbound TCP 25** — verify this first, many ISPs block it.
- [ ] Admin access to the server and to the DNS zone.
- [ ] An **external** mailbox for monitoring alerts (not on this server).

## 1. Network & firewall

Only six ports are published. Forward them on your perimeter and allow them in
Windows Defender Firewall:

| Port | Purpose |
|---|---|
| 443 | Web UI, API, chat WebSocket |
| 80 | Redirect to HTTPS + Let's Encrypt issuance |
| 25 | Inbound mail from the Internet (SMTP listener is OFF by default — step 8) |
| 587 | Mail submission from clients (SMTP AUTH) |
| 993, 143 | IMAP (roadmap stage 3) |
| 995, 110 | POP3 (roadmap stage 3) |

```powershell
443,80,25,587,993,143,995,110 | ForEach-Object {
  New-NetFirewallRule -DisplayName "Corvex $_" -Direction Inbound -Protocol TCP -LocalPort $_ -Action Allow
}
```

PostgreSQL, Redis, RabbitMQ and Elasticsearch are **never** published — they live
on an internal Docker network with no host ports.

## 2. Install Docker

### Option A — production on Windows Server: a Linux VM in Hyper-V (recommended)

Docker Desktop is not supported on Windows Server, and Linux containers do not
run natively on Windows. The supported production path is a lightweight Linux VM:

1. Enable Hyper-V (Server Manager → Add Roles, or
   `Install-WindowsFeature Hyper-V -IncludeManagementTools -Restart`).
2. Create a Gen-2 VM: Ubuntu Server 24.04 LTS, vCPU = all but 2, RAM = all but
   4 GB, 500 GB–1 TB dynamic disk on SSD, NIC on an **external** virtual switch.
3. Install Ubuntu (tick OpenSSH server), then inside the VM:

```bash
sudo apt update && sudo apt install -y ca-certificates curl git
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER   # log out and back in afterwards
docker --version && docker compose version
```

Continue inside the VM over SSH; Appendix A of the Russian guide has the Linux
equivalent of the PowerShell init script.

### Option B — pilot on Windows 10/11: Docker Desktop

Install Docker Desktop (WSL2 backend) and give it at least 6 CPU / 12 GB RAM in
Settings → Resources (Elasticsearch, ClamAV and 12 JVMs need it).

### Option C — plain Linux server

Simplest to operate if a Windows host is not mandatory: Option A step 3, no Hyper-V.

## 3. Get the source code

```powershell
git clone https://github.com/ArtemDobrokvashin/Corvex-.git C:\Corvex
cd C:\Corvex
git checkout arena/3732db07-corvex
```

Or download the branch ZIP from GitHub and unpack to `C:\Corvex`.

## 4. First-run initialisation: secrets, JWT key, pilot certificate

Do **not** copy `.env.example` manually — the script creates `.env` for you with
a *different* random secret per variable, generates the RSA JWT signing key, and
creates a 90-day self-signed certificate for pilots:

```powershell
Set-ExecutionPolicy -Scope Process Bypass   # if script execution is restricted
.\scripts\first-run.ps1
```

Expect three `[OK]` lines. Verify `infra\secrets\jwt_private.pem`,
`infra\certs\fullchain.pem` and `.env` exist.

⚠️ Secrets from `.env` are applied when the data volumes are **first created**.
Changing `POSTGRES_PASSWORD` later in `.env` alone will not change the password
inside PostgreSQL. On a blank test stand you can start over with
`docker compose down -v` (destroys volumes and data).

## 5. Configure `.env`

| Variable | Set to |
|---|---|
| `CORVEX_DOMAIN` | web host, e.g. `mail.company.local` |
| `CORVEX_MAIL_DOMAIN` | mail domain of addresses, e.g. `company.local` |
| `CORVEX_SMTP_ENABLED` | keep `false` until stage 2 (data-loss guard, see READINESS) |
| `CORVEX_LDAP_URL`, `CORVEX_LDAP_BASE` | fill if you have Active Directory (stage 5), otherwise leave |
| `CORVEX_ALERT_RECIPIENTS` | the admin's **external** mailbox |
| `ALERT_SMTP_HOST` | SMTP used for alerts — ideally an external relay |

## 6. Real TLS certificate (required before any external access)

The self-signed certificate from step 4 is for internal pilots only.

- **Public domain:** Let's Encrypt (`win-acme` on Windows, certbot in the VM);
  place files as `infra\certs\fullchain.pem` (full chain) and `infra\certs\privkey.pem`.
- **Internal domain:** a certificate from your corporate CA (AD CS); the root CA
  must be trusted on all user machines (usually via GPO already).

The certificate name is `mail.<your-domain>` — it serves web, IMAP and POP3.
Set the same name in `server_name` in `infra\nginx\conf.d\corvex.conf`.

## 7. DNS records

One A record is enough for a web-only pilot. For the future mail stages prepare:

```dns
mail.company.local.      A      203.0.113.10
company.local.           MX 10  mail.company.local.
company.local.           TXT    "v=spf1 mx ~all"        ; tighten to -all in production
corvex1._domainkey       TXT    "v=DKIM1; k=rsa; p=<public key from admin panel, stage 2>"
_dmarc.company.local.    TXT    "v=DMARC1; p=none; rua=mailto:dmarc@company.local"
autoconfig.company.local.   CNAME mail.company.local.
_imaps._tcp.company.local.  SRV 0 1 993 mail.company.local.
_submission._tcp.company.local. SRV 0 1 587 mail.company.local.
```

Ask your ISP for a **PTR (reverse DNS)** record for the public IP pointing to
`mail.company.local` — without it large providers will reject your mail.

## 8. First launch

```powershell
docker compose up -d --build
```

The first build of 12 Java services + frontend takes 10–30 minutes. Then:

```powershell
docker compose ps        # wait until every service is (healthy)
docker compose --profile monitoring up -d
```

ClamAV downloading signature databases on first start (up to ~5 minutes) is
normal. For problems: `docker compose logs --tail 100 <service>`.

Grafana: `https://mail.<domain>/grafana/` (admin subnet allowlist in the nginx
config — adjust it), user `admin`, password from `GRAFANA_PASSWORD` in `.env`.

## 9. Post-launch smoke tests

1. **Web UI opens:** `https://mail.<domain>` → Corvex login. Signing in with any
   credentials currently enters the clearly labelled **demo mode** (login API is
   stage 1): mail, chat and admin screens are fully clickable on sample data.
2. **Gateway alive:** `curl.exe -k https://mail.<domain>/api/v1/auth/me` → `401` expected.
3. **JWKS:** `docker compose exec web-gateway wget -qO- http://auth-service:8081/oauth2/jwks` → JSON public key.
4. **Migrations applied:** `docker compose logs auth-service | Select-String Flyway` (repeat for admin/chat/mail-storage services).
5. **SMTP intentionally off:** `docker compose logs mail-smtp-service` contains
   `SMTP listener disabled`. This is correct until stage 2.
6. **Alerting:** `docker compose stop chat-service` → within 2–4 minutes an email
   `[Corvex critical] ServiceDown` should reach `CORVEX_ALERT_RECIPIENTS`
   (requires a working `ALERT_SMTP_HOST`). Restore with `docker compose start chat-service`.

## 10. Day-2 operations

```powershell
docker compose ps                          # health of everything
docker compose logs -f --tail 100          # all logs (or Loki in Grafana)
docker compose restart <service>           # restart one service
docker compose up -d --build <service>     # rebuild one service after a code update
docker compose down                        # stop everything (data in volumes is kept)
```

Updating Corvex: `git pull` → `docker compose up -d --build` (Flyway applies DB
migrations automatically). Rollback: restore previous commit/image tags and
`up -d`; migrations are additive-only so the schema survives rollback.

## 11. Backups

1. Schedule daily 02:30 in Task Scheduler:
   `powershell -ExecutionPolicy Bypass -File C:\Corvex\scripts\backup.ps1 -BackupRoot D:\CorvexBackup`
2. Store copies on **different physical media** — a backup on the same SSD does
   not survive disk death or ransomware.
3. The script saves: dumps of all 4 PostgreSQL databases, archives of mail /
   attachment / chat-file volumes, `.env`, certificates and the JWT key.
   The Elasticsearch index is excluded on purpose (rebuildable from the DBs).
4. Rehearse a full restore quarterly. An untested backup is not a backup.

## 12. Pre-exposure security checklist

- [ ] No `change-me...` values left in `.env`
- [ ] Real TLS certificate (step 6), not the pilot self-signed one
- [ ] nginx `server_name` and the `/grafana/` subnet allowlist adjusted
- [ ] Alert email verified end-to-end (smoke test 6)
- [ ] Backups running, at least one copy off-server
- [ ] `CORVEX_SMTP_ENABLED=false` and no production MX published until stage 2 is done

## Troubleshooting

| Symptom | Check / fix |
|---|---|
| Maven build fails for a service | Run `docker compose build <service>` in foreground and read the first compile error; report it back to the repository |
| Container unhealthy/restarting | `docker compose logs --tail 100 <service>` — usually a secret changed in `.env` after volumes were initialised (fix inside the service, or `down -v` on a blank stand), or not enough RAM |
| nginx: `cannot load certificate` | Files missing in `infra\certs` — run step 4 or 6 |
| ClamAV takes very long | First run downloads ~200 MB of signatures; offline — load databases into the `clamav-data` volume manually |
| Elasticsearch restart-loops | Needs ≥2.5 GB RAM; on a Linux VM also `sudo sysctl -w vm.max_map_count=262144` (persist in `/etc/sysctl.conf`) |
| Yellow "demo mode" banner in the UI | Expected at the current stage (no login API yet); disappears after stage 1 |
| External mail never arrives | By design for now: stage 2 is pending and SMTP is disabled. Afterwards: ports 25 reachable, MX/PTR correct, mail-tester.com ≥ 9/10 |
