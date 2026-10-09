# Upgrade to 0.5.0 — explicit directory settings and payload diagnostics

The user accepted the 0.4.0 functional pilot on 2026-10-09 after passing pilot-validation tests. 0.5.0 adds configuration and diagnostic changes; repeat startup, connection and one fulfillment after upgrading.

## Mandatory external settings

Add these to config/application.properties BEFORE replacing the distribution. Example lab values are shown only here; they are no longer bundled defaults:

```properties
dps.target-name=DEMO
dps.ldap-host=demodc01.demo.tornbergs.consulting
dps.ldap-port=636
dps.search-base=OU=Tornbergs,DC=demo,DC=tornbergs,DC=consulting
# Replace with the actual restricted service account DN, not Administrator.
dps.allowed-bind-dn=YOUR_SERVICE_ACCOUNT_DN
```

The target name must match the JSON payload's target. Keep your existing HTTPS/LDAPS certificate configuration and passwords. No directory endpoint, target, search base or allowed bind identity is assumed. Missing required settings prevent startup, even if startup-validation is disabled. Quarkus environment/system-property overrides remain supported; there is no hard-coded fallback. The supported deployment location is config/application.properties relative to the service working directory.

Stop service, back up the complete old quarkus-app directory, replace it with dist/quarkus-app from this release, restart. Endpoint and IG scripts remain unchanged. No certificates or secrets are included in the archive.

## Incoming JSON diagnostics

```properties
dps.log-request-payload=true
quarkus.log.category."consulting.tornbergs.directory".level=DEBUG
# TRACE also includes these DEBUG messages.
dps.log-request-payload-max-chars=16384
```

Restart. A POST writes `request=iga-... payload={...}` before field mapping/validation, including unexpected fields, then the usual outcome/error. This logs a compact representation of valid JSON, not original whitespace. Authentication headers are never captured. Known credential/secret fields are redacted recursively, including password, token, authorization, credentials, API/private keys, headers and allData. Malformed JSON is logged as INVALID_JSON_OMITTED plus character count; scalar/array bodies are omitted. This prevents accidentally recording credentials from content that cannot be parsed/redacted. Valid JSON with wrong fields/types remains visible, making IG script errors diagnosable. JSON escaping prevents multiline log injection. Oversized diagnostics are marked TRUNCATED; normal body-size enforcement remains 64K.

Redaction is based on field names, not a guarantee against secrets placed in arbitrary fields. Send AD credentials only in the Authorization header, not the JSON body. DN/GUID data in logs still requires restricted access. Set dps.log-request-payload=false after troubleshooting. Payload logging requires BOTH the flag and DEBUG/TRACE; merely setting TRACE does not enable body logging.

## Default review

| Setting | Bundled default / policy |
|---|---|
| dps.allowed-bind-dn | None, mandatory external value |
| dps.target-name | None, mandatory external value |
| dps.ldap-host | None, mandatory external value |
| dps.ldap-port | None, mandatory external value |
| dps.search-base | None, mandatory external value |
| HTTPS certificate and LDAP truststore paths/passwords | No bundled credentials/certificates; external values required for production startup |
| quarkus.http.host | 127.0.0.1, local-only binding; override explicitly for remote clients |
| quarkus.http.port | 8080; unused when production HTTP is disabled |
| HTTPS listener port | Set explicitly externally (your lab uses 8444); framework defaults apply if omitted |
| dps.connect-timeout-ms / operation-timeout-ms | 5000 each, review for your network and IG timeout |
| dps.max-concurrent-requests | 8 |
| quarkus.http.limits.max-body-size | 64K |
| dps.probe-enabled | false |
| dps.startup-validation | true; disabled only by test profile/local override |
| dps.log-request-payload | false |
| dps.log-request-payload-max-chars | 16384 (effective range 256–65536) |
| Application logging | INFO; DEBUG/TRACE available |
| File logging | logs/directory-provisioning-service.log, rotation 10M / five backups |

No outgoing AD DNS name, IP address or Administrator DN remains in packaged configuration. Example scripts still contain target DEMO, which must be changed to match deployment config; they are examples, not server defaults.
