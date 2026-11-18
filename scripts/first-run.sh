#!/usr/bin/env bash
#
# Corvex first-run initializer for Linux (run ONCE, before the first 'docker compose up'):
#   1) generates RSA key pairs for JWT and DKIM signing (infra/secrets),
#   2) creates a 90-day self-signed TLS certificate for pilots (infra/certs),
#   3) creates .env from .env.example with a DIFFERENT random secret per variable.
#
# Parity with scripts/first-run.ps1: same files, same variables, same guarantees.
# Differences are deliberate and documented in docs/INSTALL.md:
#   - no Docker required: keys are made with the host openssl, not a throwaway container;
#   - ownership 100:101 is applied only when root or passwordless sudo is available.
#
# Safe to re-run: existing keys/certs/.env are never overwritten.
#
# Usage:
#   ./scripts/first-run.sh
#   ./scripts/first-run.sh --domain mail.company.ru --mail-domain company.ru
#
set -euo pipefail
cd "$(dirname "$0")/.."

DOMAIN=""
MAIL_DOMAIN=""
PUBLIC_BASE_URL=""

while [ $# -gt 0 ]; do
  case "$1" in
    --domain) DOMAIN="${2:-}"; shift 2 ;;
    --mail-domain) MAIL_DOMAIN="${2:-}"; shift 2 ;;
    --public-base-url) PUBLIC_BASE_URL="${2:-}"; shift 2 ;;
    -h|--help)
      sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

if ! command -v openssl >/dev/null 2>&1; then
  echo "openssl не найден. Установите: sudo apt install -y openssl" >&2
  exit 1
fi
if [ ! -f .env.example ]; then
  echo "Запускайте из корня репозитория: .env.example не найден." >&2
  exit 1
fi

mkdir -p infra/certs infra/secrets infra/secrets/dkim

random_secret() { openssl rand -hex 24; }
random_key_b64() { openssl rand -base64 32 | tr -d '\n'; }

# --- 1. JWT signing key (RS256) ---
if [ ! -f infra/secrets/jwt_private.pem ]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 \
    -out infra/secrets/jwt_private.pem 2>/dev/null
  openssl rsa -in infra/secrets/jwt_private.pem -pubout \
    -out infra/secrets/jwt_public.pem 2>/dev/null
  echo "[OK] JWT key pair: infra/secrets/jwt_private.pem (+ jwt_public.pem)"
else
  echo "[skip] infra/secrets/jwt_private.pem already exists"
fi

# --- 1b. DKIM signing key (selector corvex1) ---
if [ ! -f infra/secrets/dkim_private.pem ]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
    -out infra/secrets/dkim_private.pem 2>/dev/null
  openssl rsa -in infra/secrets/dkim_private.pem -pubout \
    -out infra/secrets/dkim_public.pem 2>/dev/null
  echo "[OK] DKIM key pair: infra/secrets/dkim_private.pem (+ dkim_public.pem)"
else
  echo "[skip] infra/secrets/dkim_private.pem already exists"
fi
if [ -f infra/secrets/dkim_public.pem ]; then
  DKIM_PUBLIC_KEY="$(grep -v -- '-----' infra/secrets/dkim_public.pem | tr -d '\n')"
  echo "[DNS] corvex1._domainkey TXT \"v=DKIM1; k=rsa; p=${DKIM_PUBLIC_KEY}\""
fi

# --- 2. Pilot self-signed TLS (replace with Let's Encrypt / corporate CA for production) ---
if [ ! -f infra/certs/fullchain.pem ]; then
  openssl req -x509 -newkey rsa:2048 \
    -keyout infra/certs/privkey.pem -out infra/certs/fullchain.pem \
    -days 90 -nodes -subj '/CN=mail.company.local' 2>/dev/null
  echo "[WARN] Self-signed certificate created (90 days, CN=mail.company.local) - PILOT ONLY. See docs/INSTALL.md step 6."
else
  echo "[skip] infra/certs/fullchain.pem already exists"
fi

# --- 2b. Permissions for the non-root runtime user (corvex = 100:101 in every Java image) ---
chmod 600 infra/secrets/jwt_private.pem infra/secrets/dkim_private.pem \
          infra/certs/privkey.pem 2>/dev/null || true
chmod 644 infra/secrets/jwt_public.pem infra/secrets/dkim_public.pem \
          infra/certs/fullchain.pem 2>/dev/null || true

# CORVEX_FIRST_RUN_NO_CHOWN=1 skips ownership changes: used by the unit test and by
# restricted environments where the caller cannot sudo. The keys stay readable by the
# invoking user, which is fine for a dry run but NOT for a real installation.
if [ "${CORVEX_FIRST_RUN_NO_CHOWN:-0}" = "1" ]; then
  echo "[skip] ownership 100:101 not applied (CORVEX_FIRST_RUN_NO_CHOWN=1)."
elif chown 100:101 infra/secrets/jwt_private.pem infra/secrets/dkim_private.pem \
                infra/certs/privkey.pem 2>/dev/null \
  || sudo -n chown 100:101 infra/secrets/jwt_private.pem infra/secrets/dkim_private.pem \
                     infra/certs/privkey.pem 2>/dev/null; then
  echo "[OK] Key/certificate permissions set for container user 100:101."
else
  echo "[WARN] Не удалось выставить владельца 100:101 (нужен root или sudo без пароля)."
  echo "       Если контейнер не прочитает ключи: sudo chown 100:101 infra/secrets/*.pem infra/certs/privkey.pem"
fi

# --- 3. .env with distinct random secrets (parity with first-run.ps1) ---
if [ ! -f .env ]; then
  : > .env
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      *=change-me-strong-password) printf '%s=%s\n' "${line%%=*}" "$(random_secret)" ;;
      CORVEX_TOTP_ENC_KEY=*|CORVEX_EXTERNAL_MAIL_ENC_KEY=*|CORVEX_ALERT_CONFIG_ENC_KEY=*)
        printf '%s=%s\n' "${line%%=*}" "$(random_key_b64)" ;;
      *) printf '%s\n' "$line" ;;
    esac
  done < .env.example > .env
  echo "[OK] .env created with unique random secrets."
else
  echo "[WARN] .env already exists - secrets NOT regenerated. Delete .env and re-run BEFORE the first 'docker compose up' if it still has placeholders."

  # Upgrade path: installs created before these keys existed get only the missing ones.
  for name in CORVEX_INTERNAL_API_TOKEN CORVEX_EXTERNAL_MAIL_ENC_KEY CORVEX_ALERT_CONFIG_ENC_KEY CORVEX_TOTP_ENC_KEY; do
    if ! grep -q "^${name}=" .env; then
      printf '\n%s=%s\n' "$name" "$(random_key_b64)" >> .env
      echo "[OK] Added missing ${name} to existing .env."
    fi
  done
  # Replace leftover placeholders of the AES-GCM keys: they are not valid Base64 and
  # make TOTP, external mail and alert secrets return 503 on a fresh install.
  for name in CORVEX_TOTP_ENC_KEY CORVEX_EXTERNAL_MAIL_ENC_KEY CORVEX_ALERT_CONFIG_ENC_KEY; do
    if grep -q "^${name}=base64-32-byte-key-for-aes-gcm$" .env; then
      sed -i "s|^${name}=base64-32-byte-key-for-aes-gcm$|${name}=$(random_key_b64)|" .env
      echo "[OK] Replaced placeholder ${name} with a real 32-byte key."
    fi
  done
  if grep -q "^ALERT_SMTP_HOST=smtp.provider.example$" .env; then
    sed -i 's|^ALERT_SMTP_HOST=smtp.provider.example$|ALERT_SMTP_HOST=mail-smtp-service|' .env
    echo "[OK] Replaced placeholder ALERT_SMTP_HOST with mail-smtp-service. Set an external alert relay before production."
  fi
fi

# --- 4. Optional non-interactive domain setup ---
set_env() {
  local name="$1" value="$2" current
  current="$(grep "^${name}=" .env | head -1 | cut -d= -f2- || true)"
  if [ -z "$current" ] || [ "$current" = "mail.company.local" ] || [ "$current" = "company.local" ]; then
    sed -i "s|^${name}=.*|${name}=${value}|" .env
    echo "[OK] ${name}=${value}"
  else
    echo "[skip] ${name} already set to '${current}'"
  fi
}
[ -n "$DOMAIN" ] && set_env CORVEX_DOMAIN "$DOMAIN"
[ -n "$MAIL_DOMAIN" ] && set_env CORVEX_MAIL_DOMAIN "$MAIL_DOMAIN"
[ -n "$PUBLIC_BASE_URL" ] && set_env CORVEX_PUBLIC_BASE_URL "$PUBLIC_BASE_URL"

echo ""
echo "Next steps:"
echo "  1) Edit .env - set CORVEX_DOMAIN / CORVEX_MAIL_DOMAIN (and CORVEX_LDAP_* if you have Active Directory)"
echo "  2) docker compose up -d --build"
echo "  3) docker compose --profile monitoring up -d   (optional monitoring stack)"
echo "  4) Verify: docker compose ps   -> all services healthy"
