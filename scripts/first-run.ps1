<#
 Первичная инициализация на сервере: секреты, ключ подписи JWT (RS256), самоподписанный TLS для пилота.
 Для продакшена замените самоподписанный сертификат на Let's Encrypt / корпоративный (docs/08 шаг 6).
#>
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force infra\certs, infra\secrets | Out-Null
if (-not (Test-Path infra\secrets\jwt_private.pem)) {
  # openssl доступен из образа alpine, локальная установка на Windows Server не требуется
  docker run --rm -v "${PWD}\infra\secrets:/out" alpine sh -c "apk add --no-cache openssl >/dev/null && openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out /out/jwt_private.pem && openssl rsa -in /out/jwt_private.pem -pubout -out /out/jwt_public.pem"
}
if (-not (Test-Path infra\certs\fullchain.pem)) {
  docker run --rm -v "${PWD}\infra\certs:/out" alpine sh -c "apk add --no-cache openssl >/dev/null && openssl req -x509 -newkey rsa:2048 -keyout /out/privkey.pem -out /out/fullchain.pem -days 90 -nodes -subj '/CN=mail.company.local'"
  Write-Warning "Создан САМОПОДПИСАННЫЙ сертификат на 90 дней — только для пилота внутри сети."
}
function New-Secret { -join ((1..32) | ForEach-Object { [char](Get-Random -Min 33 -Max 126) }) }
if (-not (Test-Path .env)) {
  (Get-Content .env.example) -replace 'change-me-strong-password', (New-Secret) | Set-Content .env
  Write-Host "Создан .env со случайными секретами. Проверьте домены и LDAP, затем: docker compose up -d --build"
}
