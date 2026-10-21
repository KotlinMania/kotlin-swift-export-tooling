#!/usr/bin/env python3
"""Stage the verified POM and main component for real Maven dependency resolution."""
import argparse
import json
from pathlib import Path
import shutil
from check_distribution import validate

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--distribution', type=Path, required=True)
parser.add_argument('--repository', type=Path, required=True)
args = parser.parse_args()
validate(args.distribution)
version = json.loads((Path(__file__).resolve().parents[1] / 'inputs.lock.json').read_text())['version']
target = args.repository / 'io/github/kotlinmania/swift-export-embeddable' / version
assert not target.exists()
target.mkdir(parents=True)
for suffix in ['.pom', '.jar']:
    name = 'swift-export-embeddable-' + version + suffix
    shutil.copyfile(args.distribution / name, target / name)
