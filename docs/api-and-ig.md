# API and IG integration — 1.1.0-SNAPSHOT

## Endpoints

| Method and path | Purpose |
|---|---|
| GET `/health` | Process liveness and application version; no LDAP bind |
| GET `/api/v1/provisioning` | Authenticated LDAPS TLS/bind check; no directory modifications |
| POST `/api/v1/provisioning` | Direct group membership or AD user account deletion |

Use HTTPS. GET/POST provisioning calls require Basic credentials for the configured AD bind DN. Set IG's connection-test and fulfillment paths to `/api/v1/provisioning`. Java and WinSW are not part of the API authentication model.

## Request

```json
{
  "changeItemId": "10001",
  "target": "DEMO",
  "requestType": "ADD_PERMISSION_TO_USER",
  "user": {"guid": "11111111-2222-3333-4444-555555555555"},
  "group": {"dn": "CN=STORE-User,OU=Groups,DC=example,DC=com"}
}
```

GUIDs are canonical hyphenated AD objectGUID values, not IG internal IDs. Supply exactly one `dn` or `guid` for each object; mixed user/group identifier types are allowed. `changeItemId` is required and contains 1–20 decimal digits. `target` must exactly match `dps.target-name`. The optional body `requestId` is accepted but is not used for correlation; use `X-Correlation-ID`. If that header is absent/invalid, the service generates a UUID. Accepted header characters are letters, digits, period, underscore, colon and hyphen, up to 128 characters.

| requestType | Directory operation |
|---|---|
| ADD_PERMISSION_TO_USER | Add the user as a direct group member |
| REMOVE_ACCOUNT_PERMISSION | Remove direct membership |
| REMOVE_PERMISSION_ASSIGNMENT | Remove direct membership |
| REMOVE_ACCOUNT | Delete one AD user account; use `account`, not `user`/`group` |

## Account deletion (1.1.0-SNAPSHOT)

```json
{
  "changeItemId": "114",
  "target": "DEMO",
  "requestType": "REMOVE_ACCOUNT",
  "account": {"guid": "11111111-2222-3333-4444-555555555555"}
}
```

Alternatively use `account: {"dn": "CN=Disposable Account,OU=Users,DC=example,DC=com"}`. Supply exactly one identifier. Omit `user` and `group`; supplying these alongside deletion is rejected. The API does not infer an account from an identity GUID or from raw IG context. Use the updated transformation scripts to map `accountProfile.accountId` (GUID) or `accountProvId` (DN).

A successful delete returns HTTP 200, `outcome=CHANGED`, `code=ACCOUNT_DELETED`, the normal fulfillment ID and a comment containing the change item, operation and account DN. For response compatibility `userDN` contains the account's last read DN; `groupDN` is null. Already-missing or inaccessible accounts return 404 rather than claiming success. The service has no ledger proving a previous delete; reconcile a timeout/lost response before retrying or resolving the IG task.

The service reads objectClass and objectGUID before deletion, allows AD users but rejects computers/groups, and targets the final LDAP Delete using AD's immutable GUID-name syntax for both input types. This prevents a subsequent DN reuse from redirecting the delete. A GUID that changes between resolution and reading returns 409 ACCOUNT_CHANGED; missing/unreadable GUID returns 409 ACCOUNT_GUID_UNAVAILABLE. It sends no recursive/tree-delete control. Non-leaf objects or AD deletion protection can therefore cause failure. AD ACLs authorize deletion; no extra client-side account scope is introduced. GUID searches still use the configured search base; direct DN lookup follows the existing ACL-based policy.

This uses AD-specific GUID-name request syntax, not an RFC4528 assertion control. See [Microsoft's alternative DN forms](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-adts/92a54869-38d7-4e71-a3be-5f67a0dcdd7e) and [AD Delete behavior](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-adts/6c21dd2f-40cd-4963-90a2-a7f657f319a4). Local tests emulate this AD extension; successful real-AD deletion and IG verification still require lab validation.

## Response

Successful operations return HTTP 200, including idempotent no-change results:

```json
{
  "changeItemId": "10001",
  "requestId": "iga-10001",
  "fulfillmentId": "iga-10001",
  "outcome": "UNCHANGED",
  "code": "ALREADY_MEMBER",
  "comment": "Change Item '10001' Fulfilled. Type: ADD_PERMISSION_TO_USER, Target Account: <resolved user DN>, Target Permission: <resolved group DN> No change required: already a direct member.",
  "userDN": "<resolved user DN>",
  "groupDN": "<resolved group DN>"
}
```

Changed operations use `outcome=CHANGED`, `code=MEMBERSHIP_CHANGED`. Absent-member removals use `outcome=UNCHANGED`, `code=NOT_MEMBER`. `fulfillmentId` is `iga-<changeItemId>`; it is not a durable receipt or deduplication record.

| HTTP | Meaning |
|---|---|
| 200 | Changed or already in the desired direct-membership state |
| 400 | Invalid JSON/request/identifier/target/operation, wrong object class, or HTTPS required |
| 401 | Missing/malformed Basic credentials or AD invalid credentials |
| 403 | Disallowed bind DN or explicit AD insufficient access |
| 404 | Directory object not found or not visible; includes repeated account deletion |
| 409 | Ambiguous GUID, changed account during deletion lookup, or unreadable account GUID |
| 502 | Other rejected LDAP operation |
| 503 | Busy, unavailable/timed-out directory or directory/TLS configuration error |

Structured failures contain `outcome=FAILED`, a machine-readable `code` and safe `comment`; `fulfillmentId` and unresolved DNs are null. Framework-level errors such as unsupported methods/media types or oversized requests can have a different response format. Connection-test success returns `status=DIRECTORY_READY` and a comment, rather than a fulfillment receipt. Responses carry `Cache-Control: no-store` and `X-Correlation-ID`.

## IG transformations and observed behavior

For preconfigured target imports with embedded scripts and mappings, see [Importable IG fulfillment targets](ig-import.md). Start with the account GUID variant; the identity GUID variant is an explicit alternative. Both remain subject to the documented sandbox import validation.

Use [ig-request-guid.js](../scripts/ig-request-guid.js) when `accountProfile.accountId` and `permissionProfile.permissionId` contain canonical AD objectGUIDs. Use [ig-request.js](../scripts/ig-request.js) for `accountProvId`/`permProvId` DNs. Update the example target DEMO in either script. Use [ig-headers.js](../scripts/ig-headers.js) for change-item correlation. Map response `fulfillmentId` and `comment` with the Generic REST Fulfiller response transformations.

### Configure both response transformations

The request script does not map the response. Configure these two separate response transformations in the Generic REST Fulfiller:

| Response mapping | Sample script | Output |
|---|---|---|
| Fulfillment ID | [ig-response-fulfillment-id.js](../scripts/ig-response-fulfillment-id.js) | Service `fulfillmentId`, for example `iga-13955` |
| Comment | [ig-response-comment.js](../scripts/ig-response-comment.js) | Service `comment`, including change item ID, operation, resolved DNs and any no-change explanation |

Paste each script into its corresponding response transformation. Both receive the connector envelope in `inputValue`: `response_code`, `content_type` and `content`. The `content` property is itself the JSON string returned by the service, so the scripts parse both layers. They accept numeric or string HTTP 200 and `application/json` with optional charset parameters. The `dps...` variable names avoid redefining a script-engine global such as `status`.

These samples are for fulfillment responses, not the connection-test response, which has no `fulfillmentId`. They require the service's HTTP 200 contract and the appropriate nonempty response field. Already-member/not-member outcomes are HTTP 200 and preserve their descriptive comments. The scripts do not control IG's HTTP error handling or verification; non-2xx bodies may never reach these transformations. Do not convert service failures to HTTP 200 merely to display their comments.

In the lab, HTTP 200 led to completed fulfillment/pending verification; the next successful collection/publication verified the directory change. Already-member success preserved its descriptive comment. HTTP 400 and 503 led to RETRY with IG's generic client/server error rather than the service's detailed error body. Correlate the IG change item with service logs for diagnosis. These observations do not establish IG behavior for every error status/version.

IG must provide the intended account, especially for multi-account identities. Sub-applications can omit account context in the generic REST payload; do not substitute the identity ID or select an arbitrary account. That integration limitation is outside the accepted 1.0.0 scope.
