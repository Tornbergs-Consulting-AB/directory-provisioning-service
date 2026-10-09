# DEMO lab setup — 1.0.0

Prepare the HTTPS keystore, LDAP truststore and `config/application.properties` using [Certificates and application.properties](certificates-and-configuration.md). That guide includes lab certificate commands for Linux and Windows, and production CA options. If your existing stores work, keep them.

The earlier pilot used LDAPS host `demodc01.demo.tornbergs.consulting:636`. Use your configured restricted AD service account DN, search base and target label. All names in this page and the JSON examples are lab placeholders to adapt; they are not packaged server defaults.

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
$credential = Get-Credential -UserName 'CN=YOUR_SERVICE_ACCOUNT,OU=Service Accounts,DC=demo,DC=tornbergs,DC=consulting'
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
