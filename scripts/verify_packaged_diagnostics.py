# Copyright 2026 Tornbergs Consulting AB
# SPDX-License-Identifier: Apache-2.0
"""Local packaged HTTPS checks; temporary certificates, no AD writes or real credentials.
Run after Maven verify: python3 scripts/verify_packaged_diagnostics.py
Requires Java, keytool. Uses localhost:18444; verifies TLS with generated test CA.
"""
import base64
import json
import pathlib
import ssl
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAR = ROOT / 'target/quarkus-app/quarkus-run.jar'


def keytool(*args):
    subprocess.run(['keytool', *args], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


with tempfile.TemporaryDirectory(prefix='dps-diagnostics-') as temporary:
    directory = pathlib.Path(temporary)
    (directory / 'config').mkdir()
    keytool('-genkeypair', '-alias', 'test', '-keyalg', 'RSA', '-keystore', str(directory / 'https.p12'),
            '-storetype', 'PKCS12', '-storepass', 'localtest123', '-dname', 'CN=localhost',
            '-ext', 'SAN=dns:localhost', '-validity', '1')
    keytool('-exportcert', '-rfc', '-alias', 'test', '-keystore', str(directory / 'https.p12'),
            '-storepass', 'localtest123', '-file', str(directory / 'ca.pem'))
    keytool('-importcert', '-noprompt', '-alias', 'test', '-keystore', str(directory / 'trust.p12'),
            '-storetype', 'PKCS12', '-storepass', 'localtest123', '-file', str(directory / 'ca.pem'))
    config = f'''quarkus.http.host=127.0.0.1
quarkus.http.ssl-port=18444
quarkus.http.insecure-requests=disabled
quarkus.http.ssl.certificate.key-store-file={directory}/https.p12
quarkus.http.ssl.certificate.key-store-password=localtest123
dps.truststore={directory}/trust.p12
dps.truststore-password=localtest123
dps.target-name=TEST
dps.ldap-host=localhost
dps.ldap-port=636
dps.search-base=dc=example,dc=com
dps.allowed-bind-dn=cn=svc,dc=example,dc=com
quarkus.log.category."consulting.tornbergs.directory".level=TRACE
'''
    context = ssl.create_default_context(cafile=str(directory / 'ca.pem'))

    def run_requests(enabled):
        (directory / 'config/application.properties').write_text(
            config + f'dps.log-request-payload={str(enabled).lower()}\n')
        with open(directory / 'console.log', 'w') as output:
            process = subprocess.Popen(['java', '-jar', str(JAR)], cwd=directory,
                                       stdout=output, stderr=subprocess.STDOUT)
            try:
                for _ in range(100):
                    if process.poll() is not None:
                        raise RuntimeError('Packaged startup failed')
                    try:
                        with urllib.request.urlopen('https://localhost:18444/health', context=context, timeout=.5) as reply:
                            assert reply.status == 200
                            assert json.loads(reply.read())["version"] == "1.0.0"
                        break
                    except (OSError, urllib.error.URLError):
                        time.sleep(.2)
                else:
                    raise RuntimeError('Packaged startup timed out')
                # Unsupported operation fails before authentication or any directory operation.
                bodies = [json.dumps({'changeItemId': '96', 'target': 'TEST', 'requestType': 'INVALID',
                                      'unexpected': 'VISIBLE_EXTRA_FIELD', 'password': 'BODY_SECRET_SENTINEL',
                                      'nested': [{'token': 'BODY_SECRET_SENTINEL'}]}),
                          '{"password":"MALFORMED_SECRET_SENTINEL',
                          '{"changeItemId":{},"unexpected":"VISIBLE_TYPE_ERROR"}']
                for count, body in enumerate(bodies):
                    request = urllib.request.Request('https://localhost:18444/api/v1/provisioning',
                        data=body.encode(), headers={'Content-Type': 'application/json',
                        'X-Correlation-ID': f'test-{count}',
                        'Authorization': 'Basic ' + base64.b64encode(b'cn=svc,dc=example:HEADER_SECRET_SENTINEL').decode()})
                    try:
                        urllib.request.urlopen(request, context=context, timeout=5)
                        raise AssertionError('Invalid request unexpectedly accepted')
                    except urllib.error.HTTPError as error:
                        assert error.code == 400
                        reply = json.loads(error.read())
                        assert reply['requestId'] == f'test-{count}'
                        assert reply['code'] == ('UNSUPPORTED_OPERATION' if count == 0 else 'INVALID_JSON')
            finally:
                process.terminate()
                process.wait(timeout=10)
        text = (directory / 'console.log').read_text()
        text += (directory / 'logs/directory-provisioning-service.log').read_text()
        for secret in ['BODY_SECRET_SENTINEL', 'MALFORMED_SECRET_SENTINEL', 'HEADER_SECRET_SENTINEL']:
            assert secret not in text
        if enabled:
            assert 'VISIBLE_EXTRA_FIELD' in text and 'VISIBLE_TYPE_ERROR' in text
            assert 'INVALID_JSON_OMITTED' in text and '[REDACTED]' in text
        else:
            assert ' payload=' not in text and 'VISIBLE_EXTRA_FIELD' not in text

    run_requests(False)
    run_requests(True)
    print('PASS: HTTPS payload logging opt-in, unexpected fields/type errors, malformed input and secret redaction')
    for required in ['dps.allowed-bind-dn', 'dps.ldap-host', 'dps.target-name', 'dps.ldap-port', 'dps.search-base']:
        missing = '\n'.join(line for line in config.splitlines() if not line.startswith(required + '='))
        (directory / 'config/application.properties').write_text(missing)
        result = subprocess.run(['java', '-jar', str(JAR)], cwd=directory, capture_output=True, text=True, timeout=20)
        assert result.returncode != 0, required + ' unexpectedly defaulted'
        assert required in result.stdout + result.stderr
    print('PASS: each of five missing external directory settings prevents packaged startup')
