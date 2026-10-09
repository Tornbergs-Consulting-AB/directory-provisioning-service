# DEMO lab setup — 1.0.0

Configured LDAPS host: demodc01.demo.tornbergs.consulting:636.
Allowed bind DN: CN=Administrator,CN=Users,DC=demo,DC=tornbergs,DC=consulting.
LDAP truststore: use the existing PKCS12 file and correct password.

## HTTPS server certificate

This is separate from the DC certificate/truststore. Choose the DNS name of the host running the REST service; IG must resolve it and trust its certificate. If using a temporary self-signed LAB certificate:

```bash
keytool -genkeypair -alias service-https -keyalg RSA -keysize 3072 -validity 365 -storetype PKCS12 -keystore config/certs/service-https.p12 -dname "CN=YOUR-SERVICE-HOST" -ext "SAN=dns:YOUR-SERVICE-HOST"
keytool -exportcert -rfc -alias service-https -keystore config/certs/service-https.p12 -file config/certs/service-https.pem
```

Replace YOUR-SERVICE-HOST with the actual hostname. Prompts avoid placing passwords on the command line. Use the same key/store password for this lab keystore. Trust the exported **public** certificate in the trust configuration used by IG/DaaS; do not disable certificate validation. On Linux, use /opt/netiq/idm/apps/jdk/bin/keytool if it is not in PATH.

Configure HTTPS as in examples/service-config.properties. Set both truststore-password and HTTPS-keystore-password environment variables without committing them or embedding them in scripts. They are independent of the AD password.

## Test using Linux / RHEL

Set values for your HTTPS service and public CA certificate. curl prompts for the AD password; the password is not placed in the command line. Use the configured restricted bind DN; Administrator is only an earlier isolated lab example.

```bash
SERVICE_URL='https://YOUR-SERVICE-HOST:8444'
SERVICE_CA='/path/to/service-ca.pem'
AD_BIND_DN='CN=YOUR_SERVICE_ACCOUNT,OU=Service Accounts,DC=demo,DC=tornbergs,DC=consulting'

# TLS and bind only.
curl --cacert "$SERVICE_CA" --user "$AD_BIND_DN" -i \
  "$SERVICE_URL/api/v1/provisioning"

# Add, then repeat: CHANGED followed by ALREADY_MEMBER.
curl --cacert "$SERVICE_CA" --user "$AD_BIND_DN" -i \
  -H 'Content-Type: application/json' -H 'X-Correlation-ID: iga-10001' \
  --data-binary @examples/demo-add.json "$SERVICE_URL/api/v1/provisioning"

# Remove, then repeat: CHANGED followed by NOT_MEMBER.
# Use a separate changeItemId for a distinct removal request.
python3 - <<'JSON'
import json
from pathlib import Path
body = json.loads(Path('examples/demo-add.json').read_text())
body['changeItemId'] = '10002'
body['requestType'] = 'REMOVE_PERMISSION_ASSIGNMENT'
Path('/tmp/dps-demo-remove.json').write_text(json.dumps(body))
JSON
curl --cacert "$SERVICE_CA" --user "$AD_BIND_DN" -i \
  -H 'Content-Type: application/json' -H 'X-Correlation-ID: iga-10002' \
  --data-binary @/tmp/dps-demo-remove.json "$SERVICE_URL/api/v1/provisioning"
```

Edit the example target and both DNs to the designated lab objects. The example does not contain AD credentials. Check direct group membership on the configured DC and restore the initial state when finished.

## Test using PowerShell 7

This prompts for AD credentials and sends them only over HTTPS. The client must trust the HTTPS server certificate. The HTTPS hostname must match the certificate SAN.

```powershell
$baseUrl = 'https://YOUR-SERVICE-HOST:8444'
$credential = Get-Credential -UserName 'CN=Administrator,CN=Users,DC=demo,DC=tornbergs,DC=consulting'
$body = Get-Content -Raw examples/demo-add.json | ConvertFrom-Json
$body.changeItemId = '10001'

# ADD. First execution changes membership; repeat should return ALREADY_MEMBER.
$r = Invoke-WebRequest -Uri "$baseUrl/api/v1/provisioning" -Method Post -Authentication Basic -Credential $credential -ContentType 'application/json' -Headers @{'X-Correlation-ID'='iga-10001'} -Body ($body | ConvertTo-Json -Depth 5) -SkipHttpErrorCheck
$r.StatusCode
$r.Content

# REMOVE. First execution removes membership; repeat should return NOT_MEMBER.
$body.changeItemId = '10002'
$body.requestType = 'REMOVE_PERMISSION_ASSIGNMENT'
$r = Invoke-WebRequest -Uri "$baseUrl/api/v1/provisioning" -Method Post -Authentication Basic -Credential $credential -ContentType 'application/json' -Headers @{'X-Correlation-ID'='iga-10002'} -Body ($body | ConvertTo-Json -Depth 5) -SkipHttpErrorCheck
$r.StatusCode
$r.Content
```

For each operation, inspect the group's **direct** members in AD on the same DC. Preserve any original test membership state when finishing. Verify another member is preserved.

Expected changed comment:

Change Item '10001' Fulfilled. Type: ADD_PERMISSION_TO_USER, Target Account: CN=Anna Johansson,OU=Users,OU=Tornbergs,DC=demo,DC=tornbergs,DC=consulting, Target Permission: CN=STORE-User,OU=Groups,OU=Tornbergs,DC=demo,DC=tornbergs,DC=consulting

AD may return different DN capitalization; comments use the DNs read from the directory.

## Additional lab checks

- Invalid AD password -> 401, no modification.
- Different, disallowed bind DN -> 403, no LDAP bind.
- Unknown target -> 400.
- Missing user/group -> 404, not an unchanged success.
- Wrong certificate trust / hostname -> failure, no modification.
- AD ACL denial -> 403. Use a permitted bind principal with restricted rights to test separately.
- Direct vs nested membership: indirect access does not count as a direct member.
- Repeat after a lost response: inspect/reconcile direct membership without replacing other values.

Source/build tests cannot certify real AD behavior. Capture service logs, status, JSON and actual membership for each check.
