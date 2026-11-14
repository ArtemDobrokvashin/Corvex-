"""Static checks on the disconnected candidate, not a James or AV integration test."""
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
CANDIDATE = ROOT / 'experiments/james-av-gate/mailetcontainer.candidate.xml'


class JamesAvPipelineCandidateTests(unittest.TestCase):
    def setUp(self):
        root = ET.parse(CANDIDATE).getroot()
        self.processors = {p.attrib['state']: p for p in root.findall('./processors/processor')}

    def test_gate_precedes_everything_in_root(self):
        root = self.processors['root']
        mailets = root.findall('mailet')
        self.assertEqual(mailets[0].attrib, {'match': 'All', 'class': 'ru.corvex.james.av.CorvexClamAVGate'})
        self.assertEqual(mailets[0].findtext('host'), 'clamav')
        self.assertEqual(mailets[0].findtext('port'), '3310')
        self.assertEqual(mailets[0].findtext('onMailetException'), 'propagate')
        self.assertEqual(mailets[-1].findtext('processor'), 'transport')

    def test_hold_processors_store_without_bounce(self):
        for state in ('corvex-av-infected', 'corvex-av-unknown'):
            with self.subTest(state=state):
                mailets = self.processors[state].findall('mailet')
                self.assertEqual(len(mailets), 1)
                self.assertEqual(mailets[0].attrib, {'match': 'All', 'class': 'ToRepository'})
                self.assertEqual(mailets[0].findtext('repositoryPath'), 'postgres://var/mail/' + state + '/')
                self.assertEqual(mailets[0].findtext('onMailetException'), 'propagate')
                self.assertNotEqual(mailets[0].findtext('passThrough'), 'true')

    def test_errors_do_not_bounce(self):
        mailets = self.processors['error'].findall('mailet')
        self.assertNotIn('Bounce', [m.get('class') for m in mailets])
        self.assertEqual(mailets[-1].get('class'), 'ToRepository')
        self.assertEqual(mailets[-1].findtext('onMailetException'), 'propagate')

    def test_normal_delivery_paths_still_present(self):
        transport = [m.get('class') for m in self.processors['transport'].findall('mailet')]
        relay = [m.get('class') for m in self.processors['relay'].findall('mailet')]
        self.assertIn('LocalDelivery', transport)
        self.assertIn('RemoteDelivery', relay)

    def test_candidate_is_not_connected_to_compose(self):
        compose = (ROOT / 'docker-compose.yml').read_text(encoding='utf-8')
        self.assertNotIn('mailetcontainer.candidate.xml', compose)
        self.assertNotIn('james-av-gate', compose)


if __name__ == '__main__':
    unittest.main()
