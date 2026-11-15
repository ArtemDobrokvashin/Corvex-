"""Portability guard for the PowerShell helpers that must run on Windows PowerShell 5.1.

Windows PowerShell 5.1 parses a script before executing it, so a single invalid
token such as ``"failed at $stage: ..."`` (interpreted as a scoped variable
reference) prevents any safety check from running. These checks are static only.
"""
from pathlib import Path
import re
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
SCOPES = {'env', 'global', 'script', 'local', 'private', 'using', 'variable', 'workflow'}
# $name: outside a ${...} expression is only valid for a scope qualifier.
INVALID_COLON_REFERENCE = re.compile(r'\$(?!\{)([A-Za-z_][A-Za-z0-9_]*):')


class PowerShellPortabilityTest(unittest.TestCase):
    def scripts(self):
        return sorted(SCRIPTS.glob('*.ps1'))

    def test_scripts_exist(self):
        self.assertGreater(len(self.scripts()), 20)

    def test_no_invalid_colon_variable_references(self):
        failures = []
        for path in self.scripts():
            text = path.read_text(encoding='utf-8', errors='replace')
            for match in INVALID_COLON_REFERENCE.finditer(text):
                if match.group(1) in SCOPES:
                    continue
                line = text[:match.start()].count('\n') + 1
                failures.append(f'{path.name}:{line}: ${match.group(1)}:')
        self.assertEqual([], failures, 'Wrap the name as ${name} or rephrase the string')

    def test_james_av_scripts_are_ascii(self):
        for name in ('drill-james-av-rollback.ps1', 'prepare-james-av-runtime.ps1',
                     'enable-james-av.ps1', 'rollback-james-av.ps1', 'test-av-nested-archive.ps1'):
            with self.subTest(script=name):
                raw = (SCRIPTS / name).read_bytes()
                self.assertTrue(all(byte < 128 for byte in raw), f'{name} must stay ASCII for PowerShell 5.1')


if __name__ == '__main__':
    unittest.main()
