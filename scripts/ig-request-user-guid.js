// Copyright 2026 Tornbergs Consulting AB
// SPDX-License-Identifier: Apache-2.0
// IG Generic REST request transformation using the identity AD objectGUID.
// Opt in only when this identity GUID identifies the intended AD account.
// Membership uses the identity GUID; deletion always uses the selected account.
// Include userProfile, accountProfile and permissionProfile in the fulfillment input payload.
// REMOVE_ACCOUNT always uses accountProfile.accountId, never userProfile.userId.
// User ID from Source -> userProfile.userId
// Permission ID from Source -> permissionProfile.permissionId
// These source IDs must contain AD objectGUID values, not IG internal IDs.
var item = JSON.parse(inputValue);
// Must match dps.target-name in the service configuration.
var dpsTarget = 'DEMO';

function canonicalGuid(value, label) {
    if (value === null || typeof value === 'undefined') {
        throw new Error('Missing ' + label + ': check fulfillment input profiles and account context.');
    }
    var text = String(value).replace(/^\s+|\s+$/g, '');
    // Accept canonical dashed text or the same text surrounded by braces.
    if (/^\{[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\}$/.test(text)) {
        text = text.substring(1, text.length - 1);
    }
    if (!/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(text)) {
        throw new Error(label + ' must contain a dashed AD objectGUID. Raw base64 or undashed source IDs require a verified conversion.');
    }
    return text.toLowerCase();
}

if (!/^[0-9]{1,20}$/.test(String(item.changeItemId))) {
    throw new Error('Missing or invalid changeItemId.');
}
// IG versions/configurations can supply profiles as objects or JSON strings.
function parseProfile(value, label) {
    if (typeof value === 'string') {
        try { value = JSON.parse(value); }
        catch (e) { throw new Error(label + ' contains invalid JSON.'); }
    }
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
        throw new Error('Missing or invalid ' + label + '.');
    }
    return value;
}
if (typeof item.changeRequestType !== 'string' || !item.changeRequestType) {
    throw new Error('Missing changeRequestType.');
}
var body = {
    changeItemId: String(item.changeItemId),
    target: dpsTarget,
    requestType: item.changeRequestType
};
switch (item.changeRequestType) {
    case 'REMOVE_ACCOUNT':
        // Always identify the selected ACCOUNT, including in the identity GUID variant.
        var deletionProfile = parseProfile(item.accountProfile, 'accountProfile');
        body.account = {guid: canonicalGuid(deletionProfile.accountId, 'Account ID from Source')};
        break;
    case 'ADD_PERMISSION_TO_USER':
    case 'REMOVE_ACCOUNT_PERMISSION':
    case 'REMOVE_PERMISSION_ASSIGNMENT':
        var membershipProfile = parseProfile(item.userProfile, 'userProfile');
        var permissionProfile = parseProfile(item.permissionProfile, 'permissionProfile');
        body.user = {guid: canonicalGuid(membershipProfile.userId, 'User ID from Source')};
        body.group = {guid: canonicalGuid(permissionProfile.permissionId, 'Permission ID from Source')};
        break;
    default:
        // Discovery only: the service rejects unimplemented operations.
        body.context = item;
        break;
}
outputValue = JSON.stringify({http_body: body, service_method: 'POST'});
