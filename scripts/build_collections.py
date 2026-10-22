"""Rebuild recursive Foundation collection identity over immutable km.3."""
from argparse import Namespace
import json
import zipfile

from build import REPO, check, digest, fetch, write_json
from build_collection_candidate import build_candidate


def build_collections(output, cache, seeds, lock):
    repair_path = REPO / lock['collection_repair']
    check(digest(repair_path.read_bytes()) == lock['collection_repair_sha256'], 'Collection lock mismatch')
    repair = json.loads(repair_path.read_text())
    check(not output.exists(), 'Build output must be new')
    output.mkdir(parents=True)
    cache.mkdir(parents=True, exist_ok=True)
    for name, expected in lock['licenses'].items():
        check(digest((REPO / name).read_bytes()) == expected, 'License checksum mismatch')
    tools = {row['name']: fetch(row, cache, seeds) for row in lock['tools']}
    parent = tools['swift-export-embeddable-2.4.20-km.3.jar']
    sources = tools['swift-export-embeddable-2.4.20-km.3-sources.jar']
    audit_root = output / 'collections'
    build_candidate(Namespace(output=audit_root, cache=cache, component=parent, sources=sources))
    audit = json.loads((audit_root / 'verified-host-result.json').read_text())
    check(audit['candidate_attributes'] == repair['selected_non_debug_sha256'], 'Reviewed class attributes changed')
    diagnostic = audit_root / 'swift-export-foundation-collection-diagnostic.jar'
    check(digest(diagnostic.read_bytes()) == audit['sha256'], 'Audited archive changed')
    source_directory = output / 'candidate/sources'
    source_directory.mkdir(parents=True)
    with zipfile.ZipFile(sources) as archive:
        for row in lock['sources']:
            data = ((audit_root / 'candidate/sources/TypeBridging.kt').read_bytes()
                    if row['name'] == 'TypeBridging.kt' else archive.read(row['path']))
            check(digest(data) == row['candidate_sha256'], 'Complete release source mismatch: ' + row['name'])
            (source_directory / row['name']).write_bytes(data)
    with zipfile.ZipFile(diagnostic) as archive:
        contents = {name: archive.read(name) for name in archive.namelist()}
    metadata = {
        'META-INF/kotlinmania/inputs.lock.json': (REPO / 'inputs.lock.json').read_bytes(),
        'META-INF/kotlinmania/collection-source-lock.json': repair_path.read_bytes(),
    }
    contents.update(metadata)
    result = output / f"{lock['artifact']}-{lock['version']}.jar"
    with zipfile.ZipFile(result, 'w', compression=zipfile.ZIP_STORED) as archive:
        for name, data in sorted(contents.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    selected = set(repair['class_modules'])
    with zipfile.ZipFile(parent) as original, zipfile.ZipFile(result) as final:
        old_names = set(original.namelist())
        new_names = set(final.namelist())
        check(not old_names - new_names, 'Parent archive entry removed')
        check(new_names - old_names == {'META-INF/kotlinmania/collection-source-lock.json'}, 'Unexpected release additions')
        changed = {name for name in old_names if original.read(name) != final.read(name)}
        check(changed == selected | {'META-INF/kotlinmania/inputs.lock.json'}, 'Unexpected release delta')
    write_json(output / 'build-result.json', {
        'artifact': result.name, 'sha256': digest(result.read_bytes()),
        'changed_entries': sorted(changed), 'added_entries': sorted(new_names - old_names),
        'removed_file_entries': [], 'base_component_sha256': repair['base_component_sha256'],
        'class_deltas': audit['candidate_attributes'], 'all_non_debug_class_attributes_match_reviewed_candidate': True,
        'collection_audit_sha256': digest((audit_root / 'verified-host-result.json').read_bytes()),
        'native_header_sha256': lock['native_header']['candidate_sha256'],
    })
    print(result, digest(result.read_bytes()), flush=True)
