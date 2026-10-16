# Business-role provisioning — 1.3.1-SNAPSHOT

An account-plus-permissions business role can generate fulfillment before its new account is collected. The account GUID/DN will then be absent from IG's membership context. DPS can resolve the account directly in AD using the same stable employeeID reference it wrote during creation.

## Configure the IG transformation

Update the running service AND the request script; this requires the new binary. The DN and account-GUID scripts now prefer an explicit selected account and fall back to `{ "reference": "123456-EMP" }` only for ADD_PERMISSION_TO_USER when its identifier is missing during initial account provisioning. Removals never fall back to a reference. Invalid supplied identifiers still fail. The identity-GUID variant deliberately continues to use userProfile.userId for membership; use the account-GUID or DN variant for accounts created from identities in another directory.

Each script has one `accountReference(item)` function. Customize its expression once, for example identity workforceId plus `-EMP`, and make userProfile.workforceId available in fulfillment context. That identical policy is used for creation and initial membership additions only. It must be stable and identify exactly one intended account in the connected domain. References are 1–16 characters, matching the standard AD employeeID limit. Use a different suffix/target configuration for a different account purpose; do not derive the reference from mutable names or the change item ID.

If a selected account identifier is supplied, DPS uses it; it does not substitute another account when that identifier fails resolution. Creation placement and UPN settings are still required only for creation, not reference lookup.

Example membership payload before IG collection:

```json
{
  "changeItemId": "117",
  "target": "DEMO",
  "requestType": "ADD_PERMISSION_TO_USER",
  "user": {"reference": "123456-EMP"},
  "group": {"guid": "99999999-8888-7777-6666-555555555555"}
}
```

Reference resolution searches the DC's defaultNamingContext (domain), rather than dps.search-base, to match the creation lookup. The bind account must be able to discover all potential duplicate references. Two matching objects, a non-user match, or unreadable required attributes fail closed. employeeID is not uniquely constrained by AD: reserve this namespace and retain one active creation writer as described in [account creation](account-creation.md).

## Creation before membership

For membership additions with a reference, DPS polls AD for up to `dps.account-reference-wait-ms=10000` (default). Configurable range: 0–30000. Polling occurs at most four times per second per waiting request. This is a lookup wait, not a replay of writes. An individual LDAP operation is also subject to dps.operation-timeout-ms and can extend elapsed time beyond the lookup wait. Keep IG's HTTP read timeout above the wait plus connection/bind/lookup/write time; the import examples use 60 seconds.

If no account appears, HTTP 503 ACCOUNT_NOT_READY leaves the item available for retry. Ambiguity, ACL failures and connection errors are not treated as account creation in progress. Removals and deletion do not wait for an account to be created. Missing accounts remain errors. A waiting request consumes a service concurrency slot; do not assume this mechanism can sequence a large parallel batch. Creation may return CREATION_BUSY when another creation is active.

Use [the optional IG changeset ordering sample](../scripts/ig-changeset-order.js) on the application's changeset modification configuration to put ADD_APPLICATION_TO_USER before ADD_PERMISSION_TO_USER. This sample assumes the business role already generates both items; it does not inject account requests. Review any existing customer ordering before replacing it. Save/publish the configuration and validate on your IG version. Sorting is a scheduling aid, not a guarantee that IG waits for completion, and cannot overcome a connector that blocks later creation until a failed earlier item is resolved. The bounded wait covers short overlaps; it is not a durable orchestration queue.

## Application removal deletes the account

REMOVE_APPLICATION_FROM_USER is now a deletion operation, identical to REMOVE_ACCOUNT. Send `account` with exactly one of dn or guid from the selected account context. Both the IG scripts and DPS reject references for deletion. If context is missing, collect/publish or correct the mapping before retrying; no workforceId/employeeID lookup is attempted.

```json
{
  "changeItemId": "118",
  "target": "DEMO",
  "requestType": "REMOVE_APPLICATION_FROM_USER",
  "account": {"guid": "11111111-2222-3333-4444-555555555555"}
}
```

This deletes one AD user per request, not every account belonging to an identity. If IG produces several selected-account items, each is processed separately. It does not unlink the IG identity or disable the account. Deletion uses the inspected object's immutable GUID. No recursive deletion or missing-account success is introduced. Repeating deletion returns 404; a software rollback does not restore deleted accounts.

If IG also emits explicit membership removals, order these before account deletion. After deletion, membership requests against that missing user will fail; DPS does not claim a successful modification of a nonexistent account. Removal order must be checked with the actual role-generated changeset.

## Lab acceptance

1. Use a new disposable identity with no collected AD account. Grant a role that creates one account and two memberships. Confirm the creation and membership items are ordered as intended.
2. Confirm membership payloads contain user.reference before collection, and the same value is in the new AD user's employeeID. Check actual group membership on the configured DC.
3. Confirm both membership changes complete before collection/publication; collection is still needed for IG verification.
4. Remove the role. Confirm REMOVE_APPLICATION_FROM_USER contains the intended account DN/GUID and deletes only that account. Check any separate membership removal items and their ordering.
5. Repeat with the delegated bind account, not Administrator. Test denied deletion and a duplicate reference; confirm neither reports success.
6. Submit a reference-only deletion for each deletion type: expect HTTP 400 and no LDAP delete. Also confirm missing selected account context fails in the IG transformation even if workforceId is present.
7. Test membership before creation with no matching AD user. Expect bounded wait then 503 ACCOUNT_NOT_READY, not implicit creation or success.

Automated checks use a generic LDAP fixture and resource-level HTTP mapping tests. They do not prove IG scheduling, AD ACL inheritance, real AD GUID-name deletion or the customer's role semantics. Validate those in the lab before deploying this development build.
