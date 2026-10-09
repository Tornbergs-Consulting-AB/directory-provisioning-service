# Copyright 2026 Marcus Tornberg
# SPDX-License-Identifier: Apache-2.0
"""Compile/start the DEV probe and test its observable HTTP contract."""
import base64, json, os, socket, subprocess, tempfile, time, urllib.request, urllib.error
from pathlib import Path
root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as tmp:
    classes = Path(tmp) / 'classes'; classes.mkdir()
    subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '--release', '17', '-d', str(classes), str(root/'src/main/java/consulting/tornbergs/directory/ResponseProbe.java')], check=True)
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0)); port=s.getsockname()[1]
    env=dict(os.environ, DPS_HOST='127.0.0.1', DPS_PORT=str(port), DPS_LOG_LEVEL='FINEST')
    log=Path(tmp)/'probe.log'
    with log.open('w') as stream:
        process=subprocess.Popen(['java','-cp',str(classes),'consulting.tornbergs.directory.ResponseProbe'], env=env, stdout=stream, stderr=stream)
        try:
            url=f'http://127.0.0.1:{port}'
            for _ in range(100):
                try:
                    urllib.request.urlopen(url+'/health',timeout=1).close(); break
                except (OSError, urllib.error.URLError): time.sleep(.05)
            else: raise AssertionError('Probe did not start')
            auth='Basic '+base64.b64encode(b'probe-user:super-secret-password').decode()
            def call(path, body=b'{"secret":"private-body-marker"}', credentials=auth, method='POST'):
                headers={'X-Correlation-ID':'test-83','Content-Type':'application/json'}
                if credentials: headers['Authorization']=credentials
                req=urllib.request.Request(url+path, data=body if method=='POST' else None, headers=headers,method=method)
                try: response=urllib.request.urlopen(req, timeout=3)
                except urllib.error.HTTPError as e: response=e
                with response:
                    return response.status, {k.lower():v for k,v in response.headers.items()}, json.loads(response.read())
            count=0
            for scenario,status in [('success',200),('already-member',200),('not-member',200),('bad-request',400),('unauthorized',401),('forbidden',403),('not-found',404),('unavailable',503),('internal-error',500)]:
                actual,headers,data=call('/test/responses/'+scenario)
                assert actual==status,(scenario,actual)
                assert data['requestId']=='test-83' and data['simulated'] is True
                assert headers['x-correlation-id']=='test-83'
                if status==401: assert 'www-authenticate' in headers
                count+=1
            for credential in [None,'Basic !!!','Basic '+base64.b64encode(b'u:').decode()]:
                assert call('/test/responses/success',credentials=credential)[0]==401;count+=1
            assert call('/test/responses/success',method='GET')[0]==405;count+=1
            assert call('/test/responses/unknown')[0]==404;count+=1
            assert call('/missing')[0]==404;count+=1
            assert call('/test/responses/success',body=b'x'*65537)[0]==413;count+=1
        finally:
            process.terminate();process.wait(timeout=5)
    logs=log.read_text()
    # The JDK's own FINEST logging may include protocol metadata; must not expose secrets.
    for secret in ['super-secret-password',auth,'private-body-marker']:
        assert secret not in logs,'Secret leaked into logs'
    print(f'PASS: {count} HTTP cases; credential and body redaction at FINEST')
