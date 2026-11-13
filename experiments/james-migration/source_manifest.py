#!/usr/bin/env python3
"""Experimental offline snapshot manifest builder for Linux; no database access."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
import stat
import sys
from pathlib import Path
from uuid import UUID
from compare_manifests import FLAGS, MAX_FILE, InvalidManifest, unique_object


def valid_metadata(row):
    required = {'source_id', 'mailbox', 'folder', 'path', 'flags'}
    if not isinstance(row, dict) or set(row) not in (required, required | {'expected_size'}):
        raise InvalidManifest('invalid source row')
    if 'expected_size' in row and (type(row['expected_size']) is not int or row['expected_size'] < 0):
        raise InvalidManifest('invalid expected size')
    try:
        if str(UUID(row['source_id'])) != row['source_id']:
            raise ValueError()
    except (TypeError, ValueError, AttributeError):
        raise InvalidManifest('invalid source UUID') from None
    for name in ('mailbox', 'folder'):
        v = row[name]
        if not isinstance(v, str) or not v or len(v) > 1024 or '\x00' in v:
            raise InvalidManifest('invalid mailbox or folder')
    flags = row['flags']
    if not isinstance(flags, list) or any(not isinstance(f, str) or f not in FLAGS for f in flags) or len(flags) != len(set(flags)):
        raise InvalidManifest('invalid flags')
    path = row['path']
    if not isinstance(path, str) or not path or '\x00' in path or '\\' in path:
        raise InvalidManifest('invalid relative path')
    parts = path.split('/')
    if any(x in ('', '.', '..') for x in parts):
        raise InvalidManifest('invalid relative path')
    return parts


def hash_file(root_fd, parts):
    # Descriptor-relative no-follow open avoids traversing symlinks even if a
    # directory entry is replaced after validation. Linux offline snapshots only.
    if not hasattr(os, 'O_NOFOLLOW') or not hasattr(os, 'O_DIRECTORY') or os.open not in os.supports_dir_fd:
        raise InvalidManifest('requires descriptor-relative no-follow open')
    parent = os.dup(root_fd)
    try:
        for component in parts[:-1]:
            child = os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
            os.close(parent)
            parent = child
        fd = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
        try:
            before = os.fstat(fd)
            if not stat.S_ISREG(before.st_mode):
                raise InvalidManifest('message is not a regular file')
            digest = hashlib.sha256()
            size = 0
            while True:
                block = os.read(fd, 1024 * 1024)
                if not block:
                    break
                size += len(block)
                digest.update(block)
            after = os.fstat(fd)
            fields = ('st_dev', 'st_ino', 'st_size', 'st_mtime_ns', 'st_ctime_ns')
            if any(getattr(before, x) != getattr(after, x) for x in fields) or size != after.st_size:
                raise InvalidManifest('message changed while reading')
            return digest.hexdigest(), size
        finally:
            os.close(fd)
    finally:
        os.close(parent)


def build(metadata_path, root_path):
    with open(metadata_path, 'rb') as stream:
        raw = stream.read(MAX_FILE + 1)
    if len(raw) > MAX_FILE:
        raise InvalidManifest('metadata exceeds size limit')
    try:
        metadata = json.loads(raw.decode('utf-8'), object_pairs_hook=unique_object)
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        raise InvalidManifest('invalid metadata JSON') from e
    if not isinstance(metadata, dict) or set(metadata) != {'version', 'snapshot_id', 'records'} or type(metadata['version']) is not int or metadata['version'] != 1:
        raise InvalidManifest('invalid metadata structure')
    if not isinstance(metadata['snapshot_id'], str) or not metadata['snapshot_id'] or len(metadata['snapshot_id']) > 200:
        raise InvalidManifest('invalid snapshot identifier')
    if not isinstance(metadata['records'], list) or not metadata['records']:
        raise InvalidManifest('empty or invalid records')
    if not hasattr(os, 'O_NOFOLLOW') or not hasattr(os, 'O_DIRECTORY') or os.open not in os.supports_dir_fd:
        raise InvalidManifest('Linux descriptor-relative no-follow open required')
    root_fd = os.open(root_path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        result = []
        ids = set()
        for row in metadata['records']:
            parts = valid_metadata(row)
            if row['source_id'] in ids:
                raise InvalidManifest('duplicate source UUID')
            ids.add(row['source_id'])
            digest, size = hash_file(root_fd, parts)
            if 'expected_size' in row and row['expected_size'] != size:
                raise InvalidManifest('file size differs from metadata')
            result.append({k: row[k] for k in ('source_id', 'mailbox', 'folder', 'flags')} | {'sha256': digest, 'size': size})
        return {'version': 1, 'kind': 'source', 'snapshot_id': metadata['snapshot_id'], 'records': result}
    finally:
        os.close(root_fd)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('metadata', type=Path)
    parser.add_argument('snapshot_root', type=Path)
    args = parser.parse_args(argv)
    try:
        result = build(args.metadata, args.snapshot_root)
    except (OSError, InvalidManifest) as e:
        # Do not reveal paths or message data in errors.
        print('ERROR: failed to build manifest; check snapshot and input privately (' + type(e).__name__ + ')', file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, separators=(',', ':')))
    print('WARNING: source completeness, consistent snapshot and correct mailbox mapping are not verified.', file=sys.stderr)
    return 0

if __name__ == '__main__':
    sys.exit(main())
