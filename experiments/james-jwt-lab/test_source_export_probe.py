import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'james-migration'))
spec = importlib.util.spec_from_file_location('source_export_probe', Path(__file__).with_name('source_export_probe.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class ExportProbeTests(unittest.TestCase):
    def test_refuses_without_confirmation(self):
        with patch.dict(os.environ, {}, clear=True), self.assertRaises(SystemExit):
            module.main()

    def test_synthetic_export_full_flow(self):
        owner = 'a8e7caea-0c26-4d84-91ad-5e208d62b48c'
        folder = '6ce1ef07-1b52-49b7-b4da-064d26489321'
        rows = [{'type':'header','version':1,'snapshot_id':'1:1:'},
                {'type':'folder','id':folder,'owner_id':owner,'parent_id':None,'name':'Inbox','special_use':'INBOX'},
                {'type':'message','id':'f61b2fbc-7419-4eb0-8992-35841d0c77af','owner_id':owner,'folder_id':folder,
                 'path':'fixture.eml','size_bytes':19,'seen':True,'answered':False,'flagged':False,'draft':False,
                 'deleted':False,'internal_date':'2026-01-01T00:00:00+00:00','av_status':'PENDING'},
                {'type':'summary','folders':1,'messages':1,'owner_mismatches':0}]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'data.jsonl'
            path.write_text(''.join(json.dumps(row)+'\n' for row in rows), encoding='utf-8')
            real = module.prepare_metadata.prepare
            with patch.dict(os.environ, {'JAMES_LAB_CONFIRM':'synthetic-only'}, clear=True), \
                 patch.object(module.prepare_metadata, 'prepare', side_effect=lambda _, m: real(path, m)), \
                 contextlib.redirect_stdout(io.StringIO()) as output:
                module.main()
            self.assertIn('PASS: synthetic PostgreSQL', output.getvalue())

if __name__ == '__main__': unittest.main()
