"""Checks a synthetic PostgreSQL metadata export and synthetic file; no real data."""
import json
import os
from pathlib import Path
import tempfile
import prepare_metadata
import source_manifest
import compare_manifests


def main():
    if os.environ.get('JAMES_LAB_CONFIRM') != 'synthetic-only':
        raise SystemExit('Synthetic-only confirmation required')
    data = b'Synthetic fixture\r\n'
    mapping = {'version': 1,
               'owners': {'a8e7caea-0c26-4d84-91ad-5e208d62b48c': 'user01@james.local'},
               'folders': {'6ce1ef07-1b52-49b7-b4da-064d26489321': 'INBOX'}}
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        (root / 'fixture.eml').write_bytes(data)
        mapping_file = root / 'mapping.json'
        mapping_file.write_text(json.dumps(mapping), encoding='utf-8')
        metadata = prepare_metadata.prepare(Path('/lab/source-export.jsonl'), mapping_file)
        metadata_file = root / 'metadata.json'
        metadata_file.write_text(json.dumps(metadata), encoding='utf-8')
        manifest = source_manifest.build(metadata_file, root)
        if len(manifest['records']) != 1:
            raise RuntimeError('Unexpected synthetic export record count')
        item = manifest['records'][0]
        if item['source_id'] != 'f61b2fbc-7419-4eb0-8992-35841d0c77af' or item['flags'] != ['seen'] or item['size'] != len(data):
            raise RuntimeError('Synthetic metadata did not match expected fixture')
        result = compare_manifests.compare(manifest['records'], [{**item, 'target_id': 'synthetic-target'}])
        if result['missing'] or result['unexpected'] or any(result['mismatches'].values()):
            raise RuntimeError('Synthetic comparison failed')
    print('PASS: synthetic PostgreSQL metadata export and file manifest')

if __name__ == '__main__':
    main()
