"""Structural checks; not a live ClamAV or Java execution test."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class NestedArchiveProbeGuard(unittest.TestCase):
    def test_synthetic_limits_controls_and_no_mail(self):
        source = (ROOT / 'src/AvNestedArchiveProbe.java').read_text(encoding='utf-8')
        self.assertIn('DEEP_LEVELS = 25', source)
        self.assertIn('MAX_BYTES = 128 * 1024', source)
        self.assertIn('verifyNested(deep, DEEP_LEVELS)', source)
        self.assertIn('!raw.equals("EICAR_FOUND") || !zip.equals("EICAR_FOUND")', source)
        self.assertIn('deepResult.equals("CLEAN")', source)
        self.assertIn('System.exit(3)', source)
        self.assertIn('Not a James gate test', source)
        self.assertNotIn('java.net.Socket', source)  # uses existing scanner client

    def test_wrapper_has_exclusive_temp_and_no_compose_mutation(self):
        raw = (ROOT / 'test-av-nested-archive.ps1').read_bytes()
        text = raw.decode('ascii')
        self.assertIn("docker compose ps --status running -q mail-storage-service", text)
        self.assertIn('docker exec $container mkdir $remote', text)
        self.assertIn('$remoteOwned = $true', text)
        self.assertIn("^/tmp/corvex-av-nested-[0-9a-f]{32}$", text)
        self.assertIn('AvNestedArchiveProbe clamav 3310', text)
        self.assertNotIn('docker compose up', text)
        self.assertNotIn('docker compose down', text)
        self.assertNotIn('docker volume', text)
        self.assertNotIn('docker exec $container rm', text)


if __name__ == '__main__':
    unittest.main()
