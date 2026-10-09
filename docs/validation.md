# Validation — 1.0.0

## Local checks

`mvn clean verify` runs 33 tests covering directory semantics, GUID byte encoding, request/authentication validation, configuration checks and payload redaction. The prebuilt distribution uses Java 17-compatible bytecode; source builds default to Java 21.

`python3 scripts/verify_packaged_diagnostics.py` starts the packaged runtime with temporary localhost HTTPS certificates. It checks health/version, JSON diagnostics disabled/enabled at TRACE, visible unexpected fields/type errors, malformed-body omission, credential sentinel redaction in console/file logs, and failure to start when each required directory setting is absent. It performs no AD writes and uses no real credentials.

`python3 scripts/package_release.py` checks runtime JAR integrity, dependency inventory coverage, retained licence/source files, Markdown links, shell syntax and Windows XML, then writes the release archive and checksums. Dependency changes require regenerating/reviewing third-party data before packaging.

Local Maven and packaged HTTPS checks passed for this release. Linux installer syntax and Windows XML parsing passed. These checks cannot establish real AD or OS service behavior.

## Pilot evidence

The user accepted the pilot on 2026-10-09 and reported the [pilot validation checklist](pilot-validation.md) passed. Reported checks include Linux and Windows services, fresh Linux installation, real AD add/repeat/remove/repeat, GUID resolution after moving both objects without recollection, IG fulfillment and verification after collection/publication, and JSON diagnostics on Windows.

Those AD/OS checks were performed by the user in the lab, not independently in the build environment. Repeat designated-object checks after configuration, directory, certificate or runtime changes. Preserve safe responses, correlated logs, direct membership observations and IG verification evidence.
