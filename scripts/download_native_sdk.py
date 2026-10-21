#!/usr/bin/env python3
"""Retrieve the matching official SDK, rejecting unsafe archive entries."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
root = args.output.resolve()
assert not root.exists(), 'New SDK download directory required'
root.mkdir(parents=True)
url = 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-native-prebuilt/2.4.20/kotlin-native-prebuilt-2.4.20-macos-aarch64.tar.gz'
archive = root / 'official-sdk.tar.gz'
with urllib.request.urlopen(url, timeout=120) as response, archive.open('wb') as output:
    shutil.copyfileobj(response, output)
assert hashlib.sha256(archive.read_bytes()).hexdigest() == 'b70d4fd2855fdb054fc9604beaa7de83c18d786454ae948de72a226e81f07dc8', 'Official archive checksum mismatch'
with tarfile.open(archive) as source:
    entries = source.getmembers()
    for entry in entries:
        path = Path(entry.name)
        assert not path.is_absolute() and '..' not in path.parts
        assert entry.isfile() or entry.isdir(), 'SDK archive link or special entry rejected'
    source.extractall(root, members=entries)
(root / 'download.json').write_text(json.dumps({'url': url, 'archive_sha256': hashlib.sha256(archive.read_bytes()).hexdigest(), 'identity_validation': 'prepare_native_sdk.py pins compiler and original header before use'}, indent=2) + '\n')
