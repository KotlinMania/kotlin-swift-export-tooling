#!/usr/bin/env python3
"""Validate generated typed String arrays with real Native and Swift consumers."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import os
import re
import resource
import shutil
import subprocess

REPO = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['output', 'distribution', 'cache', 'sdk', 'sdk-audit', 'sdk-download-audit', 'helper']:
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--gradle', type=Path)
    parser.add_argument('--gradle-cache-seed', type=Path)
    parser.add_argument('--konan-cache-seed', type=Path)
    options = parser.parse_args()
    root = options.output.resolve()
    assert not root.exists(), 'New output required'
    root.mkdir(parents=True)
    for seed, name in [(options.gradle_cache_seed, 'gradle-home'), (options.konan_cache_seed, 'konan-data')]:
        if seed:
            subprocess.run(['cp', '-cRL', str(seed.resolve()), str(root / name)], check=True)
    digest = lambda path: hashlib.sha256(Path(path).read_bytes()).hexdigest()
    def save(name, value):
        (root / name).write_text(json.dumps(value, indent=2) + '\n')
    lock = json.loads((REPO / 'inputs.lock.json').read_text())
    repair = json.loads((REPO / lock['array_repair']).read_text())
    assert digest(REPO / lock['array_repair']) == lock['array_repair_sha256']
    distribution = options.distribution.resolve()
    component = distribution / (lock['artifact'] + '-' + lock['version'] + '.jar')
    assert digest(component) == json.loads((distribution / 'distribution.json').read_text())['main_component_sha256']
    tools = []
    for row in lock['tools']:
        if row['scope'] != 'worker':
            continue
        path = component if row['artifact'] == 'swift-export-embeddable' else options.cache.resolve() / row['name']
        expected = digest(component) if path == component else row['sha256']
        assert digest(path) == expected
        tools.append({'path': str(path), 'sha256': expected})
    assert len(tools) == 16 and sum(item['path'] == str(component) for item in tools) == 1
    save('compiler-classpath.json', tools)
    spec = importlib.util.spec_from_file_location('sdk_helper', REPO / 'scripts/prepare_native_sdk.py')
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    sdk = options.sdk.resolve()
    audit = json.loads(options.sdk_audit.read_text())
    expected_sdk = dict(audit['source_files'])
    expected_sdk[lock['native_header']['relative_path']] = lock['native_header']['candidate_sha256']
    assert audit['changed_files'] == [lock['native_header']['relative_path']]
    assert audit['unchanged_native_binaries'] and not audit['installed_source_modified']
    assert all(audit['source_files'][key] == value for key, value in lock['native_identity'].items())
    assert audit['source_files'][lock['native_header']['relative_path']] == lock['native_header']['baseline_sha256']
    assert helper.inventory(sdk) == expected_sdk
    fixture = REPO / 'verification/array-fixture'
    source_pins = {str(path): digest(path) for path in fixture.rglob('*') if path.is_file()}
    assert all(not path.is_symlink() for path in fixture.rglob('*'))
    assert all(digest(REPO / path) == value for path, value in repair['fixture_files'].items())
    shutil.copytree(fixture / 'src', root / 'fixture/src')
    for name in ['build.gradle', 'settings.gradle', 'gradle.properties']:
        shutil.copyfile(fixture / name, root / 'fixture' / name)
    shutil.copytree(fixture / 'Tests', root / 'harness/Tests')
    shutil.copyfile(REPO / 'verification/array-consumer.init.gradle', root / 'isolation.init.gradle')
    for directory in ['logs', 'tmp', 'process-home', 'products', 'module-cache', 'clang-cache']:
        (root / directory).mkdir()
    (root / 'strict.xcconfig').write_text('SWIFT_VERSION = 5\nSWIFT_TREAT_WARNINGS_AS_ERRORS = YES\nGCC_TREAT_WARNINGS_AS_ERRORS = YES\n')
    package = '// swift-tools-version: 6.0\nimport PackageDescription\nlet package = Package(name: "AutomaticArray", platforms: [.macOS(.v15)], dependencies: [.package(name: "ArrayIntegration", path: "../build/SPMPackage/macosArm64/Debug")], targets: [.testTarget(name: "AutomaticArrayTests", dependencies: [.product(name: "ArrayIntegrationLibrary", package: "ArrayIntegration")], swiftSettings: [.swiftLanguageMode(.v6)], linkerSettings: [.unsafeFlags(["-L", @PRODUCTS@, "-lArrayIntegration"])])])\n'
    (root / 'harness/Package.swift').write_text(package.replace('@PRODUCTS@', json.dumps(str(root / 'products'))))
    producer_paths = [component, distribution / 'distribution.json', REPO / 'inputs.lock.json', REPO / lock['array_repair'], Path(__file__), REPO / 'scripts/iterator_consumer.py', REPO / 'verification/iterator-fixture/IteratorLifetimeTests.swift', REPO / 'verification/array-consumer.init.gradle', REPO / 'verification/array-expected-tests.json', options.sdk_audit.resolve(), options.sdk_download_audit.resolve(), options.helper.resolve()]
    pins = {str(path): digest(path) for path in producer_paths}
    pins.update(source_pins)
    recipe_files = [root / 'compiler-classpath.json', root / 'isolation.init.gradle', root / 'strict.xcconfig']
    recipe_files += [path for base in [root / 'fixture', root / 'harness'] for path in base.rglob('*') if path.is_file()]
    immutable_cache = root / 'gradle-home/caches/modules-2/files-2.1'
    recipe_files += [path for path in immutable_cache.rglob('*') if path.is_file()]
    recipe_files += [path for path in (root / 'konan-data').rglob('*') if path.is_file() and not path.name.endswith('.lock')]
    pins.update({str(path): digest(path) for path in recipe_files})
    save('input-pins.json', pins)
    def verify():
        changed = [path for path, value in pins.items() if not Path(path).is_file() or digest(path) != value]
        tools_equal = all(digest(item['path']) == item['sha256'] for item in tools)
        actual = helper.inventory(sdk)
        if actual != expected_sdk:
            save('sdk-observed.json', actual)
        if changed:
            save('changed-source-inputs.json', changed)
        assert actual == expected_sdk, 'SDK inventory changed'
        assert not changed and tools_equal, 'Source/recipe changed'
    env = dict(os.environ, ARRAY_DIAGNOSTIC_ROOT=str(root), GRADLE_USER_HOME=str(root / 'gradle-home'), KONAN_DATA_DIR=str(root / 'konan-data'), TMPDIR=str(root / 'tmp') + '/', JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=' + str(root / 'tmp') + ' -Duser.home=' + str(root / 'process-home'), CLANG_MODULE_CACHE_PATH=str(root / 'clang-cache'), SWIFT_MODULECACHE_PATH=str(root / 'module-cache'), SWIFTPM_MODULECACHE_OVERRIDE=str(root / 'module-cache'), SDK_NAME='macosx', CONFIGURATION='Debug', ARCHS='arm64', MACOSX_DEPLOYMENT_TARGET='15.0', DEPLOYMENT_TARGET_SETTING_NAME='MACOSX_DEPLOYMENT_TARGET', BUILT_PRODUCTS_DIR=str(root / 'products'), TARGET_BUILD_DIR=str(root / 'products'), FRAMEWORKS_FOLDER_PATH='Frameworks', XCODE_XCCONFIG_FILE=str(root / 'strict.xcconfig'))
    def run(name, args, cwd):
        verify()
        save(name + '-invocation.json', {'args': args, 'cwd': str(cwd)})
        with (root / 'logs' / (name + '.log')).open('w') as output:
            process = subprocess.Popen(args, cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT)
            save('active-process.json', {'phase': name, 'pid': process.pid, 'active': True})
            code = process.wait()
        save('active-process.json', {'phase': name, 'pid': process.pid, 'active': False, 'exit': code})
        verify()
        assert code == 0, 'Actual failure: ' + name
        return (root / 'logs' / (name + '.log')).read_text()
    gradle = str(options.gradle.resolve()) if options.gradle else shutil.which('gradle')
    assert gradle
    version = subprocess.check_output([gradle, '--version'], env=env, text=True, stderr=subprocess.STDOUT)
    (root / 'logs/gradle-version.log').write_text(version)
    assert re.search(r'^Gradle 9\.8\.2$', version, re.M)
    run('embed', [gradle, 'embedSwiftExportForXcode', '--no-daemon', '--max-workers=2', '--no-configuration-cache', '--no-build-cache', '--console=plain', '--project-cache-dir', str(root / 'project-cache'), '-I', str(root / 'isolation.init.gradle'), '-Pkotlin.native.home=' + str(sdk), '-Pkotlin.project.persistent.dir=' + str(root / 'kotlin-project'), '-Pkotlin.compiler.execution.strategy=in-process', '-Dorg.gradle.jvmargs=-Xmx4g -Djava.io.tmpdir=' + str(root / 'tmp')], root / 'fixture')
    archive = root / 'products/libArrayIntegration.a'
    assert archive.is_file()
    package_root = root / 'build/SPMPackage/macosArm64/Debug'
    emitted = {str(path): digest(path) for path in package_root.rglob('*') if path.is_file()}
    emitted[str(archive)] = digest(archive)
    save('emitted-pins.json', emitted)
    text = run('swift', ['/usr/bin/xcrun', 'swift', 'test', '-v', '--package-path', str(root / 'harness'), '--scratch-path', str(root / 'swift-build'), '--cache-path', str(root / 'swiftpm/cache'), '--config-path', str(root / 'swiftpm/config'), '--security-path', str(root / 'swiftpm/security'), '--jobs', '2', '-Xswiftc', '-warnings-as-errors', '-Xswiftc', '-target', '-Xswiftc', 'arm64-apple-macosx15.0'], root)
    names = json.loads((REPO / 'verification/array-expected-tests.json').read_text())
    counts = {name: len(re.findall(r'Test ' + re.escape(name) + r'\(\)(?: \(aka [^\n]*?\))? passed', text)) for name in names}
    assert len(names) == 27 and all(value == 1 for value in counts.values())
    assert re.search(r'Test run with 27 tests(?: in \d+ suites?)? passed', text)
    assert not re.search(r'Test .* (?:failed|skipped)', text)
    frontends = []
    for line in text.splitlines():
        if 'swift-frontend' not in line or ' -module-name ' not in line:
            continue
        module = re.search(r' -module-name (\S+)', line).group(1)
        if ' -package-description-version ' in line:
            assert module == 'main' and '/Package.swift ' in line
            continue
        mode = re.search(r' -swift-version (\d+)', line)
        row = {'module': module, 'mode': mode.group(1) if mode else None, 'werror': '-warnings-as-errors' in line, 'target': '-target arm64-apple-macosx15.0' in line}
        assert row['werror'] and row['target']
        if not module.endswith('PackageTests'):
            assert row['mode'] == ('6' if module == 'AutomaticArrayTests' else '5')
        else:
            assert row['mode'] in [None, '6']
        frontends.append(row)
    assert {'AutomaticArrayTests', 'ArrayIntegration', 'KotlinRuntimeSupport', 'KotlinCoroutineSupport'} <= {row['module'] for row in frontends}
    assert all(digest(path) == value for path, value in emitted.items())
    boundary = root / 'native-boundary'
    boundary.mkdir()
    args = ['/usr/bin/xcrun', 'clang', '-fmodules-cache-path=' + str(boundary / 'module-cache'), '-x', 'objective-c', '-fobjc-arc', '-Wall', '-Wextra', '-Werror', '-target', 'arm64-apple-macosx15.0']
    for module in ['SharedBridge_ArrayIntegration', 'KotlinRuntimeSupportBridge']:
        args += ['-I', str(package_root / 'Sources' / module / 'include')]
    args += [str(fixture / 'NativeArrayBoundary.m'), '-L', str(root / 'products'), '-lArrayIntegration', '-framework', 'Foundation', '-lc++', '-o', str(boundary / 'probe')]
    run('boundary-compile', args, root)
    def no_core():
        resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    boundary_rows = {}
    bounds = ['getNegative', 'getUpper', 'setNegative', 'setUpper']
    cases = ['positive', *bounds, 'wrongReceiver', 'wrongValue', 'fromString', 'fromNull', 'fromObject', 'wrongHashReceiver', 'wrongEqualsReceiver', 'wrongCopyReceiver', 'wrongConsumeReceiver']
    for case in cases:
        process = subprocess.run([str(boundary / 'probe'), case], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, preexec_fn=no_core, timeout=60)
        (boundary / (case + '.log')).write_bytes(process.stdout)
        if case == 'positive':
            assert process.returncode == 0
        else:
            assert process.returncode == -6, 'Unexpected non-deliberate termination: ' + case
            family = 'IndexOutOfBoundsException|ArrayIndexOutOfBoundsException' if case in bounds else 'ClassCastException'
            assert re.search(family, process.stdout.decode(errors='replace')), case
        boundary_rows[case] = {'exit': process.returncode, 'log_sha256': digest(boundary / (case + '.log'))}
    verify()
    assert all(digest(path) == value for path, value in emitted.items())
    run('iterator', ['python3', str(REPO / 'scripts/iterator_consumer.py'), '--package', str(package_root), '--archive', str(archive), '--output', str(root / 'iterator'), '--sdk', str(sdk), '--sdk-audit', str(options.sdk_audit.resolve())], root)
    assert all(digest(path) == value for path, value in emitted.items())
    iterator = root / 'iterator/verified-result.json'
    assert json.loads(iterator.read_text())['accepted']
    save('verified-result.json', {'tests': counts, 'native_boundary': boundary_rows, 'frontends': frontends, 'iterator_result_sha256': digest(iterator), 'sdk_files_unchanged': len(expected_sdk), 'component_sha256': digest(component), 'native_archive_sha256': digest(archive), 'accepted': True})
    print('PASS 27 typed Array behaviors and one iterator lifetime test', flush=True)


if __name__ == '__main__':
    main()
