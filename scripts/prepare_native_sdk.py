#!/usr/bin/env python3
"""Verify and prepare a separate Kotlin/Native 2.4.20 SDK; never edit the source."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

REPO = Path(__file__).resolve().parents[1]

def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()

def inventory(root):
    result = {}
    for directory, directories, files in os.walk(root, followlinks=False):
        for name in directories + files:
            path = Path(directory) / name
            if path.is_symlink():
                raise ValueError('SDK symlink is unsupported: ' + str(path))
        for name in sorted(files):
            path = Path(directory) / name
            if not path.is_file():
                raise ValueError('SDK special file is unsupported: ' + str(path))
            result[path.relative_to(root).as_posix()] = sha(path)
    return dict(sorted(result.items()))

def prepare(source, destination, lock, patch):
    if source.is_symlink():
        raise ValueError("SDK root must not be a symlink")
    source = source.resolve(strict=True)
    destination = destination.absolute()
    if destination.exists() or destination.is_symlink():
        raise ValueError('Destination must not exist')
    parent = destination.parent.resolve(strict=True)
    destination = parent / destination.name
    if source == destination or source in destination.parents or destination in source.parents:
        raise ValueError('Source and destination SDK trees must be separate')
    header = lock['native_header']['relative_path']
    before = inventory(source)
    if before.get(header) != lock['native_header']['baseline_sha256']:
        raise ValueError('Unexpected Native header baseline')
    for relative, expected in lock['native_identity'].items():
        if before.get(relative) != expected:
            raise ValueError('Wrong Kotlin/Native compiler input: ' + relative)
    if sha(patch) != next(r['patch_sha256'] for r in lock['sources'] if r['kind'] == 'header'):
        raise ValueError('Header patch checksum mismatch')
    shutil.copytree(source, destination, copy_function=shutil.copy2, symlinks=False)
    if inventory(destination) != before:
        raise ValueError('SDK copy differs before patching')
    process = subprocess.run(['patch', '--batch', '--fuzz=0', str(destination / header), str(patch)], capture_output=True, text=True, env={**os.environ, 'TMPDIR': str(destination.parent)})
    if process.returncode:
        raise ValueError('Header patch failed: ' + process.stderr)
    after = inventory(destination)
    if set(before) != set(after):
        raise ValueError('SDK patch added or removed files')
    changed = [name for name in before if before[name] != after[name]]
    if changed != [header] or after[header] != lock['native_header']['candidate_sha256']:
        raise ValueError('SDK changes exceed the exact header repair')
    if inventory(source) != before:
        raise ValueError('Source SDK changed during preparation')
    receipt = {'kotlin_version': '2.4.20', 'source_sdk': str(source), 'prepared_sdk': str(destination), 'source_files': before, 'changed_files': changed, 'candidate_header_sha256': after[header], 'unchanged_file_count': len(before) - 1, 'unchanged_native_binaries': True, 'installed_source_modified': False}
    return receipt

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path, help='Trusted official matching SDK, read-only input')
    parser.add_argument('--destination', required=True, type=Path, help='New project-local SDK directory')
    parser.add_argument('--receipt', required=True, type=Path, help='Owned path for the complete input audit')
    args = parser.parse_args()
    lock = json.loads((REPO / 'inputs.lock.json').read_text())
    patch = REPO / next(r['patch'] for r in lock['sources'] if r['kind'] == 'header')
    result = prepare(args.source, args.destination, lock, patch)
    args.receipt.write_text(json.dumps(result, indent=2, sort_keys=True) + '\n')
    print(args.destination)
