#!/usr/bin/env python3
"""Install the verified distribution into a local Maven repository for consumer validation only."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
from check_distribution import validate

REPO = Path(__file__).resolve().parents[1]
CHECKSUMS = ['md5', 'sha1', 'sha256', 'sha512']


def install(distribution, repository, replace):
    validate(distribution)
    assert repository.is_absolute(), 'An absolute local repository path is required'
    lock = json.loads((REPO / 'inputs.lock.json').read_text())
    receipt = json.loads((distribution / 'distribution.json').read_text())
    target = repository / lock['group'].replace('.', '/') / lock['artifact'] / lock['version']
    existing = [name for name in receipt['files'] if (target / name).exists()]
    if existing and not replace:
        raise RuntimeError('Already installed: ' + ', '.join(sorted(existing)) + ' (pass --replace to overwrite a local validation install)')
    target.mkdir(parents=True, exist_ok=True)
    for name in receipt['files']:
        shutil.copyfile(distribution / name, target / name)
        for algorithm in CHECKSUMS:
            (target / (name + '.' + algorithm)).write_text(hashlib.new(algorithm, (target / name).read_bytes()).hexdigest())
    return target, len(receipt['files'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--distribution', type=Path, required=True)
    parser.add_argument('--repository', type=Path, default=Path.home() / '.m2' / 'repository', help='Local Maven repository; use an isolated absolute path to keep the default repository clean')
    parser.add_argument('--replace', action='store_true', help='Overwrite a previous local validation install')
    args = parser.parse_args()
    target, count = install(args.distribution.resolve(), args.repository, args.replace)
    print('Installed ' + str(count) + ' verified artifacts to ' + str(target))
    print('Local validation only; never substitute this install for the published immutable release.')