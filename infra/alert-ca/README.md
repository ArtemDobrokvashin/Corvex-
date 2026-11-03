# Public CA bundles for alert-relay

Place only additional **public** CA certificate bundles here, for example a chain required
by the MAX API. Reference a file from `.env` as
`CORVEX_ALERT_EXTERNAL_CA_FILE=/etc/corvex/alert-ca/<bundle>.pem`.

The directory is mounted read-only and is separate from `infra/certs`: never put Corvex
private keys, PKCS12 bundles, bot tokens, or SMTP passwords here. PEM files are ignored by
Git; protect local copies with appropriate filesystem permissions.
