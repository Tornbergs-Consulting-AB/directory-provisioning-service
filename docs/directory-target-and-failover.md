# Logical target and proposed DC failover

## dps.target-name (implemented)

An administrator-defined logical directory label, compared exactly/case-sensitively with the POST JSON `target`. For example, `dps.target-name=DEMO` accepts `"target":"DEMO"`. A mismatch returns 400 UNKNOWN_TARGET before credentials are bound to AD. It is not a DNS domain/DC name, account, IG application name, or automatic discovery key. The same target can cover permissions from many IG applications backed by one AD directory. The label is not an authentication or authorization mechanism.

1.0.0 has one configured target and one DC; the user accepted the single-DC limitation on 2026-10-09. Its label prevents accidentally routing requests intended for another directory to this service instance. A future multi-target implementation could map the label to separate trusted endpoints, search bases and allowed bind identities. In 1.0.0, changing the label requires updating both config and IG request script; it does not itself change the LDAP host or domain.

## Future multi-DC design (outside the accepted 1.0.0 scope)

Use an explicit ordered list of writable DC FQDNs for the SAME directory/domain, retaining one logical target and one IG configuration. Prefer the first/last healthy controller according to a documented stickiness policy; do not round-robin each request. TLS hostname verification applies independently to each FQDN. Trust the issuing CA(s), or all required individual DC certificates.

Use the SDK FailoverServerSet or a small connection-selection wrapper. Select another DC only on transient connection/availability errors before directory operations; do not treat invalid credentials, ACL denial, not-found objects or invalid certificates as availability errors to work around. Bind, GUID lookup, compare and modify stay on ONE selected connection/DC for that request. Log selected DC and connection/failover outcome without credential data.

Once a write may have been sent, do not blindly replay on another DC. Return an explicit failure/unknown-outcome result and preserve diagnostics for reconciliation/retry. Timeouts must cover the combined attempts and remain below IG's request timeout. Cap attempts and avoid per-request credential caching.

AD replication is asynchronous: a backup can have an old DN/membership or lack a newly created object. A retry against a stale DC may report no change required incorrectly, so final-state checks only describe the chosen replica's observation. Keep connections sticky and establish a reconciliation/verification policy for unknown outcomes before claiming transparent write recovery. Existing IG collection verification helps but is not an immediate consistency guarantee.

Recommended first scope: connection-only failover, same-domain writable DCs, no automatic mid-operation replay. Validate primary-down fallback, invalid credentials (no fallback), TLS rejection (no bypass), both-DCs-down response, bounded timing, replication lag and lost write responses. The service itself remains a single instance; DC redundancy does not provide service-host redundancy.

Sources:

- SDK failover support: https://docs.ldap.com/ldap-sdk/docs/getting-started/failover-load-balancing.html
- FailoverServerSet: https://docs.ldap.com/ldap-sdk/docs/javadoc/com/unboundid/ldap/sdk/FailoverServerSet.html
- AD replication latency: https://learn.microsoft.com/en-us/windows/win32/ad/replication-behavior-in-active-directory-domain-services
