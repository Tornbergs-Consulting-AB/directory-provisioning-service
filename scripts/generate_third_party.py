#!/usr/bin/env python3
# Copyright 2026 Tornbergs Consulting AB
# SPDX-License-Identifier: Apache-2.0
"""Generate a runtime inventory and retain upstream notices; review changes before release."""
import argparse
import concurrent.futures
import hashlib
import json
import pathlib
import re
import shutil
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
CENTRAL = 'https://repo.maven.apache.org/maven2/'


def coordinates(jar):
    with zipfile.ZipFile(jar) as archive:
        candidates = []
        for name in archive.namelist():
            if name.startswith('META-INF/maven/') and name.endswith('/pom.properties'):
                props = dict(line.split('=', 1) for line in archive.read(name).decode().splitlines()
                             if '=' in line and not line.startswith('#'))
                if all(key in props for key in ('groupId', 'artifactId', 'version')):
                    candidates.append(tuple(props[key] for key in ('groupId', 'artifactId', 'version')))
        matching = [c for c in candidates if jar.name == '.'.join(c[:2]) + '-' + c[2] + '.jar']
        if matching:
            return matching[0], sorted(set(candidates) - set(matching))
    # These upstream JARs intentionally have no embedded Maven properties.
    fallbacks = {'com.unboundid.unboundid-ldapsdk-': ('com.unboundid', 'unboundid-ldapsdk'),
                 'org.reactivestreams.reactive-streams-': ('org.reactivestreams', 'reactive-streams')}
    for prefix, pair in fallbacks.items():
        if jar.name.startswith(prefix):
            return (*pair, jar.name[len(prefix):-4]), []
    raise RuntimeError('Unresolved runtime coordinates: ' + jar.name)


def metadata(repo, coord, seen=None):
    seen = set() if seen is None else seen
    if coord in seen:
        raise RuntimeError('Cyclic POM inheritance: ' + ':'.join(coord))
    seen.add(coord)
    group, artifact, version = coord
    pom = repo / group.replace('.', '/') / artifact / version / (artifact + '-' + version + '.pom')
    root = ET.parse(pom).getroot()
    licences = [{'name': node.findtext('m:name', namespaces=NS), 'url': node.findtext('m:url', namespaces=NS)}
                for node in root.findall('m:licenses/m:license', NS)]
    if not licences:
        parent = root.find('m:parent', NS)
        if parent is not None:
            inherited = metadata(repo, tuple(parent.findtext('m:' + key, namespaces=NS)
                                 for key in ('groupId', 'artifactId', 'version')), seen)
            licences = inherited['declaredLicences']
    if not licences:
        raise RuntimeError('Missing declared licences: ' + ':'.join(coord))
    return {'declaredLicences': licences, 'projectUrl': root.findtext('m:url', namespaces=NS),
            'pom': str(pom)}


def selected(licences):
    names = ' '.join(item['name'] or '' for item in licences).lower()
    if 'apache' in names:
        return 'Apache-2.0'
    if 'epl' in names or 'eclipse public' in names:
        return 'EPL-2.0'
    if 'edl' in names or 'eclipse distribution' in names:
        return 'EDL-1.0'
    if 'mit-0' in names:
        return 'MIT-0'
    if 'mit' in names:
        return 'MIT'
    if 'bsd-2' in names:
        return 'BSD-2-Clause'
    raise RuntimeError('Review required for licence: ' + names)


def retain(archive_path, destination):
    files = []
    with zipfile.ZipFile(archive_path) as archive:
        for name in sorted(archive.namelist()):
            if not name.endswith('/') and re.search(r'(license|licence|notice|copyright|copying)', pathlib.PurePosixPath(name).name, re.I) and not name.endswith('.class'):
                parts = pathlib.PurePosixPath(name).parts
                if '..' in parts or name.startswith('/'):
                    raise RuntimeError('Unsafe archive entry: ' + name)
                output = destination.joinpath(*parts)
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(archive.read(name))
                files.append(str(output.relative_to(ROOT)))
    return files


def fetch(url, destination, optional=False):
    if destination.exists():
        return True
    try:
        with urllib.request.urlopen(url, timeout=60) as response:
            payload = response.read()
    except urllib.error.HTTPError as error:
        if optional and error.code == 404:
            return False
        raise
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(payload)
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download-sources', action='store_true')
    parser.add_argument('--maven-repo', type=pathlib.Path, default=pathlib.Path.home() / '.m2/repository')
    args = parser.parse_args()
    base = ROOT / 'third-party'
    jars = sorted((ROOT / 'dist/quarkus-app/lib').rglob('*.jar'))
    if not jars:
        raise RuntimeError('Build/copy the complete runtime to dist/quarkus-app first.')
    records = []
    for jar in jars:
        with zipfile.ZipFile(jar) as archive:
            bad = archive.testzip()
            if bad:
                raise RuntimeError('Corrupt runtime JAR entry: ' + str(jar) + ':' + bad)
        coord, shaded = coordinates(jar)
        info = metadata(args.maven_repo, coord)
        group, artifact, version = coord
        slug = ':'.join(coord).replace(':', '__')
        source_name = artifact + '-' + version + '-sources.jar'
        source_url = CENTRAL + group.replace('.', '/') + '/' + artifact + '/' + version + '/' + source_name
        source = ROOT / 'build/third-party-sources' / (group + '.' + source_name)
        records.append({'coordinates': ':'.join(coord), 'purl': f'pkg:maven/{group}/{artifact}@{version}',
                        'runtimeFile': str(jar.relative_to(ROOT)), 'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
                        'declaredLicences': info['declaredLicences'], 'selectedLicence': selected(info['declaredLicences']),
                        'projectUrl': info['projectUrl'], 'sourceUrl': source_url, 'embeddedComponents': [':'.join(c) for c in shaded],
                        'retainedTexts': retain(jar, base / 'retained' / slug / 'binary'),
                        '_source': source, '_slug': slug})
    if args.download_sources:
        def download(record):
            return fetch(record['sourceUrl'], record['_source'], optional=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            for record, available in zip(records, pool.map(download, records)):
                if not available:
                    print('No upstream source JAR:', record['coordinates'])
    for record in records:
        source = record.pop('_source')
        if not source.exists() and (base / 'sources' / source.name).exists():
            source = base / 'sources' / source.name
        slug = record.pop('_slug')
        if source.exists():
            record['retainedTexts'] += retain(source, base / 'retained' / slug / 'source')
        if record['selectedLicence'] == 'EPL-2.0':
            if not source.exists():
                raise RuntimeError('Missing required corresponding source: ' + record['coordinates'])
            shipped = base / 'sources' / source.name
            shipped.parent.mkdir(parents=True, exist_ok=True)
            if source != shipped:
                shutil.copyfile(source, shipped)
            record['correspondingSource'] = str(shipped.relative_to(ROOT))
            record['sourceSha256'] = hashlib.sha256(shipped.read_bytes()).hexdigest()
    supplemental = base / 'supplemental'
    upstream = {
        'Netty-NOTICE.txt': 'https://raw.githubusercontent.com/netty/netty/netty-4.1.138.Final/NOTICE.txt',
        'Netty-LICENSE.txt': 'https://raw.githubusercontent.com/netty/netty/netty-4.1.138.Final/LICENSE.txt',
        'CRaC-LICENSE.txt': 'https://raw.githubusercontent.com/CRaC/org.crac/master/LICENSE',
        'Reactive-Streams-LICENSE.txt': 'https://raw.githubusercontent.com/reactive-streams/reactive-streams-jvm/v1.0.4/LICENSE',
        'Brotli-LICENSE.txt': 'https://raw.githubusercontent.com/google/brotli/master/LICENSE',
        'Brotli4j-LICENSE.txt': 'https://raw.githubusercontent.com/hyperxpro/Brotli4j/v1.23.0/LICENSE',
    }
    for name, url in upstream.items():
        fetch(url, supplemental / name)
    # Preserve all component licences referenced by Netty's root NOTICE.
    netty_files = sorted(set(re.findall(r'license/((?:LICENSE|NOTICE)\.[A-Za-z0-9._-]+)', (supplemental / 'Netty-NOTICE.txt').read_text())))
    def download_netty(name):
        url = 'https://raw.githubusercontent.com/netty/netty/netty-4.1.138.Final/license/' + name
        fetch(url, supplemental / 'license' / name)
        return name, url
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        for name, url in pool.map(download_netty, netty_files):
            upstream['license/' + name] = url
    (supplemental / 'provenance.json').write_text(json.dumps(upstream, indent=2) + '\n')
    inventory = {'project': 'directory-provisioning-service', 'version': '1.0.0',
                 'scope': 'Exact runtime JAR inventory; embedded/native code is additionally covered by retained and supplemental notices.',
                 'components': records}
    (base / 'runtime-components.json').write_text(json.dumps(inventory, indent=2, sort_keys=True) + '\n')
    # Flat runtime CycloneDX inventory; dependency edges and shaded/native code are
    # intentionally not claimed as exhaustively modeled by this generated SBOM.
    sbom = {'bomFormat': 'CycloneDX', 'specVersion': '1.6', 'version': 1,
            'metadata': {'component': {'type': 'application', 'name': 'directory-provisioning-service',
                                      'group': 'consulting.tornbergs', 'version': '1.0.0',
                                      'licenses': [{'license': {'id': 'Apache-2.0'}}]}},
            'components': []}
    for record in records:
        group, artifact, version = record['coordinates'].split(':')
        licence = {'name': 'Eclipse Distribution License 1.0'} if record['selectedLicence'] == 'EDL-1.0' else {'id': record['selectedLicence']}
        sbom['components'].append({'type': 'library', 'bom-ref': record['purl'], 'purl': record['purl'],
                                  'group': group, 'name': artifact, 'version': version,
                                  'hashes': [{'alg': 'SHA-256', 'content': record['sha256']}],
                                  'licenses': [{'license': licence}],
                                  'properties': [{'name': 'dps:runtime-file', 'value': record['runtimeFile']}]})
    (base / 'sbom.cdx.json').write_text(json.dumps(sbom, indent=2) + '\n')
    header = '''# Third-party notices — 1.0.0

Project code is licensed under Apache-2.0. Bundled libraries retain their own licences and copyrights. The following table describes the exact runtime distribution, including inherited Maven licence declarations. Choosing our project licence does not relicense these components.

UnboundID and Vert.x are used under their available Apache-2.0 option. The Jakarta/Parsson components listed as EPL-2.0 are distributed under EPL-2.0; the GPL-with-Classpath-Exception alternatives are not the selected basis here. Corresponding source JARs for those components are included in `third-party/sources/`, under their original licences. Recipients may obtain the exact source directly from those files or the listed Maven Central URLs.

Original licence and notice texts are in `third-party/retained/`; additional upstream material is in `third-party/supplemental/`. Preserve both. Netty's complete upstream NOTICE includes descriptions of features/components that may not be present in this subset; it is retained without deleting upstream attributions. Jackson's retained notices cover embedded numeric parsing code. Netty's embedded Maven metadata records shaded components, including JCTools. Brotli4j's native library includes the MIT-licensed Brotli implementation; its licence is retained separately. CRaC uses BSD-2-Clause; Reactive Streams 1.0.4 uses MIT-0.

Java and WinSW are not bundled. Test libraries/build tools are not shipped runtime JARs. `third-party/runtime-components.json` supplies hashes, licence declarations, source URLs and embedded component metadata. This generated inventory must be reviewed together with upstream notices when dependencies change; it is not a substitute for reviewing shaded/native content.

| Runtime component | Selected licence | Original notices | Corresponding source |
|---|---|---|---|
'''
    rows = []
    for record in records:
        slug = record['coordinates'].replace(':', '__')
        if record['retainedTexts']:
            notices = f'[retained](third-party/retained/{slug})'
        elif record['coordinates'].startswith('org.crac:'):
            notices = '[upstream licence](third-party/supplemental/CRaC-LICENSE.txt)'
        elif record['coordinates'].startswith('org.reactivestreams:'):
            notices = '[upstream licence](third-party/supplemental/Reactive-Streams-LICENSE.txt)'
        elif record['coordinates'].startswith('io.netty:'):
            notices = '[upstream notices](third-party/supplemental/Netty-NOTICE.txt)'
        elif record['coordinates'].startswith('com.aayushatharva.brotli4j:'):
            notices = '[upstream licence](third-party/supplemental/Brotli4j-LICENSE.txt)'
        else:
            assert record['selectedLicence'] == 'Apache-2.0', 'Missing reviewed licence text'
            notices = '[common Apache licence](LICENSE)' 
        source = f"[included source]({record['correspondingSource']})" if 'correspondingSource' in record else f"[upstream source]({record['sourceUrl']})"
        rows.append(f"| {record['coordinates']} | {record['selectedLicence']} | {notices} | {source} |")
    (ROOT / 'THIRD_PARTY_NOTICES.md').write_text(header + '\n'.join(rows) + '\n')
    print(f'PASS: {len(records)} runtime components; {sum("correspondingSource" in r for r in records)} EPL source JARs included')


if __name__ == '__main__':
    main()
