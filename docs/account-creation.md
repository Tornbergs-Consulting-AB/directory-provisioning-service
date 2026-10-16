# Account creation — 1.3.1-SNAPSHOT

ADD_APPLICATION_TO_USER creates one ordinary AD user account. IG supplies the container DN, names, customer-defined reference and text attributes. DPS creates it disabled (`userAccountControl=514`), without a password, group assignments or an activation step. Existing membership/deletion operations remain supported.

This is a development build. Local LDAP/HTTP/script tests pass; real AD account creation, suffix behavior, access-denied handling and IG collection/publication verification must be tested in the lab before release.

## Request contract

```json
{
  "changeItemId": "116",
  "target": "DEMO",
  "requestType": "ADD_APPLICATION_TO_USER",
  "creation": {
    "reference": "123456-EMP",
    "containerDN": "OU=Users,OU=Tornbergs,DC=demo,DC=tornbergs,DC=consulting",
    "cn": "Daniel Persson",
    "attributes": {
      "sAMAccountName": "dpersson",
      "userPrincipalName": "dpersson@demo.tornbergs.consulting",
      "givenName": "Daniel",
      "sn": "Persson",
      "displayName": "Daniel Persson",
      "department": "Sales",
      "title": "Account Manager"
    }
  }
}
```

Do not supply account, user or group references for creation. The discovery envelope (`context`) is not the creation contract. No existing account GUID/DN or permission is required.

The reference is stored unchanged in **employeeID in the same LDAP Add**. The customer defines it in the IG script, for example `workforceId + '-EMP'` or `workforceId + '-ADM'`. It must identify one intended account and must not be reused. AD's standard employeeID schema has a **16-character maximum**, enforced here. Long identifiers are rejected, never truncated or hashed. Reserve this attribute and its reference namespace for this purpose; do not have another system overwrite it.

Placement comes from IG. DPS uses RootDSE `defaultNamingContext` to determine the connected DC's domain and requires the requested parent to be an existing OU/container in that domain. It creates no containers and follows no referrals. Placement is not restricted to dps.search-base; AD ACLs remain the authorization boundary. Existing GUID operations continue to use dps.search-base.

## Configurable text attributes

```properties
dps.creation-allowed-attributes=sAMAccountName,userPrincipalName,givenName,sn,displayName,title,department,mail,description
dps.creation-required-attributes=sAMAccountName,userPrincipalName,givenName,sn
dps.creation-max-suffix=100
```

These are also the bundled policy defaults; no domain, OU, credential or UPN suffix is bundled. Restart after changing settings. Required names must be a subset of allowed names and must include sAMAccountName and userPrincipalName. Attribute names are case-insensitive; duplicate names differing only by case are rejected. Use plain LDAP attribute names without options/OIDs.

Only **single-valued nonempty JSON strings** are accepted. Arrays, objects, numbers, booleans and nulls are rejected, not coerced. Values may contain at most 4096 characters and no control characters; AD's own attribute schema can impose stricter limits. Maximum 64 attributes per request. Optional empty values should be omitted by IG.

To add `company`, add it to the allowlist and to the IG attributes object; no Java changes are needed. Ensure it is an appropriate writable single-valued text attribute in your AD schema. DPS does not override AD schema or ACL rules. Reserved identity, object class, password, account-control, security and membership attributes are rejected even if included in the allowlist. In particular, employeeID must come only from creation.reference, and CN only from creation.cn.

## Naming and retries

DPS checks sAMAccountName and UPN within the **connected domain**, independently of the GUID search base. It checks CN within the requested container. There is no forest-wide lookup or uniqueness guarantee; the DC can still reject a request under its own forest constraints.

Attempt zero uses the requested names. On an occupied name, attempt 1 appends `1` to the sAMAccountName, UPN local part and CN; attempt 2 appends `2`, etc. Existing names are never adopted merely because they match. Maximum suffix is configurable 0-1000; default 100 means the original plus up to 100 numbered candidates. Exhaustion returns HTTP 409 NAME_COLLISION_LIMIT.

The initial sAMAccountName must be 1-20 ASCII letters/digits/dot/underscore/hyphen, start with a letter/digit and not end in a dot. UPN uses a simple ASCII local part and DNS suffix (maximum 1024 characters). CN is at most 64 characters and supports Unicode; DPS uses LDAP DN escaping. Base strings are shortened if necessary to fit a suffix within those limits. Names supplied in the payload can differ; DPS applies the same number to all three, rather than forcing the UPN local part to equal the sAMAccountName. displayName is not suffixed or required to be unique.

Before allocating names, DPS looks for employeeID across the domain. One matching disabled AD user at the expected container/name, with all supplied attributes matching a permitted candidate, returns HTTP 200 UNCHANGED / ACCOUNT_ALREADY_EXISTS. Names compare case-insensitively and other requested attribute values compare exactly. Multiple references, an enabled account, a moved account, differing requested values or an incompatible object return HTTP 409 REFERENCE_CONFLICT; nothing is updated. Changing naming policy or suffix limits can therefore affect retry recognition.

A successful creation returns HTTP 200 CHANGED / ACCOUNT_CREATED. Responses include actual `accountName`, `userPrincipalName` and the account DN in the existing `userDN` field. The standard fulfillment ID/comment mappings continue to work; the comment includes the chosen names. No group DN is returned.

DPS serializes creations **within one process**; concurrent creation requests can return 503 CREATION_BUSY. employeeID does not provide a service-enforced atomic uniqueness constraint across independent writers. Operate one active DPS creation writer for a reference namespace; external provisioning tools must coordinate references. The bind account must be able to read relevant accounts throughout the domain for these checks to be meaningful.

DPS retries name allocation after an LDAP Add rejection only when it can confirm a name collision. It does not replay Add after timeouts/transport failures. If the Add committed before the response was lost, the next caller retry finds its employeeID and reconciles the matching account. If the outcome cannot be established, investigate rather than bypassing reference checks. There is no durable request ledger and no ongoing synchronization.

## IG setup

Use the updated full request script for your chosen variant:

- [Account GUID](../scripts/ig-request-guid.js)
- [Identity GUID for membership](../scripts/ig-request-user-guid.js)
- [DN](../scripts/ig-request.js)

All three now have an ADD_APPLICATION_TO_USER branch. Their creation function is a **customer-editable example**, not a universal naming policy. Set `dpsCreationContainer` and `dpsCreationUpnSuffix` near the creation function; both deliberately default to empty and block creation until configured. You can replace the container setting with logic based on identity attributes.

The example requires `userProfile.firstName`, `lastName`, and `workforceId`, and optionally maps `jobTitle` and `department`. Add those to IG's Recipient/Fulfillment Context Attributes, with matching names. Both object profiles and JSON-encoded string profiles are supported. Edit the reference expression for your customer's employee/admin account policy. The sample folds Swedish letters for the first-initial/surname account name; edit it for other naming needs. It preserves the person's full name in CN/displayName.

Creation needs userProfile in the Content mapping. Existing account deletion still requires accountProfile.accountId or accountProvId; membership retains its own requirements. The regenerated import examples include both userProfile and accountProfile and enable ADD_APPLICATION_TO_USER, but their environment and creation settings still need completion. Sandbox import compatibility remains to be validated in IG. Do not route this type to a 1.0/1.1 service.

## Upgrade and lab validation

1. Stop the service, back up the current complete quarkus-app folder, and replace it with `dist/quarkus-app` from the 1.3.1-SNAPSHOT distribution. Keep your external config/certificates/WinSW or systemd settings. `/health` should report 1.3.1-SNAPSHOT after restart. Source builds use Java 21 and `mvn clean verify`; the prepared runtime uses Java 17-compatible bytecode.
2. Review the creation attribute settings above. Give the caller-supplied AD bind account read access for domain lookups and the AD rights needed to create/populate user objects only where intended. Parent placement and attributes remain subject to AD ACL/schema enforcement.
3. Save the updated IG transformation, set target/placement/UPN suffix/reference policy, enable ADD_APPLICATION_TO_USER in the target and application routing, and request application access for a test identity with a unique short reference.
4. Verify the created object, names, employeeID, supplied attributes, disabled state and absence of a password. Repeat the same request: expect ACCOUNT_ALREADY_EXISTS and exactly one account.
5. Create another test reference with the same requested names: expect a numbered account, with the same suffix in SAM/UPN/CN. Repeat it: it must return that same account. Test suffix exhaustion with a low configured limit.
6. Test changed values for an existing reference, duplicate references, forbidden attributes, wrong value types, missing context and insufficient AD create/write rights. Failures must not create an extra account or claim success.
7. Collect and publish accounts, ensuring the collected account-to-identity mapping can associate this newly created account. Confirm IG verification. Matching is a collector configuration responsibility; employeeID reference storage alone does not automatically configure it.
8. Re-run membership and deletion regression tests. No account activation or password delivery is implemented. Reference-based membership lookup and bounded waiting are described in the business-role guide; these are not durable workflow orchestration.

Rollback: stop the service and restore the complete prior runtime plus its IG scripts/import routing. Disable ADD_APPLICATION_TO_USER when restoring 1.1.0 or earlier. A software rollback does not remove accounts already created.

## References

- [Microsoft employeeID schema limit](https://learn.microsoft.com/en-us/windows/win32/adschema/a-employeeid)
- [Microsoft userAccountControl flags](https://learn.microsoft.com/en-us/troubleshoot/windows-server/active-directory/useraccountcontrol-manipulate-account-properties)
- [RootDSE and defaultNamingContext](https://learn.microsoft.com/en-us/windows/win32/adschema/rootdse)

## Account plus permissions in a business role

See [business-role provisioning](business-role-provisioning.md) to resolve the new account by its employeeID reference before collection, order changesets and delete the account when the application assignment is removed. Delegate [AD bind-account rights](ad-delegation.md) for all three operations.
