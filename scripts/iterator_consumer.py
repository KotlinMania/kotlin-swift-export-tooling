#!/usr/bin/env python3
"""Check iterator ARC/GC ownership against an already-built Native fixture."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

REPO = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['package', 'archive', 'output', 'sdk', 'sdk-audit']:
        parser.add_argument('--' + name, type=Path, required=True)
    options = parser.parse_args()
    root = options.output.resolve()
    assert not root.exists(), 'New output required'
    root.mkdir(parents=True)
    digest = lambda path: hashlib.sha256(Path(path).read_bytes()).hexdigest()
    save = lambda name, value: (root / name).write_text(json.dumps(value, indent=2) + '\n')
    package = options.package.resolve()
    archive = options.archive.resolve()
    assert archive.is_file()
    source = REPO / 'verification/iterator-fixture/IteratorLifetimeTests.swift'
    tests = root / 'harness/Tests/IteratorLifetimeTests'
    tests.mkdir(parents=True)
    shutil.copyfile(source, tests / source.name)
    manifest = '// swift-tools-version: 6.0\nimport PackageDescription\nlet package = Package(name: "IteratorLifetime", platforms: [.macOS(.v15)], dependencies: [.package(name: "ArrayIntegration", path: @PACKAGE@)], targets: [.testTarget(name: "IteratorLifetimeTests", dependencies: [.product(name: "ArrayIntegrationLibrary", package: "ArrayIntegration")], swiftSettings: [.swiftLanguageMode(.v6)], linkerSettings: [.unsafeFlags(["-L", @PRODUCTS@, "-lArrayIntegration"])])])\n'
    (root / 'harness/Package.swift').write_text(manifest.replace('@PACKAGE@', json.dumps(str(package))).replace('@PRODUCTS@', json.dumps(str(archive.parent))))
    spec = importlib.util.spec_from_file_location('sdk_helper', REPO / 'scripts/prepare_native_sdk.py')
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    lock = json.loads((REPO / 'inputs.lock.json').read_text())
    audit = json.loads(options.sdk_audit.read_text())
    expected = dict(audit['source_files'])
    expected[lock['native_header']['relative_path']] = lock['native_header']['candidate_sha256']
    pins = {str(path): digest(path) for path in package.rglob('*') if path.is_file()}
    pins.update({str(path): digest(path) for path in [archive, source, Path(__file__), REPO / 'inputs.lock.json', options.sdk_audit.resolve(), REPO / 'scripts/prepare_native_sdk.py']})
    pins.update({str(path): digest(path) for path in (root / 'harness').rglob('*') if path.is_file()})
    save('input-pins.json', pins)
    def verify():
        assert all(Path(path).is_file() and digest(path) == value for path, value in pins.items()), 'Input changed'
        actual = helper.inventory(options.sdk.resolve())
        if actual != expected:
            save('sdk-observed.json', actual)
        assert actual == expected, 'SDK inventory changed'
    verify()
    args = ['/usr/bin/xcrun', 'swift', 'test', '-v', '--package-path', str(root / 'harness'), '--scratch-path', str(root / 'swift-build'), '--cache-path', str(root / 'swiftpm/cache'), '--config-path', str(root / 'swiftpm/config'), '--security-path', str(root / 'swiftpm/security'), '--jobs', '2', '-Xswiftc', '-warnings-as-errors', '-Xswiftc', '-target', '-Xswiftc', 'arm64-apple-macosx15.0']
    (root / 'tmp').mkdir()
    env = dict(os.environ, TMPDIR=str(root / 'tmp') + '/', CLANG_MODULE_CACHE_PATH=str(root / 'clang-cache'), SWIFT_MODULECACHE_PATH=str(root / 'module-cache'), SWIFTPM_MODULECACHE_OVERRIDE=str(root / 'module-cache'))
    save('invocation.json', {'args': args})
    with (root / 'swift.log').open('w') as output:
        process = subprocess.Popen(args, env=env, stdout=output, stderr=subprocess.STDOUT)
        save('active-process.json', {'pid': process.pid, 'active': True})
        code = process.wait()
    save('active-process.json', {'pid': process.pid, 'active': False, 'exit': code})
    verify()
    assert code == 0, 'Actual iterator test failed'
    text = (root / 'swift.log').read_text()
    name = 'iteratorRetainsArrayAndReleasesItsNativeAndObjCRoots'
    assert len(re.findall(r'Test ' + name + r'\(\)(?: \(aka [^\n]*?\))? passed', text)) == 1
    assert re.search(r'Test run with 1 test(?: in \d+ suites?)? passed', text)
    assert not re.search(r'Test .* (?:failed|skipped)', text)
    frontends = []
    for line in text.splitlines():
        if 'swift-frontend' not in line or ' -module-name ' not in line or ' -package-description-version ' in line:
            continue
        module = re.search(r' -module-name (\S+)', line).group(1)
        mode = re.search(r' -swift-version (\d+)', line)
        mode = mode.group(1) if mode else None
        assert '-warnings-as-errors' in line and '-target arm64-apple-macosx15.0' in line
        if not module.endswith('PackageTests'):
            assert mode == ('6' if module == 'IteratorLifetimeTests' else '5')
        else:
            assert mode in [None, '6']
        frontends.append({'module': module, 'mode': mode, 'werror': True, 'mac15': True})
    assert {'IteratorLifetimeTests', 'ArrayIntegration', 'KotlinRuntimeSupport'} <= {row['module'] for row in frontends}
    save('verified-result.json', {'accepted': True, 'test': name, 'count': 1, 'frontends': frontends, 'sdk_files_unchanged': len(expected), 'native_archive_sha256': digest(archive)})
    print('PASS iterator Native/ObjC lifetime', flush=True)


if __name__ == '__main__':
    main()
