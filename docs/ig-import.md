# Importable IG fulfillment targets

These examples configure the Generic REST Fulfiller for the service's implemented direct-membership operations and REMOVE_ACCOUNT deletion. They embed the request, header, fulfillment ID and comment scripts, plus their input/output mappings. They do not add account creation or other unimplemented operations. REMOVE_ACCOUNT requires the 1.1.0-SNAPSHOT service or later; do not route it to 1.0.0.

## Choose a variant

| Import file | User/account objectGUID source | When to use it |
|---|---|---|
| [directory-provisioning-account-guid.json](../examples/ig/directory-provisioning-account-guid.json) | `accountProfile.accountId` | Preferred: use the account IG selected for fulfillment, including multi-account identities |
| [directory-provisioning-user-guid.json](../examples/ig/directory-provisioning-user-guid.json) | `userProfile.userId` | Explicit alternative when the identity source GUID identifies the exact AD account to modify |

Membership operations obtain the group's objectGUID from `permissionProfile.permissionId`. Source values must be canonical dashed AD objectGUIDs, not IG internal IDs or raw base64. Profile inputs can be JSON objects or JSON-encoded strings. The account variant never falls back to an identity GUID. For membership, the identity variant does not honor a separately selected account: do not use it for an operation intended for a different account.

**Both variants use `accountProfile.accountId` for REMOVE_ACCOUNT**, including the identity GUID variant. This avoids deleting a primary account merely because its identity requested removal of another account. Include accountProfile in the input context for both variants. Deletion needs no permissionProfile.

The corresponding standalone request scripts are [ig-request-guid.js](../scripts/ig-request-guid.js) and [ig-request-user-guid.js](../scripts/ig-request-user-guid.js).

## Compatibility and import validation

The structural source is a user-supplied configured target export based on `RestGenericFulfillmentTemplate`, template/configuration version `4.2.0`. That number is the IG template version, not this product's release version. Source-instance target/collector/template numeric IDs, API links and runtime metadata have been removed; the template's unique ID/version are retained. Example service identifiers differ between variants.

**Local structure and script tests pass; importing these sanitized examples into IG has not yet been validated.** Test with the same IG/template version in a sandbox, importing as a new target. Confirm IG resolves the retained template identity, assigns new object IDs and preserves script bindings. Do not overwrite a working target. If IG requires a field that sanitization removed, retain the import error for diagnosis rather than copying numeric IDs from another environment.

Use IG's fulfillment-target import action under Fulfillment > Configuration > Fulfillment Targets (the exact label can vary by version). Import one selected JSON file, then open the resulting target and complete the checklist below before assigning it to an application. Application routing, global Fulfillment Context Attributes, collectors and service-host configuration are not contained in this target export.

## Post-import checklist

1. Confirm the new target name and template. The examples are named `Directory Provisioning Service - account-guid` and `Directory Provisioning Service - user-guid`.
2. Keep **Basic authentication**. Replace `https://CHANGE-ME.invalid:8444` with the service's actual HTTPS base URL, without the provisioning path. Use the certificate's DNS name. `.invalid` is a deliberate non-working placeholder.
3. Keep `/api/v1/provisioning` for both the connection-test path and the fulfillment path. Keep JSON content/accept types. The example read timeout is 60 seconds; review it against configured LDAP timeouts.
4. Enter the permitted AD bind DN and password **inside IG**, and configure the connector's HTTPS trust for your service certificate/CA. The exported credential fields and environment-specific certificate values are empty. No AD credentials are stored in the product examples.
5. Open the request-body transformation (`http_body`, mapped to Content). Change `var dpsTarget = 'CHANGE_ME';` to the exact `dps.target-name` from the service's `config/application.properties`. This is a script setting, not a new IG connection parameter. There is no assumed automatic substitution from the application name.
6. Confirm that the content input mapping includes `changeItemId`, `changeRequestType`, the chosen `accountProfile` or `userProfile`, and `permissionProfile`. In IG's Fulfillment Context Attributes, expose the required source ID fields. Importing this target does not change those global attribute selections.
7. Check the response mappings: **Fulfillment ID → `fulfillmentId` script → body.fulfillmentId**; **Comment → `comment` script → body.comment**. The swapped scripts in the supplied export have been corrected. Both mappings consume the connector envelope (`response_code`, `content_type`, `content`).
8. Confirm only `ADD_PERMISSION_TO_USER`, `REMOVE_PERMISSION_ASSIGNMENT`, `REMOVE_ACCOUNT_PERMISSION` and `REMOVE_ACCOUNT` are enabled. Account creation and other operations remain disabled.
9. Run Test Connection. It must return success after HTTPS validation and AD TLS/bind. Then route the three supported change types for a sandbox application to this target.
10. Test one add, repeated add, remove and repeated remove on disposable objects. Confirm direct membership, change-item correlation, the correct fulfillment ID/comment, and final verification after collection/publication. For the account variant, confirm the selected account is the one changed.

Keep any later exports containing saved credentials or customer-specific configuration outside the public repository, even if passwords appear masked or encrypted.

## Keep the imports aligned with the scripts

The account import also supplies the sanitized structural seed. Regenerate both variants after editing the standalone scripts:

```text
python scripts/generate_ig_imports.py
python scripts/generate_ig_imports.py --check
node scripts/verify_ig_imports.js
```

Python 3 and Node.js are development tools for generation/verification; Node.js is not required on service hosts. The checks execute synthetic transformations without contacting IG or AD.

To generate imports with a chosen logical target label, use `python scripts/generate_ig_imports.py --target AD`. The generator accepts no passwords and still leaves connection credentials/trust settings empty. `--check --target AD` checks that customized output. Keep public examples on the default `CHANGE_ME` label, and do not commit customer credentials or certificate material.

The generator refreshes scripts and profile mappings, clears connection values except an explicit list of safe settings, preserves the exported mapping structure and restricts enabled operations. It does not discover or upgrade IG templates, set global context attributes, or prove import compatibility.
