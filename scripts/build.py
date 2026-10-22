#!/usr/bin/env python3
"""Rebuild the reviewed exporter changes over an immutable official binary base."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.request
import zipfile

REPO = Path(__file__).resolve().parents[1]

def digest(data):
    return hashlib.sha256(data).hexdigest()

def check(condition, message):
    if not condition:
        raise RuntimeError(message)

def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')

def fetch(row, cache, seeds):
    target = cache / row.get('name', Path(row['url']).name)
    expected = row.get('sha256', row.get('baseline_sha256'))
    if not target.exists():
        if expected in seeds:
            shutil.copyfile(seeds[expected], target)
        else:
            with urllib.request.urlopen(row['url'], timeout=120) as response:
                target.write_bytes(response.read())
    check(not target.is_symlink() and digest(target.read_bytes()) == expected, f'Input checksum mismatch: {target.name}')
    return target

def normalize_source(text):
    # The official source uses revised destructuring syntax. These three exact
    # syntax-only translations are the same ones used in the retained audits.
    text = re.sub(r'\{ \[([a-zA-Z_, ]+)\] ->', r'{ (\1) ->', text)
    text = re.sub(r'val \[([a-zA-Z_, ]+)\] =', r'val (\1) =', text)
    return re.sub(r'for \(\[([a-zA-Z_, ]+)\] in', r'for ((\1) in', text)

def run(arguments, log, env=None):
    with log.open('w') as output:
        status = subprocess.run(arguments, stdout=output, stderr=subprocess.STDOUT, env=env)
    check(status.returncode == 0, f'Command failed ({status.returncode}); see {log}')

def build(output, cache, seeds):
    lock = json.loads((REPO / 'inputs.lock.json').read_text())
    if 'collection_repair' in lock:
        from build_collections import build_collections
        return build_collections(output, cache, seeds, lock)
    if 'bytearray_repair' in lock:
        from build_bytearrays import build_bytearrays
        return build_bytearrays(output, cache, seeds, lock)
    if 'array_repair' in lock:
        from build_arrays import build_arrays
        return build_arrays(output, cache, seeds, lock)
    for relative, expected in lock['licenses'].items():
        check(digest((REPO / relative).read_bytes()) == expected, 'License/notice checksum mismatch: ' + relative)
    check(not output.exists(), 'Build output must be a new directory')
    output.mkdir(parents=True)
    cache.mkdir(parents=True, exist_ok=True)
    tools = {r['name']: fetch(r, cache, seeds) for r in lock['tools']}
    worker = [tools[r['name']] for r in lock['tools'] if r['scope'] == 'worker']
    bootstrap = [tools[r['name']] for r in lock['tools'] if r['scope'] == 'bootstrap']
    bootstrap += [p for p in worker if p.name in ('annotations-23.0.0.jar', 'kotlin-reflect-1.6.10.jar', 'kotlinx-coroutines-core-jvm-1.8.0.jar')]
    official = tools['swift-export-embeddable-2.4.20.jar']
    source_dirs = {variant: output / variant / 'sources' for variant in ('baseline', 'candidate')}
    for directory in source_dirs.values():
        directory.mkdir(parents=True)
    with zipfile.ZipFile(official) as archive:
        for row in lock['sources']:
            if 'archive_entry' in row:
                original = archive.read(row['archive_entry'])
            else:
                original = fetch(row, cache, seeds).read_bytes()
            check(digest(original) == row['baseline_sha256'], 'Source baseline checksum mismatch: ' + row['name'])
            for directory in source_dirs.values():
                (directory / row['name']).write_bytes(original)
            if 'patch' in row:
                patch = REPO / row['patch']
                check(digest(patch.read_bytes()) == row['patch_sha256'], 'Patch checksum mismatch')
                # Apply to the exact one file; no fuzzy context acceptance.
                target = source_dirs['candidate'] / row['name']
                run(['patch', '--batch', '--fuzz=0', str(target), str(patch)], output / (row['name'] + '.patch.log'))
            check(digest((source_dirs['candidate'] / row['name']).read_bytes()) == row['candidate_sha256'], 'Patched source checksum mismatch: ' + row['name'])
    auditor = output / 'auditor'
    auditor.mkdir()
    auditor_cp = os.pathsep.join(map(str, worker))
    run(['java', '--enable-native-access=ALL-UNNAMED', '-cp', auditor_cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', auditor_cp, '-jvm-target', '1.8', '-Werror', str(REPO / 'verification/ClassAudit.kt'), str(REPO / 'verification/ClassAuditTests.kt'), '-d', str(auditor)], output / 'auditor-compile.log')
    run(['java', '-cp', str(auditor) + os.pathsep + auditor_cp, 'ClassAuditTestsKt'], output / 'auditor-tests.log')
    class_deltas = {}
    for kind in ('providers', 'printer', 'enum'):
        rows = [r for r in lock['sources'] if r['kind'] == kind or kind == 'enum' and r['kind'] == 'enum-inline']
        for variant, source_dir in source_dirs.items():
            unit = output / variant / kind
            unit.mkdir(parents=True)
            normalized = unit / 'inputs'
            normalized.mkdir()
            files = []
            selected_sources = rows + ([r for r in lock['sources'] if r['kind'] == 'printer-baseline-inline'] if kind == 'printer' and variant == 'baseline' else [])
            for row in selected_sources:
                p = normalized / row['name']
                p.write_text(normalize_source((source_dir / row['name']).read_text()))
                files.append(str(p))
            classes = unit / 'classes'
            compiler = bootstrap if kind == 'enum' else worker
            module = {'providers': 'org.jetbrains.kotlin_sir-providers', 'printer': 'org.jetbrains.kotlin_sir-printer', 'enum': 'org.jetbrains.kotlin_sir-light-classes'}[kind]
            tmp = unit / 'tmp'
            tmp.mkdir()
            args = ['java', '--enable-native-access=ALL-UNNAMED', '-Xmx4g', '-Djava.io.tmpdir=' + str(tmp), '-cp', os.pathsep.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', os.pathsep.join(map(str, worker)), '-Xfriend-paths=' + str(official), '-module-name', module, '-jvm-target', '1.8', '-jvm-default=no-compatibility', '-Werror']
            if kind != 'printer':
                args += ['-opt-in=org.jetbrains.kotlin.analysis.api.KaContextParameterApi']
            if kind == 'enum':
                args += ['-opt-in=org.jetbrains.kotlin.analysis.api.KaExperimentalApi']
            args += files + ['-d', str(classes)]
            write_json(unit / 'invocation.json', {'args': args})
            run(args, unit / 'compile.log')
        selected = {name: row for name, row in lock['selected_classes'].items() if row['kind'] == kind}
        run(['ruby', str(REPO / 'verification/audit-baseline.rb'), str(official), str(output / 'baseline' / kind / 'classes'), json.dumps(list(selected))], output / (kind + '-baseline-audit.json'))
        normalized = output / 'candidate' / kind / 'canonical'
        run(['java', '-cp', str(auditor) + os.pathsep + auditor_cp, 'ClassAuditKt', str(output / 'candidate' / kind / 'classes'), str(normalized), *selected], output / (kind + '-runtime-attributes.log'))
        for entry, row in selected.items():
            rebuilt = output / 'candidate' / kind / 'classes' / entry
            check(rebuilt.is_file(), 'Candidate class is missing: ' + entry)
            check(digest((normalized / entry).read_bytes()) == row['runtime_class_sha256'], 'Candidate non-debug attributes differ from reviewed class: ' + entry)
            class_deltas[entry] = {'reviewed_raw_sha256': row['candidate_sha256'], 'rebuilt_raw_sha256': digest(rebuilt.read_bytes()), 'runtime_class_sha256': row['runtime_class_sha256'], 'runtime_attributes_equal': True}
    replacements = {}
    for entry, row in lock['selected_classes'].items():
        replacements[entry] = (output / 'candidate' / row['kind'] / 'classes' / entry).read_bytes()
    for entry in lock['resources']:
        row = next(r for r in lock['sources'] if r.get('archive_entry') == entry)
        replacements[entry] = (source_dirs['candidate'] / row['name']).read_bytes()
    # Licensing/provenance additions are explicit, deterministic release contents.
    additions = {'META-INF/LICENSE': (REPO / 'LICENSE').read_bytes(), 'META-INF/NOTICE': (REPO / 'NOTICE').read_bytes(), 'META-INF/kotlinmania/inputs.lock.json': (REPO / 'inputs.lock.json').read_bytes()}
    additions.update({'META-INF/upstream/' + p.name: p.read_bytes() for p in sorted((REPO / 'licenses/upstream').iterdir()) if p.is_file()})
    result = output / f"{lock['artifact']}-{lock['version']}.jar"
    with zipfile.ZipFile(official) as original, zipfile.ZipFile(result, 'w', compression=zipfile.ZIP_STORED) as target:
        entries = {i.filename: original.read(i.filename) for i in original.infolist() if not i.is_dir()}
        check(not (set(additions) & set(entries)), 'Release notice collides with existing entry')
        check(set(replacements) <= set(entries), 'Unexpected added producer entry')
        entries.update(replacements)
        entries.update(additions)
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            target.writestr(info, entries[name])
    with zipfile.ZipFile(official) as a, zipfile.ZipFile(result) as b:
        changed = [n for n in a.namelist() if not n.endswith('/') and a.read(n) != b.read(n)]
        check(set(changed) == set(replacements), 'Unexpected final archive delta')
        check(set(b.namelist()) - {n for n in a.namelist() if not n.endswith('/')} == set(additions), 'Wrong release additions')
    write_json(output / 'build-result.json', {'sha256': digest(result.read_bytes()), 'artifact': result.name, 'changed_entries': sorted(replacements), 'added_entries': sorted(additions), 'removed_file_entries': [], 'reviewed_diagnostic_reference': lock['diagnostic_reference_sha256'], 'class_deltas': class_deltas, 'all_non_debug_class_attributes_match_reviewed_candidate': True, 'allowed_debug_difference': 'Source/line/local-variable tables and validated invisible SourceDebugExtension SMAP; canonical constant pool, every other attribute retained', 'native_header_sha256': lock['native_header']['candidate_sha256']})
    print(result, digest(result.read_bytes()), flush=True)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--cache', required=True, type=Path)
    parser.add_argument('--seeds', type=Path, help='Optional SHA256-to-local-file map; each input is still verified')
    args = parser.parse_args()
    build(args.output.resolve(), args.cache.resolve(), json.loads(args.seeds.read_text()) if args.seeds else {})
