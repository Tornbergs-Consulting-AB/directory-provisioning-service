# Release 1.0.0 — 2026-10-09

First stable release of Directory Provisioning Service. Same membership behavior and request/response contract as 0.5.0; no migration of AD data or IG payloads is required. Single target and single DC are explicitly accepted limitations. Multi-DC failover is deferred until a suitable test environment is available.

Includes DN/GUID membership operations; authenticated connection testing; mandatory external target/LDAP/bind configuration; TLS trust/hostname validation; idempotent direct membership updates; outcome/correlation logs; optional redacted JSON diagnostics; Linux installer/systemd unit; Windows WinSW template; upgrade/rollback/uninstall and pilot acceptance documentation.

## Upgrade from 0.5.0

1. Stop the OS service. Preserve config/application.properties, certificates, secret configuration and logs.
2. Back up the COMPLETE old quarkus-app directory.
3. Replace it with dist/quarkus-app from this archive. Do not merge JAR versions.
4. Start the OS service. Check `/health` returns version `1.0.0`.
5. Run the authenticated IG connection test and one designated fulfillment; verify the intended AD state and subsequent collection/publication verification.

No changes to the endpoint, IG scripts, service registration or configuration names are required. Upgrading from older releases requires the five explicit settings in upgrade-0.5.0.md. Rollback: stop, restore the complete old distribution and any corresponding config changes, restart. Software rollback does not undo AD modifications.

## Release evidence

The user reports pilot-validation tests passed, GUID-based user/group moves without recollection, Linux and Windows service deployment, fresh Linux installer, and IG verification after collection/publication. The 0.5.0 packaged request diagnostics worked on the Windows lab host. Local checks cover directory semantics, GUID byte encoding, configuration validation, redaction and HTTP contracts. This is user-reported AD/OS evidence; no independent real AD test was performed in the build environment.

The prebuilt runtime uses Java 17-compatible bytecode, suitable for the user's Java 21 hosts; Maven source builds target Java 21 by default. CHECKSUMS.sha256 covers the prebuilt runtime files. The archive SHA-256 is provided with delivery. Checksums detect byte changes; they are not a digital signature.

## Accepted limits

One writable DC for one configured logical target; outages produce failure/retry until that DC recovers. No automatic mid-operation write replay or guarantee of immediate cross-replica consistency. Sub-application account context requires separate IG configuration work. User creation, attribute updates and other fulfillment operations are outside this release. No external repository release/tag or Windows installer executable is published by this archive.

To verify extracted runtime files on Linux, run `sha256sum -c CHECKSUMS.sha256` from the extracted project root. On Windows, Get-FileHash -Algorithm SHA256 can compare individual files with the manifest.
