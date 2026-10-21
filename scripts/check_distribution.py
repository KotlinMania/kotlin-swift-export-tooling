#!/usr/bin/env python3
"""Validate release files, licensing, deterministic reproduction and helper closure."""
import argparse
import hashlib
import json
from pathlib import Path
import stat
import xml.etree.ElementTree as ET
import zipfile

def validate(directory, comparison=None):
    repo = Path(__file__).resolve().parents[1]
    lock = json.loads((repo / 'inputs.lock.json').read_text())
    receipt = json.loads((directory / 'distribution.json').read_text())
    frozen = Path(__file__).resolve().parents[1] / 'verification/release-artifacts.json'
    assert receipt == json.loads(frozen.read_text()), 'Distribution differs from the frozen real-consumer candidate'
    for name, expected in receipt['files'].items():
        path = directory / name
        assert hashlib.sha256(path.read_bytes()).hexdigest() == expected, name
        if comparison:
            assert path.read_bytes() == (comparison / name).read_bytes(), 'Nonreproducible: ' + name
        if path.suffix not in ('.jar', '.zip'):
            continue
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            names = [entry.filename for entry in entries]
            assert len(names) == len(set(names)), 'Duplicate entries'
            assert not any(Path(n).is_absolute() or '..' in Path(n).parts for n in names)
            assert not any(n.endswith(('.rs', '.rlib', '.dylib', '.so', '.dll', '.a')) or Path(n).name.startswith('Cargo.') for n in names), 'Rust or Native binary bundled'
            assert all(not stat.S_ISLNK(e.external_attr >> 16) for e in entries), 'Archive symlink'
            prefix = 'META-INF/' if name == lock['artifact'] + '-' + lock['version'] + '.jar' else ''
            assert prefix + 'LICENSE' in names and prefix + 'NOTICE' in names
            assert archive.read(prefix + 'LICENSE') == (Path(__file__).resolve().parents[1] / 'LICENSE').read_bytes()
            if name.endswith('-native-sdk-helper.zip'):
                expected_helper = set(lock['licenses']) | {'inputs.lock.json', 'README.md', 'scripts/prepare_native_sdk.py', 'patches/KotlinBase.h.patch'}
                if 'array_repair' in lock:
                    expected_helper.add(lock['array_repair'])
                assert set(names) == expected_helper
                assert 'scripts/prepare_native_sdk.py' in names and 'patches/KotlinBase.h.patch' in names
                assert not any(n.endswith('.class') for n in names)
    pom = next(directory.glob('*.pom'))
    root = ET.parse(pom).getroot()
    ns = {'p': 'http://maven.apache.org/POM/4.0.0'}
    assert root.find('p:groupId', ns).text == 'io.github.kotlinmania'
    assert root.find('p:version', ns).text == lock['version']
    assert root.find('p:licenses/p:license/p:name', ns).text == 'Apache License, Version 2.0'
    print('Release contents, licensing and reproduction verified')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--distribution', type=Path, required=True)
    parser.add_argument('--comparison', type=Path)
    args = parser.parse_args()
    validate(args.distribution, args.comparison)
