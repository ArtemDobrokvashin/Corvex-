"""Synthetic scanner tests: no clamd, Docker, mail or network is used.

Runs with javac/java on PATH. Alternatively set CORVEX_TEST_JAVA and
CORVEX_TEST_ECJ to a Java runtime and Eclipse compiler jar. ECJ runs in Java 17
language mode against its runtime, not javac's --release API compatibility.
"""
import email
import email.policy
import io
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
STUB = r'''
package ru.corvex.mailstorage.av;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
public class ClamAvClient {
 public enum Result { CLEAN, INFECTED, ERROR }
 public record ScanResult(Result result, String signature) { }
 private int i;
 public ClamAvClient(String host, int port, int a, int b) { }
 public ScanResult scan(InputStream stream) {
  try { Files.write(Path.of(System.getenv("CAPTURE"), Integer.toString(i)), stream.readAllBytes()); }
  catch (Exception e) { throw new RuntimeException(e); }
  String value = System.getenv("VERDICTS").split(",")[i++];
  return switch (value) {
   case "CLEAN" -> new ScanResult(Result.CLEAN, null);
   case "ERROR" -> new ScanResult(Result.ERROR, null);
   case "LIMIT" -> new ScanResult(Result.INFECTED, "Heuristics.Limits.Exceeded.MaxRecursion");
   case "OTHER" -> new ScanResult(Result.INFECTED, "Some.Other.Signature");
   default -> new ScanResult(Result.INFECTED, "Eicar-Test-Signature");
  };
 }
}
'''


class NestedProbeBehaviorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.java = os.environ.get('CORVEX_TEST_JAVA') or shutil.which('java')
        javac = shutil.which('javac')
        ecj = os.environ.get('CORVEX_TEST_ECJ')
        if not cls.java or not (javac or ecj):
            raise unittest.SkipTest('Java and javac or CORVEX_TEST_ECJ required')
        cls.temp = tempfile.TemporaryDirectory(prefix='corvex-nested-probe-')
        cls.root = Path(cls.temp.name)
        stub = cls.root / 'ru/corvex/mailstorage/av/ClamAvClient.java'
        stub.parent.mkdir(parents=True)
        stub.write_text(STUB, encoding='utf-8')
        cls.classes = cls.root / 'classes'
        cls.classes.mkdir()
        if ecj:
            command = [cls.java, '-jar', ecj, '-17']
        else:
            command = [javac, '--release', '17']
        try:
            subprocess.run(command + ['-d', str(cls.classes), str(stub),
                str(ROOT / 'scripts/src/AvNestedArchiveProbe.java')], check=True, capture_output=True, text=True)
        except Exception:
            cls.temp.cleanup()
            raise

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_probe(self, verdicts, expected):
        env = dict(os.environ, CAPTURE=str(self.root), VERDICTS=verdicts)
        result = subprocess.run([self.java, '-cp', str(self.classes), 'AvNestedArchiveProbe', 'clamav', '3310'],
                                capture_output=True, text=True, env=env, timeout=30)
        self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
        return result.stdout

    def test_verdicts_and_controls(self):
        cases = [
            ('EICAR,EICAR,EICAR,LIMIT,LIMIT', 0, 'both tested deep samples'),
            ('EICAR,EICAR,EICAR,CLEAN,ERROR', 3, 'WARNING'),
            ('EICAR,EICAR,CLEAN,LIMIT,LIMIT', 3, 'WARNING'),
            ('EICAR,EICAR,EICAR,ERROR,LIMIT', 2, 'INCONCLUSIVE'),
            ('EICAR,EICAR,EICAR,OTHER,LIMIT', 2, 'INCONCLUSIVE'),
            ('ERROR,EICAR', 2, 'INCONCLUSIVE'),
        ]
        for values, code, message in cases:
            with self.subTest(values=values):
                self.assertIn(message, self.run_probe(values, code))

    def test_mime_attachments_recover_exact_eicar(self):
        self.run_probe('EICAR,EICAR,EICAR,LIMIT,LIMIT', 0)
        eicar = (b'X5O!P%@AP[4\\PZX54(P^)7CC)7}$'
                 b'EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*')
        for index, depth in ((2, 1), (4, 25)):
            message = email.message_from_bytes((self.root / str(index)).read_bytes(), policy=email.policy.default)
            attachments = list(message.iter_attachments())
            self.assertEqual(1, len(attachments))
            body = attachments[0].get_payload(decode=True)
            for _ in range(depth):
                with zipfile.ZipFile(io.BytesIO(body)) as archive:
                    names = archive.namelist()
                    self.assertEqual(1, len(names))
                    body = archive.read(names[0])
            self.assertEqual(eicar, body)


if __name__ == '__main__':
    unittest.main()


class NestedProbeScriptTest(unittest.TestCase):
    def setUp(self):
        self.script = (ROOT / 'scripts/test-av-nested-archive.ps1').read_bytes()
        self.text = self.script.decode('ascii')

    def test_script_is_ascii_and_requires_confirmation(self):
        self.assertIn("-not $ConfirmLaptop", self.text)

    def test_script_rejects_incomplete_scanner_output(self):
        self.assertIn("mime_zip_depth_25=", self.text)
        self.assertIn('throw \'INCONCLUSIVE: probe output incomplete.\'', self.text)
        self.assertLess(self.text.index('probe output incomplete'), self.text.index('$probeExit -eq 3'))

    def test_script_does_not_mutate_compose_or_networks(self):
        for forbidden in ('compose up', 'compose down', 'compose restart', 'network create',
                          'docker rm', 'docker run', '--env'):
            self.assertNotIn(forbidden, self.text)
