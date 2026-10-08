# Design decisions

## Approved scope

- REST input, LDAP output; Java on Windows and Linux.
- First directory operations: add/remove a direct group member. No account creation yet.
- Basic header carries AD credentials. HTTPS required for real credentials. No stored AD passwords.
- Configured target names map to approved LDAP endpoints, search base(s), CA truststore and allowed bind principals. Search bases locate objects; they are not authorization container restrictions.
- AD ACLs are the primary authorization restrictions; no extra user/group container allowlist.
- LDAPS, certificate chain and hostname validation. No automatic referral following.
- One bound connection per request initially; close it after completion. No cross-principal connection reuse.
- Modify only one member value. Never replace the entire member attribute.
- GUID preferred; DN accepted. Exactly one identifier per object. AD GUID byte encoding requires real AD tests.
- Already-member add / absent-member removal return 200 UNCHANGED with a descriptive comment.
- Concurrent changes: confirm the final direct-membership state for already-present/absent LDAP responses. Other LDAP errors remain errors.
- Missing object is distinct from absent membership. Reject missing/ambiguous identifiers and unsupported operations.
- Configurable timeouts and bounded concurrency. No blind automatic replay after unknown write outcome; re-read membership and safely converge on retry.
- Correlation IDs and structured diagnostics; redact credentials at every level; file rotation.

## Proposed production contract (not yet implemented)

`POST /api/v1/provisioning`, `Authorization: Basic ...`, `X-Correlation-ID: ...`.

```json
{
  "requestId": "83",
  "target": "ad-dev",
  "requestType": "ADD_PERMISSION_TO_USER",
  "user": {"guid": "5041289d-c648-4874-a038-14b01d8d7d3d"},
  "group": {"dn": "CN=APP_DOIT,OU=Groups,DC=example,DC=com"}
}
```

IG ADD_PERMISSION_TO_USER -> add; REMOVE_ACCOUNT_PERMISSION and REMOVE_PERMISSION_ASSIGNMENT -> remove. GUID must be the AD objectGUID, never an IG internal identifier. LDAP filter construction must use SDK escaping and binary GUID handling.

Success response: requestId, fulfillmentId, outcome CHANGED/UNCHANGED, machine-readable code and comment. Error response: FAILED plus code/comment. Final HTTP status mapping remains gated on IG experiments.

No durable credential-bearing request queue. Membership operations are naturally convergent; requestId is for tracing, not yet a persistent deduplication guarantee. If future provisioning operations require stronger idempotency, design a durable operation ledger separately.

## Open decisions / validation gates

1. Establish IG status, comment, fulfillment ID and retry behavior for each test response.
2. Select the account GUID source, including multi-account identities and subordinate applications. Never select an arbitrary account.
3. Actual AD bind principal form (UPN recommended), certificate trust and service-account allowlist values.
4. Connection/read/operation timeout values below IG HTTP timeout; writable DC/failover behavior and replication tests.
5. Real AD tests for moved objects, ACL denial, nested membership, concurrent writes and dropped responses.
6. Windows service wrapper, Linux systemd configuration, HTTPS server certificate and rotation.

## Milestones

1. Response probe and IG evidence.
2. Quarkus REST contract, authentication/target config and validation.
3. UnboundID LDAP adapter and AD integration tests.
4. Packaging, operational logging and runbooks.

## Version 0.2 implementation

DN path implemented for one DEMO target. changeItemId is mandatory and echoed in the response/comment. RequestId is correlation; fulfillmentId is iga-<changeItemId>, not a durable execution receipt. HTTPS required directly; no trust of forwarded-proto headers. Search base scopes GUID lookup. DN-based real AD add/repeat/remove/repeat validated by the user; GUID-based AD testing remains pending. Probe disabled by default.
