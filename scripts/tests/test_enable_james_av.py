"""Static safeguards; not a Docker, PowerShell, James or rollback execution test."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class JamesAvEnableTest(unittest.TestCase):
    def test_activation_is_narrow_and_preflighted(self):
        text = (ROOT / 'scripts/enable-james-av.ps1').read_bytes().decode('ascii')
        for marker in ('if (-not $Apply)', 'Live mailet config changed.', 'Existing extension jars found',
                       'Compose differs from running', 'Required James volume missing:',
                       '2158891b662abcd527f56f24a92b10d5404d875fad6e83ed3d57135a3386884f',
                       'Compose backup mismatch.', 'deployment-manifest.json',
                       'docker network create --internal', 'docker network connect --alias clamav',
                       '--no-deps --no-build --pull never --force-recreate james',
                       'Original volume not attached:', 'Mounted mailet config mismatch.',
                       'not proof that the gate loaded'):
            self.assertIn(marker, text)
        self.assertLess(text.index("$stage = 'stage-backup'"), text.index("$stage = 'network'"))
        self.assertLess(text.index("$stage = 'network'"), text.index("$stage = 'recreate-james'"))
        for forbidden in ('docker compose down', 'docker volume rm', 'docker system prune', 'Remove-Item',
                          'docker compose up -d --no-deps --no-build clamav'):
            self.assertNotIn(forbidden, text)

    def test_local_compose_service_blocks_are_uniquely_identified(self):
        compose = (ROOT / 'docker-compose.yml').read_text(encoding='utf-8')
        pattern = r'(?ms)^  (james|clamav):[ \t]*(?:#.*)?\r?\n.*?(?=^  [A-Za-z0-9_-]+:[ \t]*(?:#.*)?\r?$|^[A-Za-z0-9_-]+:|\Z)'
        blocks = {m.group(1): m.group() for m in re.finditer(pattern, compose)}
        self.assertEqual(set(blocks), {'james', 'clamav'})
        self.assertEqual(blocks['james'].count('    networks: [james-internal]'), 1)
        self.assertEqual(len(re.findall(r'(?m)^    volumes:[ \t]*\r?\n(?:(?!^    [A-Za-z0-9_-]+:).*\r?\n)*    networks: \[james-internal\]', blocks['james'])), 1)
        self.assertEqual(blocks['clamav'].count('    networks: [app]'), 1)
        self.assertEqual(len(re.findall(r'(?m)^networks:[ \t]*\r?$', compose)), 1)

    def test_rollback_preserves_mail_and_volumes(self):
        text = (ROOT / 'scripts/rollback-james-av.ps1').read_bytes().decode('ascii')
        for marker in ('Compose changed independently', 'Compose backup unavailable',
                       '--no-deps --no-build --pull never --force-recreate james',
                       'Original volume not attached:', 'Messages processed or quarantined are not undone'):
            self.assertIn(marker, text)
        for forbidden in ('docker compose down', 'docker volume rm', 'docker network rm', 'Remove-Item'):
            self.assertNotIn(forbidden, text)


if __name__ == '__main__':
    unittest.main()
