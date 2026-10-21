"""Rebuild the typed Array extension over the immutable, verified km.1 base."""
from __future__ import annotations

import json
import os
from pathlib import Path
import zipfile

from build import REPO, check, digest, fetch, normalize_source, run, write_json


def build_arrays(output, cache, seeds, lock):
    repair_bytes = (REPO / lock['array_repair']).read_bytes()
    check(digest(repair_bytes) == lock['array_repair_sha256'], 'Array repair lock mismatch')
    repair = json.loads(repair_bytes)
    check(not output.exists(), 'Build output must be a new directory')
    output.mkdir(parents=True)
    cache.mkdir(parents=True, exist_ok=True)
    for relative, expected in lock['licenses'].items():
        check(digest((REPO / relative).read_bytes()) == expected, 'License checksum mismatch')
    for relative, expected in repair['owned_sources'].items():
        check(digest((REPO / relative).read_bytes()) == expected, 'Owned source checksum mismatch')
    tools = {row['name']: fetch(row, cache, seeds) for row in lock['tools']}
    parent = tools['swift-export-embeddable-2.4.20-km.1.jar']
    check(digest(parent.read_bytes()) == repair['base_component']['sha256'], 'Parent mismatch')
    official = tools['swift-export-embeddable-2.4.20.jar']
    worker = [tools[row['name']] for row in lock['tools'] if row['scope'] == 'worker']
    worker = [parent if path == official else path for path in worker]
    bootstrap = [tools[row['name']] for row in lock['tools'] if row['scope'] == 'bootstrap']
    bootstrap += [path for path in worker if path.name in (
        'annotations-23.0.0.jar', 'kotlin-reflect-1.6.10.jar', 'kotlinx-coroutines-core-jvm-1.8.0.jar',
    )]
    cp = os.pathsep.join(map(str, worker))
    source_dirs = {variant: output / variant / 'sources' for variant in ('baseline', 'candidate')}
    for directory in source_dirs.values():
        directory.mkdir(parents=True)
    with zipfile.ZipFile(official) as archive:
        for row in lock['sources']:
            original = archive.read(row['archive_entry']) if 'archive_entry' in row else fetch(row, cache, seeds).read_bytes()
            check(digest(original) == row['baseline_sha256'], 'Source baseline mismatch: ' + row['name'])
            for directory in source_dirs.values():
                (directory / row['name']).write_bytes(original)
            if 'patch' in row:
                patch = REPO / row['patch']
                check(digest(patch.read_bytes()) == row['patch_sha256'], 'Patch checksum mismatch')
                run(['patch', '--batch', '--fuzz=0', str(source_dirs['candidate'] / row['name']), str(patch)], output / (row['name'] + '.patch.log'))
            check(digest((source_dirs['candidate'] / row['name']).read_bytes()) == row['candidate_sha256'], 'Candidate source mismatch: ' + row['name'])
    # The baseline is the previous published producer source, not the rejected
    # cross-version or instruction-normalized approximation of it.
    baseline = repair['baseline_type_bridging']
    patch = REPO / baseline['patch']
    check(digest(patch.read_bytes()) == baseline['patch_sha256'], 'Baseline patch mismatch')
    run(['patch', '--batch', '--fuzz=0', str(source_dirs['baseline'] / 'TypeBridging.kt'), str(patch)], output / 'baseline-type-bridging.patch.log')
    check(digest((source_dirs['baseline'] / 'TypeBridging.kt').read_bytes()) == baseline['sha256'], 'Published source mismatch')
    for name in ('AsStringArray.kt', 'AsStringArrayObjC.kt'):
        (source_dirs['candidate'] / name).write_bytes((REPO / 'producer/arrays' / name).read_bytes())
    selected = {entry: row for entry, row in lock['selected_classes'].items() if row['kind'] == 'array'}
    for variant, source_dir in source_dirs.items():
        unit = output / variant / 'array'
        inputs = unit / 'inputs'
        inputs.mkdir(parents=True)
        names = ['TypeBridging.kt', 'SirCustomTypeTranslatorImpl.kt', 'SirSession.kt', 'Types.kt']
        if variant == 'candidate':
            names += ['AsStringArray.kt', 'AsStringArrayObjC.kt']
        files = []
        for name in names:
            target = inputs / name
            target.write_text(normalize_source((source_dir / name).read_text()))
            files.append(str(target))
        tmp = unit / 'tmp'
        tmp.mkdir()
        args = ['java', '--enable-native-access=ALL-UNNAMED', '-Xmx4g', '-Djava.io.tmpdir=' + str(tmp), '-cp', os.pathsep.join(map(str, bootstrap)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', cp, '-Xfriend-paths=' + str(parent), '-module-name', 'org.jetbrains.kotlin_sir-providers', '-jvm-target', '1.8', '-jvm-default=no-compatibility', '-Werror', '-opt-in=org.jetbrains.kotlin.analysis.api.KaContextParameterApi', *files, '-d', str(unit / 'classes')]
        write_json(unit / 'invocation.json', {'args': args})
        run(args, unit / 'compile.log')
    baseline_classes = output / 'baseline/array/classes'
    baseline_entries = sorted(str(path.relative_to(baseline_classes)) for path in baseline_classes.rglob('*.class'))
    check(len(baseline_entries) == 159, 'Unexpected complete baseline class inventory')
    run(['ruby', str(REPO / 'verification/audit-baseline.rb'), str(parent), str(baseline_classes), json.dumps(baseline_entries)], output / 'array-baseline-audit.json')
    classes = output / 'candidate/array/classes'
    actual_entries = {str(path.relative_to(classes)) for path in classes.rglob('*.class')}
    added = {entry for entry, row in selected.items() if row['added']}
    check(actual_entries == set(baseline_entries) | added and len(added) == 9, 'Candidate inventory mismatch')
    with zipfile.ZipFile(parent) as archive:
        for entry, row in selected.items():
            check((entry not in archive.namelist()) == row['added'], 'Wrong new-class classification')
    auditor = output / 'auditor'
    run(['java', '--enable-native-access=ALL-UNNAMED', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', cp, '-jvm-target', '1.8', '-Werror', str(REPO / 'verification/ClassAudit.kt'), str(REPO / 'verification/ClassAuditTests.kt'), '-d', str(auditor)], output / 'auditor-compile.log')
    audit_cp = str(auditor) + os.pathsep + cp
    run(['java', '-cp', audit_cp, 'ClassAuditTestsKt'], output / 'auditor-tests.log')
    canonical = output / 'candidate/array/canonical'
    run(['java', '-cp', audit_cp, 'ClassAuditKt', str(classes), str(canonical), *selected], output / 'array-runtime-attributes.log')
    replacements = {}
    for entry, row in selected.items():
        check(digest((canonical / entry).read_bytes()) == row['runtime_class_sha256'], 'Non-debug class attributes differ: ' + entry)
        replacements[entry] = (classes / entry).read_bytes()
    for entry, expected in lock['resources'].items():
        name = next(row['name'] for row in lock['sources'] if row.get('archive_entry') == entry)
        data = (source_dirs['candidate'] / name).read_bytes()
        check(digest(data) == expected, 'Resource mismatch: ' + entry)
        replacements[entry] = data
    replacements['META-INF/kotlinmania/inputs.lock.json'] = (REPO / 'inputs.lock.json').read_bytes()
    replacements['META-INF/kotlinmania/arrays.lock.json'] = repair_bytes
    result = output / f"{lock['artifact']}-{lock['version']}.jar"
    with zipfile.ZipFile(parent) as original, zipfile.ZipFile(result, 'w', compression=zipfile.ZIP_STORED) as target:
        entries = {name: original.read(name) for name in original.namelist() if not name.endswith('/')}
        check(set(replacements) - set(entries) == added | {'META-INF/kotlinmania/arrays.lock.json'}, 'Unexpected producer addition')
        entries.update(replacements)
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            target.writestr(info, data)
    with zipfile.ZipFile(parent) as original, zipfile.ZipFile(result) as rebuilt:
        old = {name for name in original.namelist() if not name.endswith('/')}
        check(not old - set(rebuilt.namelist()), 'Removed parent file')
        changed = {name for name in old if original.read(name) != rebuilt.read(name)}
        check(changed <= set(replacements), 'Unselected parent entry changed')
    write_json(output / 'build-result.json', {'sha256': digest(result.read_bytes()), 'artifact': result.name, 'changed_entries': sorted(changed), 'added_entries': sorted(set(replacements) - old), 'removed_file_entries': [], 'parent_sha256': repair['base_component']['sha256'], 'all_non_debug_class_attributes_match_reviewed_candidate': True, 'baseline_classes_checked': len(baseline_entries), 'native_header_sha256': lock['native_header']['candidate_sha256']})
    print(result, digest(result.read_bytes()), flush=True)
