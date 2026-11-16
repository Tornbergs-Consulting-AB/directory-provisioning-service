# Profile attribute updates — 1.4.0-SNAPSHOT

MODIFY_USER_PROFILE applies explicit value additions/removals to one existing AD user. IG maps identity attribute names to LDAP names. DPS independently validates an update allowlist, reads the live AD schema, inspects the user and submits at most one atomic LDAP Modify.

## Upgrade and enable

1. Back up the complete 1.3.1 quarkus-app runtime, external config and IG target export. Stop the service and replace the entire quarkus-app folder with dist/quarkus-app from this distribution. Keep config/application.properties, certificates, password environment and service wrapper settings. This build requires Java 21. Check /health reports 1.4.0-SNAPSHOT.
2. Add or review this external configuration (the shown value is also the bundled default):

```properties
dps.update-allowed-attributes=otherTelephone,department
```

Only these two attributes are enabled by default. Creation and update allowlists are separate. Adding an attribute requires both an explicit IG mapping and an allowed LDAP name here, then a service restart. Empty or invalid configuration prevents production startup. AD ACLs still determine effective write access: delegate Write otherTelephone and Write department on the intended descendant User objects, along with read access to their current values/objectGUID and the relevant schema metadata. No administrator membership is required.

3. Enable MODIFY_USER_PROFILE in the IG Generic REST target and relevant routing. Existing HTTP endpoint, Basic authentication and fulfillment ID/comment response mappings remain valid. The sample imports have been regenerated, but importing them into an existing customized target is optional. Validate import behavior on the installed IG version.
4. Include userAttributeModifications and userProfile in fulfillment input. Add identity context fields ext_aDGUID and/or ext_aDDN; collect and publish first. Use the already configured transformation from the discussion, or the updated scripts/ig-request-guid.js example. Preserve your customer creation reference/naming/placement settings when replacing a script: the repository's default creation reference includes -EMP, whereas the demo may use workforceId alone.

## Contract

```json
{
  "changeItemId": "174",
  "target": "DEMO",
  "requestType": "MODIFY_USER_PROFILE",
  "user": {"guid": "35c3d1fc-6e13-493e-9501-0d103bcc344f"},
  "modifications": [
    {"attribute": "otherTelephone", "add": ["4321", "4221"], "remove": ["1236", "1235"]},
    {"attribute": "department", "add": ["Not-Sales"], "remove": ["Sales"]}
  ]
}
```

user requires exactly one DN or GUID. References/workforceId, group, account and creation are forbidden for this operation. GUID lookup uses dps.search-base; the user must be visible there. DN targeting is also accepted; after reading the target DPS uses its immutable GUID for Modify, protecting against rename/DN reuse between inspection and write. A supplied GUID is verified against the inspected object.

Each modification contains one LDAP attribute name and both add/remove string arrays, at least one containing a value. There are at most 32 attributes and 256 total requested values. Values must be nonblank JSON strings of at most 4096 characters without control characters; AD enforces additional schema lengths and constraints. Duplicate attribute blocks, duplicate equivalent values and the same equivalent value on both sides are rejected. Attribute options, numeric OIDs and alias-based targeting are not accepted.

No replace-all/clear flag or null-value convention is implemented. Explicit removals can remove the last or all specified values, and explicit additions can populate an empty attribute, but the corresponding real IG input examples remain to be captured. Missing arrays, nulls and empty strings are rejected, not interpreted as clear. Attribute order and multivalue order have no semantic significance.

## Mapping in IG

The provided transformation maps exact attributeName keys, not translated display labels:

```javascript
var dpsAttributeMap = {
    'ext_otherTelephone': 'otherTelephone',
    'ext_department-Ext': 'department'
};
```

Based on the observed IG input, changeType 1 means add and 2 means remove. The script groups deltas per LDAP attribute. Unknown mappings or change types fail the entire transformation. Object and JSON-string profile/modification arrays are supported. The collected ext_aDGUID is preferred; an invalid supplied GUID fails rather than falling back. ext_aDDN is used only when GUID is absent. Neither userProfile.userId nor workforceId is substituted for profile targeting.

## Schema and attribute restrictions

DPS reads schemaNamingContext from RootDSE and searches AD attributeSchema objects by lDAPDisplayName. Supported syntax is Unicode String (2.5.5.12) or case-insensitive string (2.5.5.4), with a readable isSingleValued value. Linked, system-only, nontext and unknown/unreadable schema attributes are rejected. Ranged attribute reads are rejected because they do not provide a complete inspected value set.

The endpoint additionally reserves identifiers, naming, security/account-control/password attributes, employeeID, member/memberOf, manager/directReports, SPNs and delegation/key-credential fields. Listing a reserved name in the allowlist does not bypass this restriction. Supervisor changes remain unimplemented. Do not allow arbitrary text attributes without reviewing their security and application meaning; other schema/app-specific requirements may still cause AD rejection.

## Values, concurrency and retries

- Multivalued changes remove only specified values that were observed, add only specified values that were absent, and preserve unrelated values.
- For a single-valued field, at most one add and one remove is accepted. Sales to Not-Sales works when current is Sales; retry is a no-op when current is Not-Sales. A third value such as Finance conflicts, including an add that would overwrite an unmentioned existing value.
- Text matching uses LDAP-style case-ignore string matching. Case-only changes are no-ops, not spelling updates.
- All needed strict DELETE-value operations precede the corresponding ADD operations, in one Modify addressed by the inspected objectGUID. No REPLACE, permissive-modify control or whole-attribute deletion is used.
- A relevant concurrent change can make an expected DELETE fail or an ADD collide; AD then rejects the atomic Modify and none of this request's changes are applied. Unrelated concurrent multivalue additions are preserved. This is value-level protection, not a global uSNChanged lock: unrelated account changes are allowed, and no-op results describe the observed read state.
- There is no automatic write replay. LDAP timeout/disconnection can leave outcome unknown; retry reads the current state and computes the remaining deltas. It does not overwrite a conflicting single-valued attribute to force success.

The schema/read checks require visibility of existing values. If a customer ACL hides values, strict AD modifications may reject an attempted change; grant the required read rights rather than treating omitted data as authoritative empty data.

## Responses and diagnostics

| HTTP/code | Meaning |
|---|---|
| 200 PROFILE_UPDATED / CHANGED | Requested delta was applied |
| 200 PROFILE_ALREADY_SATISFIED / UNCHANGED | No LDAP Modify needed |
| 400 INVALID_MODIFICATIONS or INVALID_IDENTIFIER | Invalid request/reserved field/reference target |
| 400 ATTRIBUTE_NOT_ALLOWED | LDAP field is not enabled in the update allowlist |
| 409 ATTRIBUTE_CONFLICT | Unexpected single value, concurrent value collision or AD constraint failure; inspect AD before retrying |
| 409 ACCOUNT_CHANGED / ACCOUNT_GUID_UNAVAILABLE | Stable account targeting could not be established |
| 409 INCOMPLETE_ATTRIBUTE_VALUES | Ranged attribute read detected |
| 503 ATTRIBUTE_SCHEMA_UNAVAILABLE | Required AD schema metadata not readable/available |
| 403 ACCESS_DENIED | AD explicitly denied an operation |
| 503 DIRECTORY_UNAVAILABLE | LDAP connection/timeout failure; write outcome may be unknown |

Success comments include the change item, request type and resolved account DN. Values are not copied into success comments or default INFO logs. Existing opt-in redacted DEBUG payload logging includes modification values and therefore needs restricted log access. IG may reduce non-200 errors to its HTTP error text; use the request correlation ID to inspect DPS logs/the direct API response.

## Real AD / IG acceptance

Use the delegated bind account and a disposable user. Seed otherTelephone with 1236, 1235 and an unrelated value 9999; set department to Sales. Submit the sample with that user's actual GUID. Expect 200 PROFILE_UPDATED; resulting phones are 4321, 4221 and 9999, department Not-Sales. Repeat the same request: expect PROFILE_ALREADY_SATISFIED and no duplicate values. Collect/publish and confirm IG verification.

Next, set department to Finance and submit the original Sales-to-Not-Sales request again. Expect 409 ATTRIBUTE_CONFLICT and no changes to any attribute in that request. Test an unlisted field, reference-only target and missing write permission; none may return success or partially apply. Move the account without collecting and confirm GUID-based updates still resolve it.

Automated fixtures test deltas, single-value conflicts, atomic rollback, relevant/unrelated races, schema restrictions, GUID targeting, deny/timeout propagation and HTTP/script mappings. They emulate native AD GUID Modify addressing and schema metadata; real AD/IG behavior remains lab validation, including the three pending empty/last-value/all-value IG examples.

Rollback restores the complete prior runtime, scripts and routing. Disable MODIFY_USER_PROFILE when reverting to 1.3.1. Reverting software does not reverse attributes already modified in AD.
