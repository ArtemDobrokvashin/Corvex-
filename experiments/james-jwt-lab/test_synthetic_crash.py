import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'james-migration'))
probe = Mock()
with patch.dict(sys.modules, {'probe': probe}):
    import synthetic_import
    spec = importlib.util.spec_from_file_location('synthetic_crash', Path(__file__).with_name('synthetic_crash.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

class CrashTests(unittest.TestCase):
    def test_recovery_blocks_retry_and_keeps_single_new_message(self):
        probe.reset_mock()
        probe.ok.side_effect = lambda result, _: result[1]
        probe.body.return_value = module.imp.RAW
        client = Mock()
        client.select.return_value = ('OK', [b'2'])
        client.uid.side_effect = [('OK', [b'1 2']), ('OK', [b'1 2'])]
        client.response.side_effect = [('UIDVALIDITY', [b'99']), ('APPENDUID', [None])]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'crash-before.json').write_text(json.dumps(['1']), encoding='utf-8')
            j = module.imp.Journal(root / 'crash.sqlite')
            try:
                j.register(module.SOURCE_ID, hashlib.sha256(module.imp.RAW).hexdigest(), len(module.imp.RAW))
                j.start(module.SOURCE_ID)
                with patch.object(module, 'ROOT', root), contextlib.redirect_stdout(io.StringIO()):
                    module.recover(client, j)
                self.assertEqual(j.state(module.SOURCE_ID), ('uncertain', None))
                client.append.assert_not_called()
            finally:
                j.close()

    def test_no_confirmation(self):
        with patch.dict(os.environ, {}, clear=True), self.assertRaises(SystemExit): module.main()

if __name__ == '__main__': unittest.main()
