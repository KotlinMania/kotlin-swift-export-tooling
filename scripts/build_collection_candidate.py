#!/usr/bin/env python3
"""Audit an isolated Foundation collection conversion candidate over published km.3."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

from build import REPO, check, digest, normalize_source, run, write_json


def build_candidate(args):
    root = args.output.resolve()
    check(not root.exists(), 'New output required')
    root.mkdir(parents=True)
    tmp = root / 'tmp'
    tmp.mkdir()
    env = dict(os.environ, TMPDIR=str(tmp) + '/')
    source_lock = REPO / 'producer/collections/source-lock.json'
    repair = json.loads(source_lock.read_text())
    lock_path = REPO / 'inputs.lock.json'
    lock = json.loads(lock_path.read_text())
    parent = args.component.resolve()
    check(digest(parent.read_bytes()) == repair['base_component_sha256'], 'Published parent mismatch')
    check(digest(args.sources.read_bytes()) == repair['published_sources_sha256'], 'Published sources mismatch')
    patch = REPO / repair['patch']
    check(digest(patch.read_bytes()) == repair['patch_sha256'], 'Patch mismatch')
    tools = {}
    for row in lock['tools']:
        path = args.cache.resolve() / row['name']
        check(path.is_file() and not path.is_symlink() and digest(path.read_bytes()) == row['sha256'],
              'Frozen compiler input mismatch: ' + row['name'])
        tools[row['name']] = path
    worker = [parent if row['artifact'] == 'swift-export-embeddable' else tools[row['name']]
              for row in lock['tools'] if row['scope'] == 'worker']
    check(len(worker) == 16, 'Worker closure changed')
    bootstrap = [tools[row['name']] for row in lock['tools'] if row['scope'] == 'bootstrap']
    bootstrap += [tools['swift-export-embeddable-2.4.20-km.1.jar']]
    bootstrap += [path for path in worker if path.name in (
        'annotations-23.0.0.jar', 'kotlin-reflect-1.6.10.jar', 'kotlinx-coroutines-core-jvm-1.8.0.jar',
    )]
    cp = os.pathsep.join(map(str, worker))
    fixed = [Path(__file__), source_lock, patch, lock_path, parent, args.sources.resolve(),
             REPO / 'scripts/build.py', REPO / 'verification/ClassAudit.kt',
             REPO / 'verification/ClassAuditTests.kt', REPO / 'verification/audit-bytearray-closure.py',
             *tools.values()]
    with zipfile.ZipFile(args.sources) as archive:
        for variant in ['baseline', 'candidate']:
            directory = root / variant / 'sources'
            directory.mkdir(parents=True)
            for name, row in repair['compile_sources'].items():
                data = archive.read(row['archive_entry'])
                check(digest(data) == row['sha256'], 'Published source differs: ' + name)
                (directory / name).write_bytes(data)
    run(['patch', '--batch', '--fuzz=0', str(root / 'candidate/sources/TypeBridging.kt'), str(patch)],
        root / 'patch.log', env)
    check(digest((root / 'candidate/sources/TypeBridging.kt').read_bytes()) == repair['candidate_source_sha256'],
          'Candidate source differs')
    files_by_variant = {}
    for variant in ['baseline', 'candidate']:
        inputs = root / variant / 'inputs'
        inputs.mkdir()
        files = []
        for name in repair['compile_sources']:
            path = inputs / name
            source = root / variant / 'sources' / name
            path.write_text(normalize_source(source.read_text()))
            files.append(str(path))
            fixed += [source, path]
        files_by_variant[variant] = files
    pins = {str(path): digest(path.read_bytes()) for path in fixed}
    write_json(root / 'input-pins.json', pins)
    class_pins = {}
    class_inventories = {}
    inventory = lambda path: sorted(str(p.relative_to(path)) for p in path.rglob('*.class'))

    def verify():
        check(all(digest(Path(path).read_bytes()) == expected for path, expected in pins.items()),
              'Frozen source/tool input changed')
        check(all(digest(Path(path).read_bytes()) == expected for path, expected in class_pins.items()),
              'Compiled class changed after its phase')
        check(all(inventory(Path(path)) == expected for path, expected in class_inventories.items()),
              'Compiled class inventory changed after its phase')

    modules = {'published': 'org.jetbrains.kotlin:sir-providers', 'rebuilt': 'org.jetbrains.kotlin_sir-providers'}
    for variant, module in [(variant, module) for variant in ['baseline', 'candidate'] for module in modules]:
        identity = modules[module]
        unit = root / variant / module
        unit.mkdir()
        command = ['java', '--enable-native-access=ALL-UNNAMED', '-Xmx4g', '-Djava.io.tmpdir=' + str(tmp),
                   '-cp', os.pathsep.join(map(str, bootstrap)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                   '-no-stdlib', '-no-reflect', '-classpath', cp, '-Xfriend-paths=' + str(parent),
                   '-module-name', identity, '-jvm-target', '1.8',
                   '-jvm-default=no-compatibility', '-Werror',
                   '-opt-in=org.jetbrains.kotlin.analysis.api.KaContextParameterApi',
                   *files_by_variant[variant], '-d', str(unit / 'classes')]
        verify()
        write_json(unit / 'invocation.json', {'args': command})
        run(command, unit / 'compile.log', env)
        directory = unit / 'classes'
        class_inventories[str(directory)] = inventory(directory)
        class_pins.update({str(path): digest(path.read_bytes()) for path in directory.rglob('*.class')})
        verify()
    entries = inventory(root / 'baseline/published/classes')
    check(all(value == entries for value in class_inventories.values()), 'Class inventory changed')
    write_json(root / 'class-inventory.json', entries)
    auditor = root / 'auditor'
    run(['java', '--enable-native-access=ALL-UNNAMED', '-cp', cp,
         'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', cp,
         '-jvm-target', '1.8', '-Werror', str(REPO / 'verification/ClassAudit.kt'),
         str(REPO / 'verification/ClassAuditTests.kt'), '-d', str(auditor)], root / 'auditor-compile.log', env)
    audit_cp = str(auditor) + os.pathsep + cp
    run(['java', '-cp', audit_cp, 'ClassAuditTestsKt'], root / 'auditor-tests.log', env)
    parent_classes = root / 'parent-classes'
    parent_classes.mkdir()
    with zipfile.ZipFile(parent) as archive:
        for entry in entries:
            path = parent_classes / entry
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(archive.read(entry))
    directories = {'parent': parent_classes}
    directories.update({variant + '-' + module: root / variant / module / 'classes'
                        for variant in ['baseline', 'candidate'] for module in modules})
    for label, directory in directories.items():
        run(['java', '-cp', audit_cp, 'ClassAuditKt', str(directory), str(root / (label + '-canonical')),
             *entries], root / (label + '-attributes.log'), env)
    expected = [
        'org/jetbrains/kotlin/sir/providers/impl/BridgeProvider/Bridge$AsContravariantBlock$inSwiftSources$1.class',
        'org/jetbrains/kotlin/sir/providers/impl/BridgeProvider/Bridge$AsNSArray.class',
        'org/jetbrains/kotlin/sir/providers/impl/BridgeProvider/Bridge$AsNSCollection$InSwiftSources.class',
        'org/jetbrains/kotlin/sir/providers/impl/BridgeProvider/Bridge$AsNSCollection.class',
        'org/jetbrains/kotlin/sir/providers/impl/BridgeProvider/Bridge$AsNSDictionary.class',
    ]
    check(sorted(repair['class_modules']) == expected, 'Reviewed class selection changed')
    changed = expected
    for module in modules:
        actual = [entry for entry in entries if (root / ('baseline-' + module + '-canonical') / entry).read_bytes() !=
                  (root / ('candidate-' + module + '-canonical') / entry).read_bytes()]
        check(actual == expected, 'Unexpected non-debug class delta: ' + repr(actual))
    module_by_class = {entry: next(key for key, value in modules.items() if value == identity)
                       for entry, identity in repair['class_modules'].items()}
    for entry, module in module_by_class.items():
        check((root / 'parent-canonical' / entry).read_bytes() ==
              (root / ('baseline-' + module + '-canonical') / entry).read_bytes(),
              'Published baseline differs with exact module identity: ' + entry)
    parent_baseline_differences = {module: [entry for entry in entries if (root / 'parent-canonical' / entry).read_bytes() !=
                                   (root / ('baseline-' + module + '-canonical') / entry).read_bytes()]
                                   for module in modules}
    write_json(root / 'parent-baseline-differences.json', parent_baseline_differences)
    result = root / 'swift-export-foundation-collection-diagnostic.jar'
    with zipfile.ZipFile(parent) as archive:
        original = {name: archive.read(name) for name in archive.namelist() if not name.endswith('/')}
    replacements = {entry: (root / 'candidate' / module_by_class[entry] / 'classes' / entry).read_bytes()
                    for entry in changed}
    contents = dict(original)
    contents.update(replacements)
    with zipfile.ZipFile(result, 'w', compression=zipfile.ZIP_STORED) as archive:
        for name, data in sorted(contents.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    with zipfile.ZipFile(result) as archive:
        check(set(archive.namelist()) == set(original), 'Archive inventory changed')
        check(all(archive.read(name) == data for name, data in original.items() if name not in replacements),
              'Unselected parent bytes changed')
    write_json(root / 'worker.json', [{'path': str(path), 'sha256': digest(path.read_bytes())} for path in worker])
    write_json(root / 'closure-entries.json', changed)
    java_home = subprocess.check_output(['java', '-XshowSettings:properties', '-version'],
                  stderr=subprocess.STDOUT, text=True).split('java.home = ', 1)[1].splitlines()[0].strip()
    run([sys.executable, str(REPO / 'verification/audit-bytearray-closure.py'), '--jar', str(result),
         '--worker', str(root / 'worker.json'), '--entries', str(root / 'closure-entries.json'),
         '--jdk', java_home, '--output', str(root / 'reference-closure.json')], root / 'reference-closure.log', env)
    verify()
    candidate_attributes = {entry: digest((root / ('candidate-' + module_by_class[entry] + '-canonical') / entry).read_bytes()) for entry in changed}
    check(candidate_attributes == repair['selected_non_debug_sha256'], 'Reviewed collection attributes changed')
    write_json(root / 'verified-host-result.json', {
        'host_source_class_audit_passed': True, 'native_or_swift_behavior_executed': False,
        'component': str(result), 'sha256': digest(result.read_bytes()), 'changed_classes': changed,
        'class_inventory_count': len(entries), 'parent_baseline_differences': parent_baseline_differences,
        'unselected_parent_bytes_identical': True, 'added_or_removed_entries': [],
        'reference_closure_sha256': digest((root / 'reference-closure.json').read_bytes()),
        'class_modules': repair['class_modules'],
        'candidate_attributes': {entry: digest((root / ('candidate-' + module_by_class[entry] + '-canonical') / entry).read_bytes())
                                 for entry in changed},
    })
    print('PASS isolated host source/class audit; Native and Swift behavior remain unexecuted', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['output', 'cache', 'component', 'sources']:
        parser.add_argument('--' + name, type=Path, required=True)
    build_candidate(parser.parse_args())
