#!/usr/bin/env python3
"""Independently retrieve immutable Central artifacts and their detached signatures."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request
from check_distribution import validate

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
assert not args.output.exists(), 'New retrieval directory required'
args.output.mkdir(parents=True)
lock = json.loads((Path(__file__).resolve().parents[1] / 'verification/release-artifacts.json').read_text())
base = 'https://repo.maven.apache.org/maven2/io/github/kotlinmania/swift-export-embeddable/2.4.20-km.1/'
signatures = {}
for name, expected in lock['files'].items():
    for suffix in ['', '.asc', '.sha256']:
        with urllib.request.urlopen(base + name + suffix, timeout=60) as response:
            data = response.read()
        (args.output / (name + suffix)).write_bytes(data)
    assert hashlib.sha256((args.output / name).read_bytes()).hexdigest() == expected
    assert (args.output / (name + '.sha256')).read_text().strip() == expected
    signature = (args.output / (name + '.asc')).read_bytes()
    assert b'BEGIN PGP SIGNATURE' in signature
    signatures[name] = hashlib.sha256(signature).hexdigest()
(args.output / 'distribution.json').write_text(json.dumps(lock, indent=2) + '\n')
validate(args.output)
(args.output / 'retrieval.json').write_text(json.dumps({'base_url': base, 'files': lock['files'], 'signatures_sha256': signatures, 'signature_crypto_verification': 'required separately using the recorded existing public key'}, indent=2) + '\n')
print('Published files independently retrieved and byte-verified; detached signatures retained')
