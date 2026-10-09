// Copyright 2026 Marcus Tornberg
// SPDX-License-Identifier: Apache-2.0
// IG Generic REST request transformation for AD objectGUID identifiers.
// Include accountProfile and permissionProfile in the fulfillment input payload.
// Account ID from Source -> accountProfile.accountId
// Permission ID from Source -> permissionProfile.permissionId
// These source IDs must contain AD objectGUID values, not IG internal IDs.
var item = JSON.parse(inputValue);

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
var accountGuid = canonicalGuid(item.accountProfile && item.accountProfile.accountId, 'Account ID from Source');
var permissionGuid = canonicalGuid(item.permissionProfile && item.permissionProfile.permissionId, 'Permission ID from Source');

var body = {
    changeItemId: String(item.changeItemId),
    target: 'DEMO',
    requestType: item.changeRequestType,
    user: {guid: accountGuid},
    group: {guid: permissionGuid}
};
outputValue = JSON.stringify({http_body: body, service_method: 'POST'});
