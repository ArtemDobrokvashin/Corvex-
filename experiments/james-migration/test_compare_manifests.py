import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
import compare_manifests as c

ID = 'f61b2fbc-7419-4eb0-8992-35841d0c77af'
BASE = {'source_id': ID, 'mailbox': 'user@example.test', 'folder': 'Inbox', 'sha256': 'a' * 64, 'size': 20, 'flags': ['seen']}
TARGET = {**BASE, 'target_id': 'native-id'}

class CompareTests(unittest.TestCase):
    def test_equal_and_flag_order(self):
        a = {**BASE, 'flags': ['seen', 'flagged']}
        b = {**TARGET, 'flags': ['flagged', 'seen']}
        # File validation normalizes order.
        self.assertEqual(self.run_cli([a], [b]), 0)

    def test_missing_extra_and_mismatch(self):
        b = {**TARGET, 'sha256': 'b' * 64, 'folder': 'Other', 'flags': []}
        result = c.compare([BASE], [b])
        self.assertEqual(result['mismatches']['sha256'], 1)
        self.assertEqual(result['mismatches']['folder'], 1)
        self.assertEqual(result['mismatches']['flags'], 1)
        self.assertEqual(c.compare([BASE], [])['missing'], 1)
        self.assertEqual(c.compare([], [TARGET])['unexpected'], 1)
        self.assertEqual(self.run_cli([BASE], [b]), 1)

    def test_duplicate_mapping_and_target_ids(self):
        second = {**TARGET, 'source_id': 'e9769711-24f7-4d49-a938-134fe1f5f562'}
        self.assertEqual(c.compare([BASE, BASE], [TARGET, TARGET])['duplicate_source'], 1)
        self.assertEqual(c.compare([BASE], [TARGET, TARGET])['duplicate_mapping'], 1)
        self.assertEqual(c.compare([BASE], [TARGET, second])['duplicate_target_id'], 1)
        self.assertEqual(self.run_cli([BASE], [TARGET, TARGET]), 1)

    def test_invalid_fields_fail_closed(self):
        for field, value in [('source_id', 'not-uuid'), ('sha256', 'x' * 64), ('size', True), ('flags', ['seen', 'seen']), ('folder', '')]:
            with self.subTest(field=field):
                self.assertEqual(self.run_cli([{**BASE, field: value}], [TARGET]), 2)

    def test_empty_manifests_are_not_cutover_proof(self):
        self.assertEqual(self.run_cli([], []), 2)

    def run_cli(self, source, target):
        with tempfile.TemporaryDirectory() as tmp:
            paths = []
            for kind, records in [('source', source), ('target', target)]:
                path = Path(tmp) / (kind + '.json')
                path.write_text(json.dumps({'version': 1, 'kind': kind, 'snapshot_id': 'fixture', 'records': records}), encoding='utf-8')
                paths.append(str(path))
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                return c.main(paths)

if __name__ == '__main__': unittest.main()
