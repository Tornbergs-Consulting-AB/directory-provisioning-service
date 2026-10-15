# Account deletion — 1.1.0-SNAPSHOT lab validation

REMOVE_ACCOUNT deletes an AD user account. It does not disable it, unlink it from its identity or merely remove memberships. This is a development build; version 1.0.0 remains unchanged. No real AD deletion has been performed by the development checks.

## Upgrade and configure

1. Apply the source patch and build using Java 21: `mvn clean verify`. The output is the entire `target/quarkus-app` folder, not just quarkus-run.jar.
2. Stop the service. Back up the current runtime, configuration and IG target export. Replace the entire installed `quarkus-app` folder with the new output, retaining protected configuration, certificate files and service environment/password settings. Restart and check `/health` reports `1.1.0-SNAPSHOT`.
3. Ensure the permitted AD bind principal has the AD rights needed to delete the intended test account, in addition to existing read/member rights. Existing protection from accidental deletion can prevent the operation. These rights are controlled by AD; the service does not grant or bypass them.
4. Update IG's request transformation. `scripts/ig-request-guid.js` uses accountProfile.accountId. `scripts/ig-request-user-guid.js` also uses accountProfile.accountId for deletion, even though it uses the identity GUID for membership. `scripts/ig-request.js` uses accountProvId for DN requests. Include the relevant account context in IG's input mapping.
5. Enable REMOVE_ACCOUNT in the IG Generic REST target's supported change types and application routing, or sandbox-import the regenerated example. Review all post-import settings in [IG target imports](ig-import.md). Existing fulfillment ID/comment response mappings remain valid.

A new service binary is required; changing only the IG script is insufficient. The temporary discovery envelope (`context`) must be replaced by the operation-specific `account` payload. Example payloads: [GUID](../examples/delete-account-guid.json), [DN](../examples/delete-account-dn.json). Replace their synthetic object identifiers and target label before testing.

## Real AD / IG test sequence

Use separately created disposable accounts for each destructive success case. Test on the configured writable DC.

- Delete by GUID; confirm HTTP 200, ACCOUNT_DELETED and the exact intended account is absent in AD.
- Create another disposable account and delete by DN; confirm the same behavior.
- Move a disposable account without collecting again, then delete using its collected GUID. Confirm only that account was deleted and the comment identifies its current DN.
- Repeat the successful request. Expect 404 OBJECT_NOT_FOUND; absent/invisible objects are not reported as confirmed previous deletions.
- Try an account protected from deletion or a bind account without delete rights. Expect failure and confirm the account remains. An explicit LDAP insufficient-access response maps to 403; other AD rejection codes may map to 502.
- Target a group or computer. Expect 400 INVALID_USER_OBJECT and no deletion.
- Test a user with child objects if relevant. The service sends no tree-delete control, so ordinary non-leaf deletion should fail without deleting descendants.
- Collect and publish the affected application, then confirm IG changes the successful fulfillment from pending verification to Verified.
- Re-run a membership add/remove pair to check the existing behavior is unchanged.

A timeout may mean the delete completed but its response was lost. The service performs no automatic replay and stores no deletion ledger. Check AD and reconcile the IG task; a subsequent 404 is not evidence that the first call succeeded. DN-only input cannot distinguish a stale collected DN from a replacement account already present at that DN when the request begins; prefer GUID input.

## Local verification

Run `mvn clean verify`, `python scripts/generate_ig_imports.py --check` and `node scripts/verify_ig_imports.js`. Directory tests emulate AD's GUID-name delete in a generic in-memory LDAP fixture. They cover stable targeting during DN reuse, object types, missing accounts, missing GUID, denied/timed-out operations and no recursive deletion. They do not prove actual AD extension behavior, ACL policy or IG verification.

If validation fails, stop the service and restore the complete prior runtime and prior IG transformation/routing. Disable REMOVE_ACCOUNT routing when rolling back to 1.0.0; configuration/certificates remain external. Rolling back software does not restore deleted AD accounts.
