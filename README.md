# Directory Provisioning Service — 1.0.0

Java service receiving HTTPS provisioning requests and updating direct Active Directory group membership over LDAPS. Runs on Linux and Windows. Identifies users/groups by DN or canonical AD objectGUID; GUID lookup resolves their current DNs before changing one member value.

## Release scope

- POST `/api/v1/provisioning`: ADD_PERMISSION_TO_USER, REMOVE_ACCOUNT_PERMISSION and REMOVE_PERMISSION_ASSIGNMENT.
- Already present/absent direct membership returns HTTP 200 with an UNCHANGED outcome.
- GET on the same endpoint tests LDAP TLS and caller-supplied AD credentials without modifying objects.
- GET `/health` reports application version/liveness; it does not bind to AD.
- One explicitly configured logical target and one writable DC. The single-DC limitation is accepted for 1.0.0; automatic failover is not implemented.
- No AD password stored: Basic credentials are supplied by IG for each request, over HTTPS, and used for the LDAPS bind. An allowed bind DN restricts the accepted principal; AD ACLs authorize directory changes.

## Install and configure

The prebuilt distribution is `dist/quarkus-app`; copy the ENTIRE directory, including lib/app/quarkus. It was built with Java 17-compatible bytecode; run it on your approved Java 21. Source builds target Java 21 by default (`mvn clean verify`).

Copy `examples/service-config.properties` to `config/application.properties` relative to the service working directory and replace every placeholder. Target, LDAP hostname/port, search base and allowed bind DN have no bundled defaults. Configure the HTTPS server keystore separately from LDAP trust. Keep certificates/passwords outside Git. The JSON `target` must exactly match `dps.target-name`; the GUID search base must encompass the current and possible new locations of user/group objects. AD ACLs remain the authorization boundary.

- Linux installer and instructions: [deployment/linux/README.md](deployment/linux/README.md).
- Linux systemd and Windows WinSW service setup: [docs/operations.md](docs/operations.md).
- Windows password setup: [deployment/windows/password-setup.md](deployment/windows/password-setup.md).
- Both-platform uninstall: [docs/uninstall.md](docs/uninstall.md).
- Upgrade/rollback and known limits: [docs/release-1.0.0.md](docs/release-1.0.0.md).

## IG integration

Use `scripts/ig-request-guid.js` when accountProfile.accountId and permissionProfile.permissionId contain canonical AD objectGUID values. Use `scripts/ig-request.js` for DN references from accountProvId/permProvId. `scripts/ig-headers.js` adds change-item correlation. Scripts use the example target DEMO; update it to your configured label. Preserve account selection/context; do not substitute the identity identifier for an account identifier. Sub-applications that omit account context remain an integration limitation.

Configure connection-test and fulfillment paths as `/api/v1/provisioning`. Map response fulfillmentId and comment in IG. Observed IG behavior: 200 means fulfilled/pending collection verification; 400/503 means RETRY with a generic IG error. Correlated service logs provide failure detail. changeItemId is included in the response/comment; it is not a persistent deduplication key or execution ledger.

## Diagnostics and validation

INFO logs show request/change item, operation, result and timing. DEBUG shows identifier type and resolved DN. To enable redacted incoming JSON diagnostics, set `dps.log-request-payload=true` AND application category DEBUG/TRACE. Body logging is disabled by default. Known secret fields are redacted, headers are not captured, and malformed JSON is omitted with its character count. DN/GUID values and unknown fields can appear; restrict log access. See [configuration and diagnostic limits](docs/configuration.md).

The user accepted the lab pilot and validated Linux/Windows services, Linux installer, real AD GUID moves, idempotency and IG collection/publication verification. See [docs/validation.md](docs/validation.md) for local tests and evidence limits. `python3 scripts/verify_packaged_diagnostics.py` checks local packaged HTTPS diagnostics after a Maven build; no AD writes or real credentials are used.

## Operational limits

No DC failover, multi-target routing, user/account creation, durable ledger, credential caching or automatic write replay. All steps for a request use one caller-bound LDAP connection, closed afterward. LDAP referrals are not followed. Concurrent moves or unknown write outcomes may require reconciliation/retry. IG HTTP timeout must cover cumulative LDAP steps; initial recommendation is 60 seconds with default 5-second connect/operation timeouts.

Probe endpoints are disabled by default. No production secrets or certificates are included.

## Documentation and licensing

Start with the [1.0.0 documentation index](docs/README.md). Project source, scripts, deployment templates and documentation are licensed under [Apache-2.0](LICENSE), copyright 2026 Marcus Tornberg. Third-party components keep their own licences; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and `third-party/`. Java 21 and WinSW are separately installed prerequisites and are not bundled.

See [SUPPORT.md](SUPPORT.md) for best-effort maintenance and customer ownership of deployment, [CONTRIBUTING.md](CONTRIBUTING.md) for contributions, [SECURITY.md](SECURITY.md) for vulnerability reporting, and [distribution instructions](docs/distribution.md) for release preparation. This archive does not publish an external repository.
