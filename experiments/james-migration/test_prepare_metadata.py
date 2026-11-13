import json
import tempfile
import unittest
from pathlib import Path
from prepare_metadata import prepare, InvalidManifest
from source_manifest import build

OWNER = 'a8e7caea-0c26-4d84-91ad-5e208d62b48c'
FOLDER = '6ce1ef07-1b52-49b7-b4da-064d26489321'
MSG = 'f61b2fbc-7419-4eb0-8992-35841d0c77af'

class OfflineExportTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.dump = self.root / 'export.jsonl'
        self.mapping = self.root / 'map.json'
        self.rows = [
            {'type':'header', 'version':1, 'snapshot_id':'fixture'},
            {'type':'folder','id':FOLDER,'owner_id':OWNER,'parent_id':None,'name':'Inbox','special_use':'INBOX'},
            {'type':'message','id':MSG,'owner_id':OWNER,'folder_id':FOLDER,'path':'mail.eml', 'size_bytes':3,
             'seen':True,'answered':False,'flagged':False,'draft':False,'deleted':False,
             'internal_date':'2026-01-01T00:00:00+00:00','av_status':'PENDING'},
            {'type':'summary','folders':1,'messages':1,'owner_mismatches':0}]
        self.map = {'version':1,'owners':{OWNER:'one@example.test'},'folders':{FOLDER:'INBOX'}}
        (self.root / 'mail.eml').write_bytes(b'abc')
        self.write()

    def tearDown(self): self.tmp.cleanup()
    def write(self):
        self.dump.write_text(''.join(json.dumps(x)+'\n' for x in self.rows), encoding='utf-8')
        self.mapping.write_text(json.dumps(self.map), encoding='utf-8')

    def test_end_to_end_export_to_source_manifest(self):
        meta = prepare(self.dump, self.mapping)
        path = self.root / 'metadata.json'
        path.write_text(json.dumps(meta), encoding='utf-8')
        record = build(path, self.root)['records'][0]
        self.assertEqual((record['mailbox'], record['folder'], record['flags'], record['size']),
                         ('one@example.test', 'INBOX', ['seen'], 3))
        self.assertNotIn('path', record)

    def test_database_size_mismatch_stops(self):
        self.rows[2]['size_bytes'] = 4
        self.write()
        path = self.root / 'metadata.json'
        path.write_text(json.dumps(prepare(self.dump, self.mapping)), encoding='utf-8')
        with self.assertRaises(InvalidManifest): build(path, self.root)

    def test_truncated_or_duplicate_records_rejected(self):
        self.rows[-1]['messages'] = 2
        self.write()
        with self.assertRaises(InvalidManifest): prepare(self.dump, self.mapping)
        self.rows[-1]['messages'] = 2
        self.rows.insert(-1, self.rows[2].copy())
        self.write()
        with self.assertRaises(InvalidManifest): prepare(self.dump, self.mapping)

    def test_wrong_owner_and_unmapped_folder_rejected(self):
        self.rows[2]['owner_id'] = 'c5b9ed02-cac9-4764-8709-009e9d08f76a'
        self.write()
        with self.assertRaises(InvalidManifest): prepare(self.dump, self.mapping)
        self.rows[2]['owner_id'] = OWNER
        self.map['folders'] = {}
        self.write()
        with self.assertRaises(InvalidManifest): prepare(self.dump, self.mapping)

    def test_unsafe_path_and_invalid_flags_rejected(self):
        self.rows[2]['path'] = '../outside'
        self.write()
        with self.assertRaises(InvalidManifest): prepare(self.dump, self.mapping)
        self.rows[2]['path'] = 'mail.eml'
        self.rows[2]['seen'] = 1
        self.write()
        with self.assertRaises(InvalidManifest): prepare(self.dump, self.mapping)

if __name__ == '__main__': unittest.main()
