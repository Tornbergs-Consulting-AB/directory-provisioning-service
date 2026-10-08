# Upgrade to 0.3.0 — provisioning endpoint and GUID lookup

Stop the service. Back up the existing quarkus-app directory. Replace the entire directory with dist/quarkus-app from this release; preserve config, certificates and logs. Run from the same working directory as before.

Change both IG's connection-test Web Service Path and the fulfillment POST path to `/api/v1/provisioning`. There is no old membership endpoint alias. Existing DN payloads remain valid.

## GUID payload

Use the AD object's objectGUID in canonical dashed text, not an IG internal ID, identity ID or account logical ID. Supply exactly one of dn or guid per object. Mixed DN/GUID references are supported.

```json
{
  "changeItemId": "10003",
  "target": "DEMO",
  "requestType": "ADD_PERMISSION_TO_USER",
  "user": {"guid": "REPLACE-WITH-USER-OBJECTGUID"},
  "group": {"guid": "REPLACE-WITH-GROUP-OBJECTGUID"}
}
```

Searches are subtree searches under `dps.search-base` with the caller's AD credentials. Configure a base encompassing both users and groups, including their possible new locations. DN requests still read their supplied DNs directly. Search base is a lookup scope, not an authorization boundary. AD ACLs remain authoritative. No DN fallback is attempted when GUID lookup fails.

The response and fulfillment comment contain the resolved current userDN and groupDN. A missing/invisible GUID returns 404; invalid or conflicting identifiers return 400. Existing object-class validation and direct-membership semantics apply.

## Linux lab checks

Set SERVICE_URL and SERVICE_CA as before. Save the above payload with real GUIDs as examples/demo-guid-add.json. Then run:

```bash
curl --cacert "$SERVICE_CA" --user "$AD_BIND_DN" -i \
  "$SERVICE_URL/api/v1/provisioning"
curl --cacert "$SERVICE_CA" --user "$AD_BIND_DN" -i \
  -H 'Content-Type: application/json' -H 'X-Correlation-ID: iga-10003' \
  --data-binary @examples/demo-guid-add.json \
  "$SERVICE_URL/api/v1/provisioning"
```

curl prompts for the password. Repeat the add (ALREADY_MEMBER), change requestType to REMOVE_PERMISSION_ASSIGNMENT and repeat twice (CHANGED then NOT_MEMBER). Rename or move the LAB user within the search base, retain the same GUID and repeat; the response must contain the new DN. Repeat for the group. Inspect direct members on the configured DC and restore the original lab state.

Retrieve objectGUID with your LDAP browser, displaying it as a GUID. Raw LDIF `objectGUID::` is base64 binary and must not be pasted directly into the guid field. The service deliberately accepts canonical GUID text only.

The existing scripts/ig-request.js still sends DN references. Map actual account/group objectGUID attributes in IG before changing that script; do not substitute identity GUIDs.

## Validation

Local tests cover binary AD GUID encoding, GUID lookup following a rename, mixed identifiers, absent/out-of-scope GUIDs and membership operations. Real AD GUID, move and IG end-to-end tests remain to be performed in your lab. A rename/move concurrent with the LDAP write may fail; a new request resolves GUIDs again. No blind automatic write retry is introduced.
