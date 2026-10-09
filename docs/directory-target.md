# Directory target and single-DC operation — 1.0.0

## dps.target-name

An administrator-defined logical directory label, compared exactly/case-sensitively with the POST JSON `target`. For example, `dps.target-name=DEMO` accepts `"target":"DEMO"`. A mismatch returns 400 UNKNOWN_TARGET before credentials are bound to AD. It is not a DNS domain/DC name, account, IG application name, or automatic discovery key. The same target can cover permissions from many IG applications backed by one AD directory. The label is not an authentication or authorization mechanism.

1.0.0 has one configured target and one DC; the user accepted the single-DC limitation on 2026-10-09. Its label prevents accidentally routing requests intended for another directory to this service instance. A future multi-target implementation could map the label to separate trusted endpoints, search bases and allowed bind identities. In 1.0.0, changing the label requires updating both config and IG request script; it does not itself change the LDAP host or domain.

## Single DC operation

Set `dps.ldap-host` to the FQDN of one writable domain controller and configure LDAPS trust/hostname validation for that host. Bind, object resolution and membership work stay on one connection to that DC. There is no DC discovery, host list or automatic fallback.

If it is unavailable, requests fail; restore connectivity and retry through IG. If a response was lost after a possible write, inspect/reconcile direct membership before relying on a retry result. Membership retries converge on the desired direct state, subject to concurrent operations.

For a planned DC change, stop the service, update the hostname/port and applicable trust configuration, restart, and run an authenticated connection test followed by a designated fulfillment and IG collection verification. Ensure the replacement is a writable DC for the same directory and that required data is replicated. Software configuration changes do not guarantee immediate replication consistency.

Multi-DC failover requires separate design and validation and is not a capability of 1.0.0.
