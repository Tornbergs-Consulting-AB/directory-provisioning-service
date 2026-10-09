# Version 0.4.0 validation

Local Maven verification covers GUID encoding/resolution, direct membership semantics, HTTP validation and startup configuration limits. The user confirmed GUID user/group moves against real AD without collection, and IG collection/publication verification. Deployment wrappers and restricted-account/failure acceptance tests remain pending; see pilot-validation.md. Builds here use Java 17 compatibility; Java 21 host validation remains a deployment check.

Verification: 26 tests passed (6 request validation, 12 directory operations/GUID, 4 configuration checks, 4 HTTP contracts). Additional packaged production-profile checks passed: valid local HTTPS startup and health, incorrect LDAP truststore password prevents startup. systemd-analyze verify passed for the provided unit. No Windows service runtime or real AD failure testing was performed here.
