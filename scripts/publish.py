#!/usr/bin/env python3
"""Publish the verified immutable release using existing credentials only."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile
from check_distribution import validate
from signatures import verify as verify_signatures

REPO = Path(__file__).resolve().parents[1]
PROJECT = 'KotlinMania/kotlin-swift-export-tooling'

def github_run(identifier):
    return json.loads(subprocess.check_output(['gh', 'api', f'repos/{PROJECT}/actions/runs/{identifier}'], text=True))

def publish(args):
    assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd=REPO, text=True), 'Clean source required'
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip()
    runs = [github_run(args.pr_ci_run), github_run(args.main_ci_run)]
    for run in runs:
        assert run['conclusion'] == 'success' and run['path'] == '.github/workflows/ci.yml', 'Required CI did not pass'
        tree = subprocess.check_output(['git', 'rev-parse', run['head_sha'] + '^{tree}'], cwd=REPO, text=True).strip()
        assert tree == subprocess.check_output(['git', 'rev-parse', head + '^{tree}'], cwd=REPO, text=True).strip(), 'CI source differs'
    assert runs[1]['head_sha'] == head and runs[1]['head_branch'] == 'main', 'Exact-main CI required'
    validate(args.distribution)
    expected = json.loads((REPO / 'verification/release-artifacts.json').read_text())
    actual = json.loads((args.distribution / 'distribution.json').read_text())
    assert actual == expected, 'Release files differ from independently tested candidate'
    prefix = 'swift-export-embeddable-2.4.20-km.1'
    url = 'https://repo.maven.apache.org/maven2/io/github/kotlinmania/swift-export-embeddable/2.4.20-km.1/' + prefix + '.pom'
    try:
        urllib.request.urlopen(url, timeout=30).close()
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
    else:
        raise RuntimeError('Immutable version is already published; do not overwrite')
    env = os.environ.copy()
    if args.existing_secrets_dir:
        secrets = args.existing_secrets_dir
        credentials = ET.parse(secrets / 'creds.xml').getroot()
        env['MAVEN_CENTRAL_USERNAME'] = credentials.findtext('username', '').strip()
        env['MAVEN_CENTRAL_PASSWORD'] = credentials.findtext('password', '').strip()
        env['SIGNING_KEY'] = (secrets / 'private.maven.asc').read_text()
        env['SIGNING_KEY_PASSWORD'] = (secrets / 'passphrase.txt').read_text().rstrip('\r\n')
    for key in ['MAVEN_CENTRAL_USERNAME', 'MAVEN_CENTRAL_PASSWORD', 'SIGNING_KEY', 'SIGNING_KEY_PASSWORD']:
        assert env.get(key), 'Existing credential missing: ' + key
    assert not args.output.exists(), 'New publication output directory required'
    args.output.mkdir(parents=True)
    # The existing key is parsed in memory by Gradle; no keyring or repository secrets are changed.
    with (args.output / 'signing.log').open('w') as log:
        subprocess.run(['gradle', 'signRelease', '--no-daemon', '--max-workers=2', '--no-configuration-cache', '--console=plain', '-PdistributionDirectory=' + str(args.distribution.resolve())], cwd=REPO, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    signatures = verify_signatures(args.distribution.resolve(), actual['files'], args.output / 'signature-verification')
    layout = args.output / 'staging/io/github/kotlinmania/swift-export-embeddable/2.4.20-km.1'
    layout.mkdir(parents=True)
    for name in actual['files']:
        for source in [args.distribution / name, args.distribution / (name + '.asc')]:
            assert source.is_file() and source.stat().st_size, 'Missing artifact or signature'
            shutil.copyfile(source, layout / source.name)
    for path in list(layout.iterdir()):
        for algorithm in ['md5', 'sha1', 'sha256', 'sha512']:
            (layout / (path.name + '.' + algorithm)).write_text(hashlib.new(algorithm, path.read_bytes()).hexdigest())
    bundle = args.output / (prefix + '-bundle.zip')
    with zipfile.ZipFile(bundle, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted((args.output / 'staging').rglob('*')):
            if path.is_file():
                archive.write(path, path.relative_to(args.output / 'staging'))
    token = base64.b64encode((env['MAVEN_CENTRAL_USERNAME'] + ':' + env['MAVEN_CENTRAL_PASSWORD']).encode()).decode()
    boundary = 'KotlinMania' + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; filename="{bundle.name}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode() + bundle.read_bytes() + f'\r\n--{boundary}--\r\n'.encode())
    request = urllib.request.Request('https://central.sonatype.com/api/v1/publisher/upload?' + urllib.parse.urlencode({'name': prefix, 'publishingType': 'AUTOMATIC'}), data=body, headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'multipart/form-data; boundary=' + boundary})
    with urllib.request.urlopen(request, timeout=120) as response:
        deployment = response.read().decode().strip()
    receipt = {'deployment_id': deployment, 'source_head': head, 'required_ci': [run['html_url'] for run in runs], 'files': actual['files'], 'signatures': signatures, 'bundle_sha256': hashlib.sha256(bundle.read_bytes()).hexdigest(), 'status': 'uploaded_not_yet_independently_verified'}
    (args.output / 'upload.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print('Central upload accepted; deployment ' + deployment + '. Verify status and independently retrieve artifacts before adoption.')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--distribution', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--pr-ci-run', required=True)
    parser.add_argument('--main-ci-run', required=True)
    parser.add_argument('--existing-secrets-dir', type=Path, help='Existing local credentials; never copied or configured persistently')
    publish(parser.parse_args())
