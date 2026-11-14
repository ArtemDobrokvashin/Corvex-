"""Static regression guards; not Docker/PowerShell or live-James tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = (ROOT / 'scripts/test-james-jmap-read-gateway.ps1').read_text(encoding='utf-8')
PROBE = (ROOT / 'scripts/JamesJmapGatewayLiveProbe.java').read_text(encoding='utf-8')
DOCKERFILE = (ROOT / 'infra/james/jmap-read-gateway/Dockerfile').read_text(encoding='utf-8')


class GatewayProbeGuards(unittest.TestCase):
    def test_script_only_recreates_jmap_and_has_restoration(self):
        self.assertIn('up -d --no-deps --no-build --force-recreate james-jmap', SCRIPT)
        self.assertIn('if ($Changed)', SCRIPT)
        self.assertIn('docker @Base up -d --no-deps --no-build --force-recreate james-jmap', SCRIPT)
        self.assertIn('com.docker.compose.config-hash', SCRIPT)
        self.assertIn("Probe 'read-only'", SCRIPT)
        self.assertNotIn('down -v', SCRIPT)
        self.assertNotIn('docker network connect', SCRIPT)

    def test_intent_before_proxy_recreation_and_no_retry(self):
        self.assertIn('prior gateway probe intent exists', SCRIPT)
        self.assertLess(SCRIPT.index("[IO.FileMode]::CreateNew"), SCRIPT.index('$Changed = $true'))
        self.assertLess(SCRIPT.index('$Changed = $true'), SCRIPT.index('docker @candidate up'))

    def test_probe_uses_synthetic_account_and_invalid_write_parameters(self):
        self.assertIn('cvx-[0-9a-f]{32}@localhost', PROBE)
        for method in ('Email/import', 'Email/set', 'Mailbox/set', 'EmailSubmission/set'):
            self.assertIn('"' + method + '"', PROBE)
        self.assertIn('addObject().put("invalidProbe", true)', PROBE)
        self.assertIn('Request rejected', PROBE)
        self.assertNotIn('BodyPublishers.ofFile', PROBE)

    def test_image_build_runs_unit_tests(self):
        self.assertIn('RUN node --test gateway.test.cjs', DOCKERFILE)
        self.assertIn('COPY --from=checked /app/gateway.cjs', DOCKERFILE)


if __name__ == '__main__':
    unittest.main()
