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
// CUSTOMER SETTINGS: configure before enabling account creation.
var dpsCreationContainer = ''; // e.g. OU=Users,OU=Tornbergs,DC=demo,DC=tornbergs,DC=consulting
var dpsCreationUpnSuffix = ''; // e.g. demo.tornbergs.consulting
// This policy is used only for creation and initial membership additions.
// Change it once here for customer-specific account types (e.g. -EMP / -ADM).
function accountReference(item) {
    var p = item.userProfile;
    if (typeof p === 'string') {
        try { p = JSON.parse(p); } catch (e) { throw new Error('userProfile contains invalid JSON.'); }
    }
    if (!p || typeof p.workforceId !== 'string' || !p.workforceId.replace(/^\s+|\s+$/g, ''))
        throw new Error('Missing userProfile.workforceId for account reference.');
    var reference = p.workforceId + '-EMP';
    if (reference.length > 16 || /[\x00-\x1f\x7f]/.test(reference))
        throw new Error('employeeID reference must contain 1 to 16 characters without control characters.');
    return reference;
}
function selectedAccount(item, allowCreationReference) {
    if (item.accountProfile !== null && typeof item.accountProfile !== 'undefined' && item.accountProfile !== '') {
        var p = parseProfile(item.accountProfile, 'accountProfile');
        if (p.accountId !== null && typeof p.accountId !== 'undefined' && p.accountId !== '')
            return {guid: canonicalGuid(p.accountId, 'Account ID from Source')};
    }
    if (!allowCreationReference) throw new Error('Missing selected account DN/GUID; references are forbidden for removal.');
    // No collected account context yet. Do not guess a DN or an account name.
    return {reference: accountReference(item)};
}
function creationPayload(item) {
    var p = item.userProfile;
    if (typeof p === 'string') {
        try { p = JSON.parse(p); } catch (e) { throw new Error('userProfile contains invalid JSON.'); }
    }
    if (!p || typeof p !== 'object' || Array.isArray(p)) throw new Error('Missing userProfile for creation.');
    function text(value, label) {
        if (typeof value !== 'string' || !value.replace(/^\s+|\s+$/g, '')) throw new Error('Configure nonempty ' + label + '.');
        return value;
    }
    var first = text(p.firstName, 'userProfile.firstName');
    var last = text(p.lastName, 'userProfile.lastName');
    var reference = accountReference(item);
    // Example naming only: first initial + surname; Swedish letters folded; edit as needed.
    var sam = (first.substring(0, 1) + last).toLowerCase()
        .replace(/[åä]/g, 'a').replace(/ö/g, 'o').replace(/[^a-z0-9._-]/g, '').substring(0, 20);
    if (!sam) throw new Error('Naming policy produced an empty account name.');
    var attrs = {sAMAccountName: sam,
        userPrincipalName: sam + '@' + text(dpsCreationUpnSuffix, 'dpsCreationUpnSuffix'),
        givenName: first, sn: last, displayName: first + ' ' + last};
    if (typeof p.jobTitle === 'string' && p.jobTitle.replace(/^\s+|\s+$/g, '')) attrs.title = p.jobTitle;
    if (typeof p.department === 'string' && p.department.replace(/^\s+|\s+$/g, '')) attrs.department = p.department;
    return {reference: reference, containerDN: text(dpsCreationContainer, 'dpsCreationContainer'),
        cn: first + ' ' + last, attributes: attrs};
}

// Profile updates target the collected AD identity GUID/DN, never a creation reference.
var dpsAttributeMap = {
    'ext_otherTelephone': 'otherTelephone',
    'ext_department-Ext': 'department'
};
function profileUpdateUser(item) {
    var p = item.userProfile;
    if (typeof p === 'string') {
        try { p = JSON.parse(p); } catch (e) { throw new Error('userProfile contains invalid JSON.'); }
    }
    if (!p || typeof p !== 'object' || Array.isArray(p)) throw new Error('Missing userProfile.');
    function present(v) { return v !== null && typeof v !== 'undefined' && !(typeof v === 'string' && !v.replace(/^\s+|\s+$/g, '')); }
    if (present(p.ext_aDGUID)) {
        if (typeof p.ext_aDGUID !== 'string') throw new Error('ext_aDGUID must be a canonical GUID string.');
        var guid = p.ext_aDGUID.replace(/^\s+|\s+$/g, '');
        if (/^\{[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\}$/.test(guid)) guid = guid.substring(1, guid.length - 1);
        if (!/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(guid)) throw new Error('Invalid userProfile.ext_aDGUID.');
        return {guid: guid.toLowerCase()};
    }
    if (present(p.ext_aDDN)) {
        if (typeof p.ext_aDDN !== 'string' || /[\x00-\x1f\x7f]/.test(p.ext_aDDN)) throw new Error('Invalid userProfile.ext_aDDN.');
        return {dn: p.ext_aDDN.replace(/^\s+|\s+$/g, '')};
    }
    throw new Error('Profile updates require userProfile.ext_aDGUID or ext_aDDN.');
}
function profileModifications(item) {
    var changes = item.userAttributeModifications;
    if (typeof changes === 'string') {
        try { changes = JSON.parse(changes); } catch (e) { throw new Error('Invalid userAttributeModifications JSON.'); }
    }
    if (!Array.isArray(changes) || !changes.length) throw new Error('Missing userAttributeModifications.');
    var result = [];
    function contains(values, value) {
        for (var i = 0; i < values.length; i++) if (values[i] === value) return true;
        return false;
    }
    for (var i = 0; i < changes.length; i++) {
        var c = changes[i];
        if (!c || typeof c.attributeName !== 'string' || !Object.prototype.hasOwnProperty.call(dpsAttributeMap, c.attributeName)) throw new Error('Unmapped IG attribute.');
        var name = dpsAttributeMap[c.attributeName];
        if (typeof name !== 'string' || !/^[A-Za-z][A-Za-z0-9-]*$/.test(name)) throw new Error('Invalid LDAP attribute mapping.');
        var action;
        if (c.changeType === 1 || c.changeType === '1') action = 'add';
        else if (c.changeType === 2 || c.changeType === '2') action = 'remove';
        else throw new Error('Unsupported attribute changeType.');
        var value = c.attributeValue;
        if (typeof value !== 'string' || !value.replace(/^\s+|\s+$/g, '') || /[\x00-\x1f\x7f]/.test(value)) throw new Error('Expected nonempty text attributeValue.');
        var m = null;
        for (var j = 0; j < result.length; j++) if (result[j].attribute.toLowerCase() === name.toLowerCase()) { m = result[j]; break; }
        if (m === null) { m = {attribute: name, add: [], remove: []}; result.push(m); }
        if (contains(m[action === 'add' ? 'remove' : 'add'], value)) throw new Error('Value is both added and removed.');
        if (!contains(m[action], value)) m[action].push(value);
    }
    return result;
}

switch (item.changeRequestType) {
    case 'ADD_APPLICATION_TO_USER':
        body.creation = creationPayload(item);
        break;
    case 'REMOVE_ACCOUNT':
    case 'REMOVE_APPLICATION_FROM_USER':
        // Always identify the selected ACCOUNT, including in the identity GUID variant.
        body.account = selectedAccount(item, false);
        break;
    case 'ADD_PERMISSION_TO_USER':
    case 'REMOVE_ACCOUNT_PERMISSION':
    case 'REMOVE_PERMISSION_ASSIGNMENT':
        var membershipProfile = parseProfile(item.userProfile, 'userProfile');
        var permissionProfile = parseProfile(item.permissionProfile, 'permissionProfile');
        body.user = {guid: canonicalGuid(membershipProfile.userId, 'User ID from Source')};
        body.group = {guid: canonicalGuid(permissionProfile.permissionId, 'Permission ID from Source')};
        break;
    case 'MODIFY_USER_PROFILE':
        body.user = profileUpdateUser(item);
        body.modifications = profileModifications(item);
        break;
    default:
        // Discovery only: the service rejects unimplemented operations.
        body.context = item;
        break;
}
outputValue = JSON.stringify({http_body: body, service_method: 'POST'});
