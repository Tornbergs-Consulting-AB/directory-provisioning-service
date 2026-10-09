# Design — 1.0.0

## Request execution

A Quarkus Java service receives HTTPS requests and uses the UnboundID LDAP SDK to update direct Active Directory group membership over LDAPS. Linux and Windows use the same application. Java 21 and the Windows service wrapper are installed separately.

`POST /api/v1/provisioning` accepts one user reference, one group reference, a change item ID, a logical target and an operation. Each reference contains exactly one DN or canonical AD objectGUID. The service resolves current DNs and verifies object classes before checking/modifying membership. It never selects an account from an identity automatically; IG must supply the intended account identifier.

`ADD_PERMISSION_TO_USER` adds one `member` value. `REMOVE_ACCOUNT_PERMISSION` and `REMOVE_PERMISSION_ASSIGNMENT` remove one value. The service never replaces the full membership list. Nested membership is not considered direct membership. Already-present additions and already-absent removals succeed with an UNCHANGED response. Missing directory objects are errors.

Membership checks and writes use the same bound connection/DC. Already-present/absent LDAP responses are reconciled with a direct-membership check; unrelated LDAP errors remain errors. Concurrent opposing requests may leave either state. Moving an object between resolution and modification may require a retry.

## Credentials and trust

IG supplies AD bind credentials in the HTTP Basic Authorization header over HTTPS. The service accepts only its configured bind DN and never stores AD passwords. Each request opens its own LDAP connection, binds as the caller, and closes the connection afterward. No credential-bearing queue, credential cache or shared authenticated connection pool is used.

TLS validates the LDAPS certificate chain and hostname. LDAP referrals are disabled. HTTPS terminates at the service; forwarded-protocol headers do not bypass its TLS requirement. AD ACLs authorize writes. The GUID search base controls object discovery and does not impose an authorization container boundary on DN requests.

The HTTPS server keystore and LDAP truststore are separate. Their passwords are deployment secrets; they are distinct from per-request AD credentials.

## Capacity, retry and diagnostics

Configured connect/operation timeouts and a concurrency limit bound directory work. There is no automatic write replay, durable execution ledger or persistent deduplication key. Repeat membership operations converge on the requested direct-membership state, subject to concurrent changes and directory visibility. Correlation IDs identify attempts; `fulfillmentId` identifies an IG change item and is not a stored execution receipt.

INFO logs record outcomes and timing. DEBUG logs show identifier types and resolved DNs. Optional request JSON diagnostics parse and redact known sensitive fields before logging; headers and malformed raw bodies are not logged. Unknown JSON fields and object identifiers can still contain sensitive information. See [configuration](configuration.md).

## Supported limits

Version 1.0.0 supports one logical target and one writable DC. Outages require recovery of that DC and a retry. User creation, deletion, attribute updates, multi-target routing and DC failover are outside this release. IG sub-applications that omit account context require separate integration work. See [API and IG integration](api-and-ig.md) and [directory target](directory-target.md).
