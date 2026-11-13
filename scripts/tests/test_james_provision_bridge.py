"""Static guards only; they do not validate nginx, TLS, Compose or WebAdmin live."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]

class JamesProvisionBridgeTest(unittest.TestCase):
    def test_bridge_is_opt_in_internal_and_restricted(self):
        compose = (ROOT / 'docker-compose.yml').read_text(encoding='utf-8')
        block = compose.split('\n  james-admin:\n', 1)[1].split('\n  mail-smtp-service:', 1)[0]
        self.assertIn('profiles: [james-provision]', block)
        self.assertIn('networks: [app, james-internal]', block)
        self.assertNotIn('ports:', block)
        self.assertIn('CORVEX_JAMES_PROVISION_ENABLED: ${CORVEX_JAMES_PROVISION_ENABLED:-false}', compose)
        self.assertIn('CORVEX_JAMES_WEBADMIN_FILE:-${CORVEX_JAMES_CONFIG_DIR:-./infra/secrets/james}/webadmin.properties', compose)
        proxy = (ROOT / 'infra/james/provision-nginx.conf').read_text(encoding='utf-8')
        for expected in ('ssl_verify_client on;', 'ssl_client_certificate /run/james-provision/client-ca.crt;',
                         'access_log off;', 'limit_except PUT { deny all; }',
                         'if ($args != "") { return 400; }',
                         'if ($request_uri !~ "^/users/cvx-[0-9a-f]{32}@localhost$") { return 400; }',
                         'proxy_pass_request_headers off;',
                         'location / { return 404; }', 'http://james:8000',
                         '^/users/cvx-[0-9a-f]{32}@localhost$'):
            self.assertIn(expected, proxy)
        self.assertNotIn('proxy_pass http://james:8000;', proxy.split('location / {', 1)[-1])

    def test_key_setup_refuses_overwrite_and_does_not_start_docker(self):
        text = (ROOT / 'scripts/prepare-james-provision-laptop.ps1').read_text(encoding='utf-8')
        self.assertIn('[IO.FileMode]::CreateNew', text)
        self.assertIn('if (Test-Path $Root)', text)
        self.assertIn('if (Test-Path (Join-Path $Adapter $name))', text)
        self.assertIn('if (-not $ConfirmLaptop)', text)
        self.assertNotIn('& docker ', text.lower())
        self.assertNotIn('docker compose ', text.lower())
        self.assertIn('infra/secrets/*', (ROOT / '.gitignore').read_text(encoding='utf-8'))

    def test_start_script_limits_scope_and_keeps_api_disabled(self):
        text = (ROOT / 'scripts/start-james-provision-bridge.ps1').read_text(encoding='utf-8')
        self.assertIn("$env:CORVEX_JAMES_PROVISION_ENABLED = 'false'", text)
        self.assertIn('up -d --no-deps --no-build --force-recreate james', text)
        self.assertIn('up -d --no-deps --no-build --force-recreate james-jmap james-admin', text)
        self.assertIn('test-james-provision-mtls.ps1', text)
        self.assertNotIn('down -v', text.lower())
        self.assertNotIn('prune', text.lower())


if __name__ == '__main__':
    unittest.main()
