import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path
from source_manifest import build, main
from compare_manifests import read_manifest, InvalidManifest

ID = 'f61b2fbc-7419-4eb0-8992-35841d0c77af'

class SourceManifestTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name) / 'snapshot'
        self.root.mkdir()
        (self.root / 'folder').mkdir()
        (self.root / 'folder' / 'mail.eml').write_bytes(b'a\x00b\r\n')
        self.file = Path(self.tmp.name) / 'metadata.json'
        self.row = {'source_id': ID, 'mailbox': 'user@example.test', 'folder': 'INBOX', 'path': 'folder/mail.eml', 'flags': ['seen']}
        self.write([self.row])

    def tearDown(self): self.tmp.cleanup()
    def write(self, rows):
        self.file.write_text(json.dumps({'version': 1, 'snapshot_id': 'fixture', 'records': rows}), encoding='utf-8')

    def test_build_and_compare_format(self):
        manifest = build(self.file, self.root)
        row = manifest['records'][0]
        self.assertEqual(row['sha256'], hashlib.sha256(b'a\x00b\r\n').hexdigest())
        self.assertEqual(row['size'], 5)
        result_path = Path(self.tmp.name) / 'manifest.json'
        result_path.write_text(json.dumps(manifest), encoding='utf-8')
        self.assertEqual(len(read_manifest(result_path, 'source')), 1)
        self.assertNotIn('path', row)

    def test_reject_traversal_duplicate_and_missing(self):
        for rows in [[{**self.row, 'path': '../outside'}], [self.row, self.row], [{**self.row, 'path': 'missing'}]]:
            self.write(rows)
            with self.assertRaises((OSError, InvalidManifest)): build(self.file, self.root)

    @unittest.skipUnless(hasattr(os, 'symlink'), 'no symlink support')
    def test_reject_symlink_at_file_and_directory(self):
        (self.root / 'link').symlink_to(self.root / 'folder', target_is_directory=True)
        (self.root / 'folder' / 'link.eml').symlink_to(self.root / 'folder' / 'mail.eml')
        for path in ['link/mail.eml', 'folder/link.eml']:
            self.write([{**self.row, 'path': path}])
            with self.assertRaises(OSError): build(self.file, self.root)

    def test_bad_flags_and_nonregular_rejected(self):
        self.write([{**self.row, 'flags': ['seen', 'seen']}])
        with self.assertRaises(InvalidManifest): build(self.file, self.root)
        self.write([{**self.row, 'path': 'folder'}])
        with self.assertRaises(InvalidManifest): build(self.file, self.root)

if __name__ == '__main__': unittest.main()
