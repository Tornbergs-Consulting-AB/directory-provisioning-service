# Version 0.5.0 validation

The user accepted the 0.4.0 pilot on 2026-10-09 and reports all pilot-validation checks passed. Linux systemd and Windows WinSW operation, GUID resolution after user/group moves and IG fulfillment verification after publication are confirmed by the user. No independent real AD validation was run in this environment. The fresh Linux installer test remains unconfirmed.

0.5.0 retains directory operations and adds mandatory external directory settings and opt-in JSON diagnostics. Build/check results are added below after verification. The local build uses Java 17 compatibility; the user tested earlier releases on Java 21.

Verification completed: 33 Maven tests passed. Packaged HTTPS checks passed with body logging disabled/enabled at TRACE; unknown fields remained visible, invalid JSON/type errors returned structured 400 responses, and password/header sentinels were absent from console and file logs. Each missing required directory setting independently prevented packaged startup. No AD writes were performed in these local diagnostic checks.
