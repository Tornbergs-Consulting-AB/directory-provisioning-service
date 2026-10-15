// Copyright 2026 Tornbergs Consulting AB
// SPDX-License-Identifier: Apache-2.0
// DN request transformation: accountProvId must identify the intended AD account.
var item = JSON.parse(inputValue);
var dpsTarget = 'DEMO'; // Must match dps.target-name.
if (!/^[0-9]{1,20}$/.test(String(item.changeItemId))) {
    throw new Error('Missing or invalid changeItemId.');
}
if (typeof item.changeRequestType !== 'string' || !item.changeRequestType) {
    throw new Error('Missing changeRequestType.');
}
function requiredDn(value, label) {
    if (typeof value !== 'string' || !value.replace(/^\s+|\s+$/g, '')) {
        throw new Error('Missing or invalid ' + label + '.');
    }
    return value;
}
var body = {changeItemId: String(item.changeItemId), target: dpsTarget, requestType: item.changeRequestType};
switch (item.changeRequestType) {
    case 'REMOVE_ACCOUNT':
        body.account = {dn: requiredDn(item.accountProvId, 'accountProvId')};
        break;
    case 'ADD_PERMISSION_TO_USER':
    case 'REMOVE_ACCOUNT_PERMISSION':
    case 'REMOVE_PERMISSION_ASSIGNMENT':
        body.user = {dn: requiredDn(item.accountProvId, 'accountProvId')};
        body.group = {dn: requiredDn(item.permProvId, 'permProvId')};
        break;
    default:
        // Discovery only: the service rejects unimplemented operations.
        body.context = item;
        break;
}
outputValue = JSON.stringify({http_body: body, service_method: 'POST'});
