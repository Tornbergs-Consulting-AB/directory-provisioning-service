# Release 1.0.0 — 2026-10-09

First stable release of Directory Provisioning Service. Provides DN/GUID direct membership operations, authenticated connection testing, mandatory external directory settings, TLS validation, idempotent results, correlated logs, optional redacted JSON diagnostics, Linux installation and Windows service templates.

The project is licensed under Apache-2.0. Java 21 and WinSW are separately installed prerequisites. The distribution includes third-party notices, a runtime component inventory and corresponding sources for the Eclipse-licensed components. Documentation describes the current release; earlier upgrade notes and experiment instructions have been consolidated.

## Install or replace an existing distribution

For a fresh Linux installation use [the installer](../deployment/linux/README.md). Windows service setup is in [operations](operations.md). Prepare [configuration](configuration.md) and certificates before startup.

For an existing deployment:

1. Stop the OS service. Preserve `config/application.properties`, certificates, protected secret configuration and logs.
2. Back up the COMPLETE previous `quarkus-app` directory.
3. Confirm the five required external settings are present: `dps.target-name`, `dps.ldap-host`, `dps.ldap-port`, `dps.search-base` and `dps.allowed-bind-dn`. Retain the HTTPS/LDAP trust settings. Ensure IG uses `/api/v1/provisioning` and the intended DN/GUID request script.
4. Replace the complete runtime with `dist/quarkus-app` from this archive. Never merge JAR versions. Keep `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md` and `third-party/` available with any binary redistribution; the Linux installer handles these for this release layout.
5. Start the service. Check `/health` reports `1.0.0`, run the authenticated connection test, then one designated fulfillment. Check direct AD membership and subsequent IG collection/publication verification.

Rollback: stop, restore the previous complete distribution and its corresponding configuration, restart and test. Rolling back software does not undo AD modifications. Keep deployment evidence with the exact archive checksum: documentation/licensing packaging can change while the application version remains 1.0.0 during preparation for initial public publication.

## Verification and accepted limits

The user accepted the lab pilot on 2026-10-09 and confirmed Linux/Windows services, the fresh Linux installer, real AD add/remove with idempotent repeats, GUID-based object moves without recollection and IG verification after collection/publication. See [validation](validation.md) for local checks and evidence limits.

One writable DC for one logical target. No automatic failover, write replay, durable ledger, user creation or attribute updates. Sub-application account context needs separate IG integration work. No external repository/tag or public release is created by this archive.

The prebuilt runtime uses Java 17-compatible bytecode; run it on approved Java 21 hosts. Source builds target Java 21 by default. `CHECKSUMS.sha256` covers distribution files; verify with `sha256sum -c CHECKSUMS.sha256` from the project root. On Windows compare `Get-FileHash -Algorithm SHA256` with the manifest. Checksums detect byte changes and are not digital signatures.
