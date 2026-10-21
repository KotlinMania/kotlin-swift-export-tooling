#!/usr/bin/env python3
"""Measure recursive Foundation collection conversions against an audited isolated producer."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess

REPO = Path(__file__).resolve().parents[1]
TESTS = ['readsKotlinHeadersWithoutLosingOrderOrUnicode', 'roundTripsSwiftHeaderDictionary', 'distinguishesEmptyValuesFromMissingKeys', 'optionalDictionaryDistinguishesNilFromEmpty', 'recursivelyRoundTripsNestedHeaderDictionaries', 'nestedHeaderListsKeepConcreteValues', 'mutationKeepsDictionaryValueSemantics', 'temporaryOwnerValuesSurviveAutoreleasePool', 'synchronousDictionaryCallbackKeepsTypedValues', 'nullableDictionaryCallbackKeepsNilAndValuesDistinct', 'invokesKotlinReturnedDictionaryClosure']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['output', 'distribution', 'cache', 'sdk', 'sdk-audit', 'gradle-cache-seed', 'konan-cache-seed', 'gradle']:
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--configuration', choices=['Debug', 'Release'], required=True)
    parser.add_argument('--candidate-audit', type=Path)
    parser.add_argument('--sdk-download-audit', type=Path)
    parser.add_argument('--helper', type=Path)
    options = parser.parse_args()
    root = options.output.resolve()
    assert not root.exists(), 'New output required'
    root.mkdir(parents=True)
    digest = lambda path: hashlib.sha256(Path(path).read_bytes()).hexdigest()
    def save(name, value):
        (root / name).write_text(json.dumps(value, indent=2) + '\n')
    lock = json.loads((REPO / 'inputs.lock.json').read_text())
    release = json.loads((REPO / 'verification/release-artifacts.json').read_text())
    parent = options.distribution.resolve() / (lock['artifact'] + '-' + lock['version'] + '.jar')
    distribution_receipt = options.distribution.resolve() / 'distribution.json'
    assert digest(parent) == json.loads(distribution_receipt.read_text())['main_component_sha256']
    audit_path = options.candidate_audit.resolve() if options.candidate_audit else None
    if audit_path:
        assert digest(parent) == release['main_component_sha256']
        candidate_audit = json.loads(audit_path.read_text())
        source_lock = json.loads((REPO / 'producer/collections/source-lock.json').read_text())
        assert candidate_audit['host_source_class_audit_passed'] and candidate_audit['unselected_parent_bytes_identical']
        assert set(candidate_audit['changed_classes']) == set(source_lock['class_modules'])
        host_pins_path = audit_path.parent / 'input-pins.json'
        host_pins = json.loads(host_pins_path.read_text())
        assert all(digest(path) == value for path, value in host_pins.items()), 'Host audit input changed'
        assert digest(audit_path.parent / 'candidate/sources/TypeBridging.kt') == source_lock['candidate_source_sha256']
        assert digest(REPO / source_lock['patch']) == source_lock['patch_sha256']
        assert not candidate_audit['added_or_removed_entries']
        assert digest(audit_path.parent / 'reference-closure.json') == candidate_audit['reference_closure_sha256']
        assert json.loads((audit_path.parent / 'reference-closure.json').read_text())['accepted']
        component = Path(candidate_audit['component'])
        assert digest(component) == candidate_audit['sha256']
    else:
        assert options.sdk_download_audit and options.helper, 'Release validation requires packaged helper provenance'
        component = parent
    tools = []
    for row in lock['tools']:
        if row['scope'] == 'worker':
            path = component if row['artifact'] == 'swift-export-embeddable' else options.cache.resolve() / row['name']
            expected = digest(component) if path == component else row['sha256']
            assert digest(path) == expected
            tools.append({'path': str(path), 'sha256': expected})
    assert len(tools) == 16
    fixture = REPO / 'verification/collection-fixture'
    assert not any(path.is_symlink() for path in fixture.rglob('*'))
    shutil.copytree(fixture, root / 'fixture')
    for name, seed in [('gradle-home', options.gradle_cache_seed), ('konan-data', options.konan_cache_seed)]:
        subprocess.run(['cp', '-cRL', str(seed.resolve()), str(root / name)], check=True)
    for directory in ['logs', 'tmp', 'products', 'process-home', 'module-cache', 'clang-cache']:
        (root / directory).mkdir()
    save('compiler-classpath.json', tools)
    init_source = REPO / 'verification/array-consumer.init.gradle'
    init = init_source.read_text().replace("it.name == 'macosArm64DebugSwiftExport'", "it.name == 'macosArm64" + options.configuration + "SwiftExport'")
    init = init.replace("it.name == 'macosArm64DebugGenerateSPMPackage'", "it.name == 'macosArm64" + options.configuration + "GenerateSPMPackage'")
    init = init.replace('build/SPMPackage/macosArm64/Debug/Package.swift', 'build/SPMPackage/macosArm64/' + options.configuration + '/Package.swift')
    (root / 'isolation.init.gradle').write_text(init)
    (root / 'strict.xcconfig').write_text('SWIFT_VERSION = 5\nSWIFT_TREAT_WARNINGS_AS_ERRORS = YES\nGCC_TREAT_WARNINGS_AS_ERRORS = YES\n')
    harness = root / 'harness'
    shutil.copytree(fixture / 'Tests', harness / 'Tests')
    package = root / 'build/SPMPackage/macosArm64' / options.configuration
    manifest = '''// swift-tools-version: 6.0
import PackageDescription
let package = Package(name: "FoundationCollectionConstructor", platforms: [.macOS(.v15)], dependencies: [.package(name: "FoundationCollectionProof", path: @PACKAGE@)], targets: [
    .testTarget(name: "FoundationCollectionTests", dependencies: [.product(name: "FoundationCollectionProofLibrary", package: "FoundationCollectionProof")], swiftSettings: [.swiftLanguageMode(.v6)], linkerSettings: [.unsafeFlags(["-L", @PRODUCTS@, "-lFoundationCollectionProof"])])])
'''
    (harness / 'Package.swift').write_text(manifest.replace('@PACKAGE@', json.dumps(str(package))).replace('@PRODUCTS@', json.dumps(str(root / 'products'))))
    spec = importlib.util.spec_from_file_location('sdk_helper', REPO / 'scripts/prepare_native_sdk.py')
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    supplied_sdk = options.sdk.resolve()
    sdk = root / 'native-sdk'
    subprocess.run(['cp', '-cRL', str(supplied_sdk), str(sdk)], check=True)
    audit = json.loads(options.sdk_audit.read_text())
    expected_sdk = dict(audit['source_files'])
    expected_sdk[lock['native_header']['relative_path']] = lock['native_header']['candidate_sha256']
    assert audit['changed_files'] == [lock['native_header']['relative_path']]
    assert audit['unchanged_native_binaries'] and not audit['installed_source_modified']
    assert all(audit['source_files'][key] == value for key, value in lock['native_identity'].items())
    assert audit['source_files'][lock['native_header']['relative_path']] == lock['native_header']['baseline_sha256']
    fixed = [REPO / 'producer/collections/source-lock.json', REPO / 'producer/collections/TypeBridging.kt.patch', REPO / 'scripts/build_collection_candidate.py', parent, distribution_receipt, component, Path(__file__), REPO / 'inputs.lock.json', REPO / 'verification/release-artifacts.json', init_source, REPO / 'scripts/prepare_native_sdk.py', options.sdk_audit.resolve(), root / 'compiler-classpath.json', root / 'strict.xcconfig', root / 'isolation.init.gradle']
    if audit_path:
        fixed += [audit_path, audit_path.parent / 'reference-closure.json', host_pins_path]
        fixed += [Path(path) for path in host_pins]
    if options.sdk_download_audit:
        fixed.append(options.sdk_download_audit.resolve())
    if options.helper:
        fixed.append(options.helper.resolve())
    fixed += [path for base in [fixture, root / 'fixture', harness] for path in base.rglob('*') if path.is_file()]
    pins = {str(path): digest(path) for path in fixed}
    save('input-pins.json', pins)
    def verify():
        assert all(Path(path).is_file() and digest(path) == value for path, value in pins.items()), 'Source/recipe input changed'
        assert all(digest(row['path']) == row['sha256'] for row in tools), 'Worker changed'
        actual = helper.inventory(sdk)
        if actual != expected_sdk:
            save('sdk-observed.json', actual)
        assert actual == expected_sdk, 'SDK inventory changed'
    env = dict(os.environ, ARRAY_DIAGNOSTIC_ROOT=str(root), GRADLE_USER_HOME=str(root / 'gradle-home'), KONAN_DATA_DIR=str(root / 'konan-data'), TMPDIR=str(root / 'tmp') + '/', JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=' + str(root / 'tmp') + ' -Duser.home=' + str(root / 'process-home'), CLANG_MODULE_CACHE_PATH=str(root / 'clang-cache'), SWIFT_MODULECACHE_PATH=str(root / 'module-cache'), SWIFTPM_MODULECACHE_OVERRIDE=str(root / 'module-cache'), SDK_NAME='macosx', CONFIGURATION=options.configuration, ARCHS='arm64', MACOSX_DEPLOYMENT_TARGET='15.0', DEPLOYMENT_TARGET_SETTING_NAME='MACOSX_DEPLOYMENT_TARGET', BUILT_PRODUCTS_DIR=str(root / 'products'), TARGET_BUILD_DIR=str(root / 'products'), FRAMEWORKS_FOLDER_PATH='Frameworks', XCODE_XCCONFIG_FILE=str(root / 'strict.xcconfig'))
    def run(name, args, cwd):
        verify()
        save(name + '-invocation.json', {'args': args, 'cwd': str(cwd)})
        with (root / 'logs' / (name + '.log')).open('w') as log:
            process = subprocess.Popen(args, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT)
            save('active-process.json', {'phase': name, 'pid': process.pid, 'active': True})
            code = process.wait()
        save('active-process.json', {'phase': name, 'pid': process.pid, 'active': False, 'exit': code})
        verify()
        assert code == 0, 'Actual failure: ' + name
        return (root / 'logs' / (name + '.log')).read_text()
    verify()
    gradle = str(options.gradle.resolve())
    version = subprocess.check_output([gradle, '--version'], env=env, text=True, stderr=subprocess.STDOUT)
    (root / 'logs/gradle-version.log').write_text(version)
    assert re.search(r'^Gradle 9\.8\.2$', version, re.M)
    gradle_args = [gradle, '--offline', '--no-daemon', '--max-workers=2', '--no-configuration-cache', '--no-build-cache', '--console=plain', '--project-cache-dir', str(root / 'project-cache'), '-I', str(root / 'isolation.init.gradle'), '-Pkotlin.native.home=' + str(sdk), '-Pkotlin.project.persistent.dir=' + str(root / 'kotlin-project'), '-Pkotlin.compiler.execution.strategy=in-process', '-Dorg.gradle.jvmargs=-Xmx4g -Djava.io.tmpdir=' + str(root / 'tmp')]
    run('embed', gradle_args + ['embedSwiftExportForXcode'], root / 'fixture')
    archive = root / 'products/libFoundationCollectionProof.a'
    assert archive.is_file()
    def emitted_inventory():
        result = {str(path): digest(path) for path in package.rglob('*') if path.is_file()}
        result[str(archive)] = digest(archive)
        return result
    emitted = emitted_inventory()
    save('emitted-pins.json', emitted)
    text = run('swift', ['/usr/bin/xcrun', 'swift', 'test', '-v', '-c', options.configuration.lower(), '--package-path', str(harness), '--scratch-path', str(root / 'swift-build'), '--cache-path', str(root / 'swiftpm/cache'), '--config-path', str(root / 'swiftpm/config'), '--security-path', str(root / 'swiftpm/security'), '--jobs', '2', '-Xswiftc', '-warnings-as-errors', '-Xswiftc', '-target', '-Xswiftc', 'arm64-apple-macosx15.0'], root)
    counts = {name: len(re.findall(r'Test ' + re.escape(name) + r'\(\)(?: \(aka [^\n]*?\))? passed', text)) for name in TESTS}
    assert all(value == 1 for value in counts.values()), counts
    assert re.search(r'Test run with 11 tests(?: in \d+ suites?)? passed', text)
    assert not re.search(r'Test .* (?:failed|skipped)', text)
    frontends = []
    manifest_frontends = []
    for line in text.splitlines():
        if 'swift-frontend' not in line or ' -module-name ' not in line:
            continue
        module = re.search(r' -module-name (\S+)', line).group(1)
        arguments = shlex.split(line)
        if module == 'main' and any(Path(argument).name == 'Package.swift' for argument in arguments):
            manifest_frontends.append(line)
            continue
        match = re.search(r' -swift-version (\d+)', line)
        mode = match.group(1) if match else None
        assert '-warnings-as-errors' in line and '-target arm64-apple-macosx15.0' in line
        if not module.endswith('PackageTests'):
            assert mode == ('6' if module == 'FoundationCollectionTests' else '5'), (module, mode)
        frontends.append({'module': module, 'mode': mode, 'werror': True, 'mac15': True})
    assert {'FoundationCollectionTests', 'FoundationCollectionProof', 'KotlinRuntimeSupport', 'KotlinStdlib'} <= {row['module'] for row in frontends}
    verify()
    assert emitted_inventory() == emitted, 'Generated inventory changed'
    generated_swift = package / 'Sources/FoundationCollectionProof/FoundationCollectionProof.swift'
    generated_header = next((package / 'Sources').glob('*/include/FoundationCollectionProof.h'))
    swift_text = generated_swift.read_text()
    header_text = generated_header.read_text()
    assert 'Dictionary<Swift.String, Swift.Array<Swift.String>>' in swift_text
    assert 'NSDictionary<NSString *, NSArray<NSString *> *> *' in header_text
    assert not re.search(r'FoundationCollectionProof_[^\n]*\([^\n]*\) as! Swift.Dictionary<Swift.String, Swift.Array<Swift.String>>', swift_text), 'Redundant direct conversion retained'
    save('verified-result.json', {'accepted': True, 'configuration': options.configuration, 'tests': counts, 'frontends': frontends, 'manifest_frontends': manifest_frontends, 'native_archive_sha256': digest(archive), 'component_sha256': digest(component), 'sdk_files_unchanged': len(expected_sdk), 'candidate_audit_sha256': digest(audit_path) if audit_path else None, 'generated_swift_sha256': digest(generated_swift), 'generated_header_sha256': digest(generated_header), 'scope': 'String/List and String-keyed Dictionary Foundation identity; no general generic or concurrent-access proof'})
    print('PASS eleven real Foundation collection behaviors in ' + options.configuration, flush=True)


if __name__ == '__main__':
    main()
