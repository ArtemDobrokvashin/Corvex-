#!/bin/sh
# Render only non-secret duration settings. Channel credentials stay in alert-relay's env.
# .gitattributes keeps this bind-mounted script LF-only on Windows checkouts.
set -e
set -u

repeat_interval="${CORVEX_ALERT_REPEAT_INTERVAL:-4h}"
critical_repeat_interval="${CORVEX_ALERT_CRITICAL_REPEAT_INTERVAL:-30m}"

validate_duration_characters() {
  value="$1"
  name="$2"
  case "$value" in
    ""|*[!0-9hmsdwy]*)
      echo "$name must be a Prometheus duration such as 30m, 4h or 1d" >&2
      exit 2
      ;;
  esac
}

validate_duration_characters "$repeat_interval" CORVEX_ALERT_REPEAT_INTERVAL
validate_duration_characters "$critical_repeat_interval" CORVEX_ALERT_CRITICAL_REPEAT_INTERVAL

sed \
  -e "s/__CORVEX_ALERT_REPEAT_INTERVAL__/$repeat_interval/g" \
  -e "s/__CORVEX_ALERT_CRITICAL_REPEAT_INTERVAL__/$critical_repeat_interval/g" \
  /etc/corvex/alertmanager.template.yml > /alertmanager/alertmanager.yml

# Alertmanager performs the authoritative schema and duration validation before startup.
/bin/amtool check-config /alertmanager/alertmanager.yml
exec /bin/alertmanager "$@"
