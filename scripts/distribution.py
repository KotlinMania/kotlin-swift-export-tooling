#!/usr/bin/env python3
"""Assemble deterministic Maven files and a source-only SDK helper classifier."""
import argparse
import hashlib
import html
import json
from pathlib import Path
import zipfile
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[1]

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def archive(path, entries):
    with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_STORED) as output:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            output.writestr(info, data)

def prepare(build, output):
    lock = json.loads((REPO / 'inputs.lock.json').read_text())
    result = json.loads((build / 'build-result.json').read_text())
    main = build / result['artifact']
    if sha(main) != result['sha256'] or output.exists():
        raise ValueError('Unverified build or existing distribution directory')
    output.mkdir(parents=True)
    prefix = f"{lock['artifact']}-{lock['version']}"
    (output / (prefix + '.jar')).write_bytes(main.read_bytes())
    licenses = {name: (REPO / name).read_bytes() for name in lock['licenses']}
    common = {**licenses, 'inputs.lock.json': (REPO / 'inputs.lock.json').read_bytes()}
    if 'array_repair' in lock:
        common[lock['array_repair']] = (REPO / lock['array_repair']).read_bytes()
    if 'bytearray_repair' in lock:
        common[lock['bytearray_repair']] = (REPO / lock['bytearray_repair']).read_bytes()
    if 'collection_repair' in lock:
        common[lock['collection_repair']] = (REPO / lock['collection_repair']).read_bytes()
    sources = {**common, **{row['path']: (build / 'candidate/sources' / row['name']).read_bytes() for row in lock['sources']}}
    sources.update({str(path.relative_to(REPO)): path.read_bytes() for path in sorted((REPO / 'patches').rglob('*.patch'))})
    if 'array_repair' in lock:
        sources.update({str(path.relative_to(REPO)): path.read_bytes() for path in sorted((REPO / 'producer/arrays').iterdir()) if path.is_file()})
    if 'bytearray_repair' in lock:
        sources.update({str(path.relative_to(REPO)): path.read_bytes() for path in sorted((REPO / 'producer/byte-arrays').iterdir()) if path.is_file()})
    if 'collection_repair' in lock:
        sources.update({str(path.relative_to(REPO)): path.read_bytes() for path in sorted((REPO / 'producer/collections').iterdir()) if path.is_file()})
    archive(output / (prefix + '-sources.jar'), sources)
    documentation = '<!doctype html><html lang="en"><meta charset="utf-8"><title>KotlinMania Swift export tooling</title><body><pre>' + html.escape((REPO / 'README.md').read_text()) + '</pre></body></html>\n'
    archive(output / (prefix + '-javadoc.jar'), {**common, 'index.html': documentation.encode()})
    helper = {**common, 'README.md': (REPO / 'README.md').read_bytes(), 'scripts/prepare_native_sdk.py': (REPO / 'scripts/prepare_native_sdk.py').read_bytes(), 'patches/KotlinBase.h.patch': (REPO / 'patches/KotlinBase.h.patch').read_bytes()}
    archive(output / (prefix + '-native-sdk-helper.zip'), helper)
    ET.register_namespace('', 'http://maven.apache.org/POM/4.0.0')
    namespace = 'http://maven.apache.org/POM/4.0.0'
    def element(parent, tag, text=None):
        node = ET.SubElement(parent, '{' + namespace + '}' + tag)
        node.text = text
        return node
    pom = ET.Element('{' + namespace + '}project')
    for name, value in [('modelVersion', '4.0.0'), ('groupId', lock['group']), ('artifactId', lock['artifact']), ('version', lock['version']), ('packaging', 'jar'), ('name', 'KotlinMania Swift Export Embeddable'), ('description', 'Version-pinned Native-to-Swift exporter repairs and coherent isolated SDK header preparation'), ('url', 'https://github.com/KotlinMania/kotlin-swift-export-tooling')]: element(pom, name, value)
    license_node = element(element(pom, 'licenses'), 'license')
    for name, value in [('name', 'Apache License, Version 2.0'), ('url', 'https://www.apache.org/licenses/LICENSE-2.0.txt'), ('distribution', 'repo')]: element(license_node, name, value)
    developer = element(element(pom, 'developers'), 'developer')
    for name, value in [('id', 'kotlinmania'), ('name', 'KotlinMania contributors'), ('organization', 'KotlinMania'), ('organizationUrl', 'https://github.com/KotlinMania')]: element(developer, name, value)
    scm = element(pom, 'scm')
    for name, value in [('url', 'https://github.com/KotlinMania/kotlin-swift-export-tooling'), ('connection', 'scm:git:https://github.com/KotlinMania/kotlin-swift-export-tooling.git'), ('tag', 'v' + lock['version'])]: element(scm, name, value)
    dependencies = element(pom, 'dependencies')
    direct = {'checker-qual', 'error_prone_annotations', 'opentelemetry-api', 'caffeine', 'kotlin-stdlib', 'kotlin-compiler-embeddable', 'kotlinx-serialization-core-jvm'}
    for row in lock['tools']:
        if row['scope'] != 'worker' or not (row['artifact'] in direct or row['version'] == '1.10.2-intellij-1'):
            continue
        dependency = element(dependencies, 'dependency')
        for name, value in [('groupId', row['group']), ('artifactId', row['artifact']), ('version', row['version']), ('scope', 'runtime')]: element(dependency, name, value)
    ET.indent(pom, space='  ')
    (output / (prefix + '.pom')).write_bytes(ET.tostring(pom, encoding='utf-8', xml_declaration=True) + b'\n')
    sbom = {'bomFormat': 'CycloneDX', 'specVersion': '1.6', 'version': 1, 'metadata': {'component': {'type': 'library', 'group': lock['group'], 'name': lock['artifact'], 'version': lock['version'], 'licenses': [{'license': {'id': 'Apache-2.0'}}]}}, 'components': [{'type': 'library', 'group': row['group'], 'name': row['artifact'], 'version': row['version'], 'hashes': [{'alg': 'SHA-256', 'content': row['sha256']}], 'properties': [{'name': 'kotlinmania:input-scope', 'value': row['scope']}]} for row in lock['tools']]}
    (output / (prefix + '-sbom.json')).write_text(json.dumps(sbom, indent=2, sort_keys=True) + '\n')
    receipt = {'files': {p.name: sha(p) for p in sorted(output.iterdir())}, 'main_component_sha256': result['sha256'], 'native_header_sha256': lock['native_header']['candidate_sha256'], 'rust_sources_included': False, 'native_sdk_binaries_included': False}
    (output / 'distribution.json').write_text(json.dumps(receipt, indent=2, sort_keys=True) + '\n')
    return receipt

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(prepare(args.build.resolve(), args.output.resolve()), indent=2))
