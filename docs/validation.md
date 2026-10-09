# Version 1.0.0 validation

The user accepted the 0.4.0 pilot on 2026-10-09 and reports all pilot-validation checks passed. Linux systemd and Windows WinSW operation, GUID resolution after user/group moves and IG fulfillment verification after publication are confirmed by the user. No independent real AD validation was run in this environment. The user confirmed the fresh Linux installer worked without issues on 2026-10-09.

0.5.0 retains directory operations and adds mandatory external directory settings and opt-in JSON diagnostics. 1.0.0 retains the tested 0.5.0 behavior; only version metadata and release documentation change. The local build uses Java 17 compatibility; the user tested earlier releases on Java 21.

Verification completed: 33 Maven tests passed. Packaged HTTPS checks passed with body logging disabled/enabled at TRACE; unknown fields remained visible, invalid JSON/type errors returned structured 400 responses, and password/header sentinels were absent from console and file logs. Each missing required directory setting independently prevented packaged startup. No AD writes were performed in these local diagnostic checks.

1.0.0 release verification: 33 Maven tests passed; packaged HTTPS startup/health returned version 1.0.0; payload opt-in/redaction/invalid-body checks and each missing directory setting check passed. Linux installer syntax and Windows service XML parsing passed. No new AD behavior was introduced after the accepted pilot.
