#!/usr/bin/env python3
"""Validate an offline PostgreSQL export and explicit mapping for source_manifest."""
from __future__ import annotations
import argparse
import json
import sys
from collections import Counter
from pathlib import Path
from uuid import UUID
from compare_manifests import MAX_FILE, InvalidManifest, unique_object
from source_manifest import valid_metadata


def uuid(value):
    try:
        if str(UUID(value)) != value:
            raise ValueError()
    except (ValueError, TypeError, AttributeError):
        raise InvalidManifest('invalid UUID') from None
    return value


def name(value):
    if not isinstance(value, str) or not value or len(value) > 1024 or '\x00' in value:
        raise InvalidManifest('invalid mapped name')
    return value


def read_bounded(path):
    with path.open('rb') as f:
        data = f.read(MAX_FILE + 1)
    if len(data) > MAX_FILE:
        raise InvalidManifest('input exceeds limit')
    try:
        return data.decode('utf-8')
    except UnicodeDecodeError as e:
        raise InvalidManifest('invalid UTF-8') from e


def parse_json(text):
    try:
        return json.loads(text, object_pairs_hook=unique_object)
    except json.JSONDecodeError as e:
        raise InvalidManifest('invalid JSON') from e


def prepare(export_path, mapping_path):
    lines = read_bounded(export_path).splitlines()
    if len(lines) < 2 or any(not line for line in lines):
        raise InvalidManifest('incomplete export')
    rows = [parse_json(line) for line in lines]
    if not all(isinstance(r, dict) for r in rows):
        raise InvalidManifest('invalid export row')
    header, summary = rows[0], rows[-1]
    if set(header) != {'type', 'version', 'snapshot_id'} or header['type'] != 'header' or type(header['version']) is not int or header['version'] != 1:
        raise InvalidManifest('invalid header')
    snapshot = name(header['snapshot_id'])
    if len(snapshot) > 200:
        raise InvalidManifest('snapshot identifier too long')
    if set(summary) != {'type', 'folders', 'messages', 'owner_mismatches'} or summary['type'] != 'summary':
        raise InvalidManifest('invalid summary')
    if any(type(summary[x]) is not int or summary[x] < 0 for x in ('folders', 'messages', 'owner_mismatches')) or summary['owner_mismatches']:
        raise InvalidManifest('invalid counts or folder ownership mismatch')
    folders = {}
    messages = []
    seen_messages = set()
    folder_fields = {'type', 'id', 'owner_id', 'parent_id', 'name', 'special_use'}
    message_fields = {'type', 'id', 'owner_id', 'folder_id', 'path', 'size_bytes', 'seen', 'answered', 'flagged', 'draft', 'deleted', 'internal_date', 'av_status'}
    for row in rows[1:-1]:
        if row.get('type') == 'folder' and set(row) == folder_fields:
            folder_id, owner = uuid(row['id']), uuid(row['owner_id'])
            if folder_id in folders:
                raise InvalidManifest('duplicate folder')
            if row['parent_id'] is not None:
                uuid(row['parent_id'])
            name(row['name'])
            if row['special_use'] is not None:
                name(row['special_use'])
            folders[folder_id] = row
        elif row.get('type') == 'message' and set(row) == message_fields:
            mid = uuid(row['id'])
            uuid(row['owner_id']); uuid(row['folder_id'])
            if mid in seen_messages or type(row['size_bytes']) is not int or row['size_bytes'] < 0:
                raise InvalidManifest('duplicate message or invalid size')
            if any(type(row[f]) is not bool for f in ('seen', 'answered', 'flagged', 'draft', 'deleted')):
                raise InvalidManifest('invalid flags')
            name(row['internal_date']); name(row['av_status'])
            seen_messages.add(mid)
            messages.append(row)
        else:
            raise InvalidManifest('unknown row')
    if len(folders) != summary['folders'] or len(messages) != summary['messages'] or not messages:
        raise InvalidManifest('count mismatch or empty message scope')
    for folder in folders.values():
        parent = folder['parent_id']
        if parent is not None and (parent not in folders or folders[parent]['owner_id'] != folder['owner_id']):
            raise InvalidManifest('missing or foreign parent folder')
    mapping = parse_json(read_bounded(mapping_path))
    if not isinstance(mapping, dict) or set(mapping) != {'version', 'owners', 'folders'} or type(mapping['version']) is not int or mapping['version'] != 1:
        raise InvalidManifest('invalid mapping')
    owners, folder_names = mapping['owners'], mapping['folders']
    if not isinstance(owners, dict) or not isinstance(folder_names, dict):
        raise InvalidManifest('invalid mapping dictionaries')
    owner_ids = {f['owner_id'] for f in folders.values()}
    if set(owners) != owner_ids or set(folder_names) != set(folders):
        raise InvalidManifest('mapping scope must match every folder and owner')
    for key, value in owners.items(): uuid(key); name(value)
    for key, value in folder_names.items(): uuid(key); name(value)
    if len(set(owners.values())) != len(owners):
        raise InvalidManifest('multiple owners mapped to same mailbox')
    pairs = [(f['owner_id'], folder_names[fid]) for fid, f in folders.items()]
    if any(n > 1 for n in Counter(pairs).values()):
        raise InvalidManifest('multiple folders mapped to same destination')
    result = []
    for msg in messages:
        folder = folders.get(msg['folder_id'])
        if folder is None or folder['owner_id'] != msg['owner_id']:
            raise InvalidManifest('message folder ownership mismatch')
        row = {'source_id': msg['id'], 'mailbox': owners[msg['owner_id']],
               'folder': folder_names[msg['folder_id']], 'path': msg['path'],
               'expected_size': msg['size_bytes'], 'flags': [f for f in ('seen', 'answered', 'flagged', 'draft', 'deleted') if msg[f]]}
        valid_metadata(row)
        result.append(row)
    return {'version': 1, 'snapshot_id': snapshot, 'records': result}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('export', type=Path)
    parser.add_argument('mapping', type=Path)
    args = parser.parse_args(argv)
    try:
        result = prepare(args.export, args.mapping)
    except (OSError, InvalidManifest) as exc:
        print('ERROR: invalid offline export or mapping (' + type(exc).__name__ + ')', file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, separators=(',', ':')))
    print('WARNING: mappings and filesystem snapshot consistency are not verified.', file=sys.stderr)
    return 0

if __name__ == '__main__': sys.exit(main())
