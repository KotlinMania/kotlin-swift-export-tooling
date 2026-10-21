"""Rebuild checked ByteArray constructors over the immutable km.2 producer."""
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

from build import REPO, check, digest, fetch, normalize_source, run, write_json


def build_bytearrays(output, cache, seeds, lock):
    repair_bytes = (REPO / lock['bytearray_repair']).read_bytes()
    check(digest(repair_bytes) == lock['bytearray_repair_sha256'], 'Constructor lock mismatch')
    repair = json.loads(repair_bytes)
    check(not output.exists(), 'Build output must be new')
    output.mkdir(parents=True)
    cache.mkdir(parents=True, exist_ok=True)
    tmp = output / 'tmp'
    tmp.mkdir()
    env = dict(os.environ, TMPDIR=str(tmp) + '/')
    for name, expected in lock['licenses'].items():
        check(digest((REPO / name).read_bytes()) == expected, 'License checksum mismatch')
    tools = {row['name']: fetch(row, cache, seeds) for row in lock['tools']}
    parent = tools[repair['base_component']['name']]
    check(digest(parent.read_bytes()) == repair['base_component']['sha256'], 'Parent mismatch')
    worker = [parent if row['artifact'] == 'swift-export-embeddable' else tools[row['name']]
              for row in lock['tools'] if row['scope'] == 'worker']
    check(len(worker) == 16, 'Worker closure changed')
    bootstrap = [tools[row['name']] for row in lock['tools'] if row['scope'] == 'bootstrap']
    bootstrap += [tools['swift-export-embeddable-2.4.20-km.1.jar']]
    bootstrap += [p for p in worker if p.name in (
        'annotations-23.0.0.jar', 'kotlin-reflect-1.6.10.jar', 'kotlinx-coroutines-core-jvm-1.8.0.jar',
    )]
    cp = os.pathsep.join(map(str, worker))
    source_dirs = {variant: output / variant / 'sources' for variant in ('baseline', 'candidate')}
    for directory in source_dirs.values():
        directory.mkdir(parents=True)
    # Retain the complete source closure of previous repairs in the source archive.
    with zipfile.ZipFile(tools['swift-export-embeddable-2.4.20.jar']) as official:
        for row in lock['sources']:
            original = official.read(row['archive_entry']) if 'archive_entry' in row else fetch(row, cache, seeds).read_bytes()
            check(digest(original) == row['baseline_sha256'], 'Source baseline mismatch: ' + row['name'])
            for directory in source_dirs.values():
                (directory / row['name']).write_bytes(original)
            if 'patch' in row:
                patch = REPO / row['patch']
                check(digest(patch.read_bytes()) == row['patch_sha256'], 'Patch mismatch')
                run(['patch', '--batch', '--fuzz=0', str(source_dirs['candidate'] / row['name']), str(patch)],
                    output / (row['name'] + '.patch.log'), env)
            check(digest((source_dirs['candidate'] / row['name']).read_bytes()) == row['candidate_sha256'],
                  'Candidate source mismatch: ' + row['name'])
    for variant, source_dir in source_dirs.items():
        unit = output / variant / 'constructors'
        inputs = unit / 'inputs'
        inputs.mkdir(parents=True)
        files = []
        for name in repair['compile_sources']:
            p = inputs / name
            p.write_text(normalize_source((source_dir / name).read_text()))
            files.append(str(p))
        args = ['java', '--enable-native-access=ALL-UNNAMED', '-Xmx4g', '-Djava.io.tmpdir=' + str(tmp),
                '-cp', os.pathsep.join(map(str, bootstrap)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-classpath', cp, '-Xfriend-paths=' + str(parent),
                '-module-name', 'org.jetbrains.kotlin:sir-light-classes', '-jvm-target', '1.8',
                '-jvm-default=no-compatibility', '-Werror',
                '-opt-in=org.jetbrains.kotlin.analysis.api.KaContextParameterApi',
                '-opt-in=org.jetbrains.kotlin.analysis.api.KaExperimentalApi',
                *files, '-d', str(unit / 'classes')]
        write_json(unit / 'invocation.json', {'args': args})
        run(args, unit / 'compile.log', env)
    base = output / 'baseline/constructors/classes'
    candidate = output / 'candidate/constructors/classes'
    inventory = lambda directory: sorted(str(p.relative_to(directory)) for p in directory.rglob('*.class'))
    check(inventory(base) == repair['baseline_inventory'], 'Baseline inventory changed')
    check(inventory(candidate) == repair['candidate_inventory'], 'Candidate inventory changed')
    auditor = output / 'auditor'
    run(['java', '--enable-native-access=ALL-UNNAMED', '-cp', cp,
         'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', cp,
         '-jvm-target', '1.8', '-Werror', str(REPO / 'verification/ClassAudit.kt'),
         str(REPO / 'verification/ClassAuditTests.kt'), '-d', str(auditor)], output / 'auditor-compile.log', env)
    audit_cp = str(auditor) + os.pathsep + cp
    run(['java', '-cp', audit_cp, 'ClassAuditTestsKt'], output / 'auditor-tests.log', env)
    parent_classes = output / 'parent-classes'
    parent_classes.mkdir()
    with zipfile.ZipFile(parent) as archive:
        for entry in repair['baseline_selected']:
            p = parent_classes / entry
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(archive.read(entry))
    selected = repair['selected_classes']
    excluded = set(repair['baseline_inventory']) - set(repair['baseline_selected'])
    changed_excluded = sorted(entry for entry in excluded if (base / entry).read_bytes() != (candidate / entry).read_bytes())
    check(changed_excluded == repair['changed_excluded_classes'], 'Excluded compiler delta changed')
    for label, directory, entries in [('parent', parent_classes, repair['baseline_selected']),
                                      ('baseline', base, sorted(set(repair['baseline_selected']) | set(changed_excluded))),
                                      ('candidate', candidate, sorted(set(selected) | set(changed_excluded)))]:
        run(['java', '-cp', audit_cp, 'ClassAuditKt', str(directory), str(output / (label + '-canonical')),
             *entries], output / (label + '-attributes.log'), env)
    for entry in repair['baseline_selected']:
        check((output / 'parent-canonical' / entry).read_bytes() ==
              (output / 'baseline-canonical' / entry).read_bytes(), 'Baseline attributes differ: ' + entry)
    for entry in changed_excluded:
        check((output / 'baseline-canonical' / entry).read_bytes() ==
              (output / 'candidate-canonical' / entry).read_bytes(), 'Excluded source changed: ' + entry)
    replacements = {}
    for entry, expected in selected.items():
        check(digest((output / 'candidate-canonical' / entry).read_bytes()) == expected['full_non_debug_sha256'],
              'Reviewed candidate differs: ' + entry)
        replacements[entry] = (candidate / entry).read_bytes()
    replacements['META-INF/kotlinmania/inputs.lock.json'] = (REPO / 'inputs.lock.json').read_bytes()
    replacements['META-INF/kotlinmania/bytearrays.lock.json'] = repair_bytes
    result = output / f"{lock['artifact']}-{lock['version']}.jar"
    with zipfile.ZipFile(parent) as original:
        old = {name: original.read(name) for name in original.namelist() if not name.endswith('/')}
    added = set(selected) - set(old)
    check(added == set(repair['added_classes']), 'New class classification changed')
    entries = dict(old)
    entries.update(replacements)
    with zipfile.ZipFile(result, 'w', compression=zipfile.ZIP_STORED) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    with zipfile.ZipFile(result) as archive:
        check(set(old) <= set(archive.namelist()), 'Parent file removed')
        check(all(archive.read(name) == data for name, data in old.items() if name not in replacements),
              'Excluded parent entry changed')
    closure_entries = sorted(set(repair['baseline_selected']) | set(selected))
    write_json(output / 'closure-entries.json', closure_entries)
    write_json(output / 'worker.json', [{'path': str(p), 'sha256': digest(p.read_bytes())} for p in worker])
    java_home = Path(subprocess.check_output(['java', '-XshowSettings:properties', '-version'],
                    stderr=subprocess.STDOUT, text=True).split('java.home = ', 1)[1].splitlines()[0].strip())
    run([sys.executable, str(REPO / 'verification/audit-bytearray-closure.py'), '--jar', str(result),
         '--worker', str(output / 'worker.json'), '--entries', str(output / 'closure-entries.json'),
         '--jdk', str(java_home), '--output', str(output / 'reference-closure.json')],
        output / 'reference-closure.log', env)
    write_json(output / 'build-result.json', {
        'sha256': digest(result.read_bytes()), 'artifact': result.name,
        'parent_sha256': repair['base_component']['sha256'], 'changed_entries': sorted(replacements),
        'added_entries': sorted(set(entries) - set(old)), 'removed_file_entries': [],
        'baseline_classes_checked': len(repair['baseline_selected']),
        'all_non_debug_class_attributes_match_reviewed_candidate': True,
        'excluded_parent_entries_byte_identical': True,
        'reference_closure_sha256': digest((output / 'reference-closure.json').read_bytes()),
        'native_header_sha256': lock['native_header']['candidate_sha256'],
    })
    print(result, digest(result.read_bytes()), flush=True)
