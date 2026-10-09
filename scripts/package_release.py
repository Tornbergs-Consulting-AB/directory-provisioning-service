#!/usr/bin/env python3
# Copyright 2026 Tornbergs Consulting AB
# SPDX-License-Identifier: Apache-2.0
"""Validate and package the reviewed release without changing compiled application behavior."""
import hashlib
import json
import pathlib
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
VERSION = '1.0.0'


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def included(path):
    relative = path.relative_to(ROOT)
    return not any(part in {'.git', 'target', 'build', 'logs', '__pycache__', 'source-cache'} for part in relative.parts) and not path.name.endswith(('.log', '.p12', '.jks', '.pyc', '.env')) and str(relative) != 'config/application.properties'


def main():
    for name in ('LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md', 'SUPPORT.md', 'CONTRIBUTING.md', 'SECURITY.md'):
        assert (ROOT / name).is_file(), name + ' missing'
    inventory = json.loads((ROOT / 'third-party/runtime-components.json').read_text())
    jars = {str(p.relative_to(ROOT)): p for p in (ROOT / 'dist/quarkus-app/lib').rglob('*.jar')}
    assert set(jars) == {record['runtimeFile'] for record in inventory['components']}, 'Regenerate dependency inventory'
    for record in inventory['components']:
        assert sha256(jars[record['runtimeFile']]) == record['sha256'], 'Runtime changed: ' + record['runtimeFile']
        for notice in record['retainedTexts']:
            assert (ROOT / notice).is_file(), notice
        if record['selectedLicence'] == 'EPL-2.0':
            source = ROOT / record['correspondingSource']
            assert sha256(source) == record['sourceSha256'], 'Corresponding source changed'
    files = sorted(p for p in ROOT.rglob('*') if p.is_file() and included(p))
    for path in files:
        if path.suffix == '.jar':
            with zipfile.ZipFile(path) as archive:
                assert archive.testzip() is None, 'Corrupt JAR: ' + str(path)
        if path.suffix == '.md' and 'third-party' not in path.relative_to(ROOT).parts:
            for link in re.findall(r'(?<!!)\[[^\]]*\]\(([^)]+)\)', path.read_text()):
                target = link.split('#')[0]
                if target and not re.match(r'^[a-z][a-z0-9+.-]*:', target, re.I):
                    assert (path.parent / target).exists(), f'Broken Markdown link: {path}: {link}'
        if path.suffix == '.sh':
            subprocess.run(['bash', '-n', str(path)], check=True)
    assert not list((ROOT / 'docs').glob('upgrade-*.md')), 'Old upgrade notes remain'
    ET.parse(ROOT / 'deployment/windows/directory-provisioning-service.xml')
    for path in files:
        assert path.suffix.lower() not in {'.exe', '.dll', '.p12', '.jks'}, 'Unexpected executable/key material: ' + str(path)
    app_jar = ROOT / 'dist/quarkus-app/app/directory-provisioning-service-1.0.0.jar'
    with zipfile.ZipFile(app_jar) as archive:
        assert archive.read('META-INF/LICENSE') == (ROOT / 'LICENSE').read_bytes()
        assert archive.read('META-INF/NOTICE') == (ROOT / 'NOTICE').read_bytes()
    # Manifest covers all shipped files other than itself, not only runtime JARs.
    manifest = ROOT / 'CHECKSUMS.sha256'
    manifest.write_text(''.join(f'{sha256(p)}  {p.relative_to(ROOT).as_posix()}\n' for p in files if p != manifest))
    destination = ROOT.parent / f'directory-provisioning-service-{VERSION}.zip'
    with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files:
            archive.write(path, ROOT.name + '/' + str(path.relative_to(ROOT)))
    with zipfile.ZipFile(destination) as archive:
        assert archive.testzip() is None, 'Release archive failed CRC check'
    print('PASS: runtime/source hashes, JAR integrity, licence coverage, Markdown links, shell syntax, Windows XML')
    print(f'PACKAGED: {destination.name}; {len(files)} files; {destination.stat().st_size} bytes')
    print('SHA256: ' + sha256(destination))


if __name__ == '__main__':
    main()
