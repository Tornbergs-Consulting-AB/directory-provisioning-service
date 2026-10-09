# Production pilot acceptance

Status: accepted by the user on 2026-10-09 for the 1.0.0 release scope. The user reports all pilot validation tests passed; Windows service operation and Linux service operation are confirmed. The user also confirmed successful Linux installation using the script on 2026-10-09.

Confirmed by the user: real AD add/repeat/remove/repeat; IG fulfillment; GUID-based user and group moves without recollection; subsequent collection/publication changes pending verification to verified.

Record HTTP code, safe response, correlation ID, resulting direct membership and IG state for each check:

| Check | Expected |
|---|---|
| Restricted AD account, permitted group | Successful add/remove and idempotent repeats |
| Restricted account, denied group | 403 on explicit insufficient-access LDAP result; otherwise documented LDAP failure; no membership change |
| Wrong password | 401, no modification |
| Unapproved bind DN | 403, no directory bind |
| Missing or out-of-base GUID | 404, no modification |
| Wrong object class | 400, no modification |
| DC unavailable / timeout | 503, IG retry; restored connection plus retry converges |
| Untrusted or hostname-mismatched DC certificate | TLS failure, no bind/write |
| Nested-only membership | Removal returns NOT_MEMBER, nested membership retained; add creates direct membership |
| Other members | Their member values retained after every operation |
| Concurrent operations | No replacement of entire member list; inspect final state and retry convergence |
| Restart under OS service account | Configuration loaded, logs writable, TLS/bind and fulfillment work |
| Credential redaction | No Basic header/password in file, console or wrapper logs, including TRACE |
| Rollback rehearsal | Previous complete distribution starts with preserved config/certificates |

Concurrent opposing operations can legitimately finish with either state; successful responses describe the operation's observation, not a permanent guarantee. An object moving between resolution and write may require retry. Keep these tests in the lab or on designated pilot objects; restore test memberships afterward.

The acceptance above is user-reported lab validation. This environment did not independently run those AD or OS service tests. Maintain the test evidence and repeat configuration/startup and request diagnostics checks after deployment changes.
