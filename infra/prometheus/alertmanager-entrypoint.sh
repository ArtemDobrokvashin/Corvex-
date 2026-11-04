#!/bin/sh
# .gitattributes keeps this bind-mounted script LF-only on Windows checkouts.
# Alertmanager owns grouping/silences and emits a one-minute delivery tick.
# User-facing repeat intervals are applied dynamically by notification-service
# and alert-relay, so UI changes never require a YAML rewrite or container restart.
# Channel credentials remain in alert-relay and never pass through this renderer.
set -eu

cp /etc/corvex/alertmanager.template.yml /alertmanager/alertmanager.yml
/bin/amtool check-config /alertmanager/alertmanager.yml
exec /bin/alertmanager "$@"
