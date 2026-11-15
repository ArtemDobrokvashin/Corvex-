import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class TransitionProtocolsTests(unittest.TestCase):
    def test_transition_disables_all_native_protocol_listeners(self):
        paths = {'imapserver.xml':'imapservers', 'smtpserver.xml':'smtpservers',
                 'pop3server.xml':'pop3servers', 'lmtpserver.xml':'lmtpservers',
                 'managesieveserver.xml':'managesieveservers'}
        compose = (ROOT / 'docker-compose.yml').read_text(encoding='utf-8')
        for filename, root in paths.items():
            with self.subTest(filename=filename):
                tree = ET.parse(ROOT / 'infra/james/protocols' / filename)
                self.assertEqual(tree.getroot().tag, root)
                self.assertEqual(len(tree.getroot()), 0)
                if filename == 'smtpserver.xml':
                    self.assertIn('source: ${CORVEX_JAMES_SMTP_CONFIG_FILE:-./infra/james/protocols/smtpserver.xml}', compose)
                else:
                    self.assertIn('source: ./infra/james/protocols/' + filename, compose)
                self.assertIn('target: /root/conf/' + filename, compose)

if __name__ == '__main__': unittest.main()
