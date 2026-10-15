// Copyright 2026 Tornbergs Consulting AB
// SPDX-License-Identifier: Apache-2.0
// Development verification only: Node.js built-ins; no IG/AD connections.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '..');
let count = 0;
function check(action) { action(); count++; }
function execute(script, input) {
    const context = {inputValue: typeof input === 'string' ? input : JSON.stringify(input)};
    vm.runInNewContext(script, context, {timeout: 1000});
    return context.outputValue;
}
const account = '11111111-2222-3333-4444-555555555555';
const identity = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
const permission = '99999999-8888-7777-6666-555555555555';
const supported = ['ADD_PERMISSION_TO_USER', 'REMOVE_ACCOUNT_PERMISSION', 'REMOVE_PERMISSION_ASSIGNMENT', 'REMOVE_ACCOUNT'];
for (const variant of ['account-guid', 'user-guid']) {
    const document = JSON.parse(fs.readFileSync(path.join(root, 'examples/ig/directory-provisioning-' + variant + '.json'), 'utf8'));
    const collector = document.dataCollectors.collectors[0];
    const config = JSON.parse(collector.configuration);
    const scripts = Object.fromEntries(config['ecma-scripts'].map(s => [s.name, s.script]));
    check(() => {
        assert.equal(document.targetId, undefined);
        for (const name of ['collectorId', 'templateId', 'link', 'version', 'lastUpdateTime']) assert.equal(collector[name], undefined);
        assert.equal(collector.templateUniqueId, 'RestGenericFulfillmentTemplate');
        assert.equal(collector.templateVersion, '4.2.0');
        assert.deepEqual(Object.keys(config.supportedChangeItems).filter(k => config.supportedChangeItems[k]).sort(), [...supported].sort());
    });
    check(() => {
        const view = config.views[0];
        const response = Object.fromEntries(view['input-transforms'].map(m => [m['view-name'], m['script-name']]));
        assert.equal(response.fulfillmentId, 'fulfillmentId'); assert.equal(response.comment, 'comment');
        const inputs = JSON.parse(view['schema-map-filter']['fulfillment-parms'].find(p => p['app-name'] === 'content')['view-name']);
        assert(inputs.includes(variant === 'account-guid' ? 'accountProfile' : 'userProfile'));
        assert(inputs.includes('permissionProfile'));
    });
    check(() => {
        for (const p of config['service-parms']) {
            assert.equal(p['default-value'], undefined);
            if (p['data-type'] === 'password' || p['certificate-parm'] || ['username', 'jwt_public_key', 'clientID', 'username_bearer'].includes(p.name)) assert.equal(p.value, '');
        }
        assert.equal(config['service-parms'].find(p => p.name === 'service-url-basic').value, 'https://CHANGE-ME.invalid:8444');
        assert(!/lulea|borg\.local|127\.0\.0\.1|gromitid|IGASVCTEST|\$\$\$\$/i.test(JSON.stringify(document)));
    });
    for (const requestType of supported) for (const encoded of [false, true]) {
        const input = {changeItemId: '13955', changeRequestType: requestType,
            accountProfile: {accountId: account}, userProfile: {userId: identity}, permissionProfile: {permissionId: permission}};
        if (encoded) for (const name of ['accountProfile', 'userProfile', 'permissionProfile']) input[name] = JSON.stringify(input[name]);
        check(() => {
            const output = JSON.parse(execute(scripts.http_body, input));
            assert.equal(output.service_method, 'POST');
            if (requestType === 'REMOVE_ACCOUNT') {
                assert.equal(output.http_body.account.guid, account);
                assert.equal(output.http_body.user, undefined);
                assert.equal(output.http_body.group, undefined);
            } else {
                assert.equal(output.http_body.user.guid, variant === 'account-guid' ? account : identity);
                assert.equal(output.http_body.group.guid, permission);
            }
            assert.equal(output.http_body.target, 'CHANGE_ME');
            assert.equal(output.http_body.requestType, requestType);
        });
    }
    check(() => {
        const input = {changeItemId:'13955',changeRequestType:supported[0], userProfile:{userId:identity}, permissionProfile:{permissionId:permission}};
        if (variant === 'account-guid') assert.throws(() => execute(scripts.http_body, input));
        else assert.equal(JSON.parse(execute(scripts.http_body, input)).http_body.user.guid, identity);
    });
    for (const bad of [null, 'not-json', '[]', '{}']) check(() => {
        const input = {changeItemId:'13955',changeRequestType:supported[0],accountProfile:bad,userProfile:bad,permissionProfile:{permissionId:permission}};
        assert.throws(() => execute(scripts.http_body, input));
    });
    check(() => {
        const input = {changeItemId:'114', changeRequestType:'REMOVE_ACCOUNT', accountProfile:JSON.stringify({accountId:account})};
        assert.equal(JSON.parse(execute(scripts.http_body,input)).http_body.account.guid,account);
        delete input.accountProfile; input.userProfile={userId:identity};
        assert.throws(() => execute(scripts.http_body,input));
    });
    check(() => {
        const result=JSON.parse(execute(scripts.http_body,{changeItemId:'115',changeRequestType:'ADD_APPLICATION_TO_USER'}));
        assert.equal(result.http_body.context.changeItemId,'115');
        assert.equal(result.http_body.group,undefined);
    });
    check(() => assert.throws(() => execute(scripts.http_body, {changeItemId:'13955',accountProfile:{accountId:'not-a-guid'},userProfile:{userId:'not-a-guid'},permissionProfile:{permissionId:permission}})));
    check(() => assert.equal(JSON.parse(execute(scripts.http_headers, '13955'))['X-Correlation-ID'], 'iga-13955'));
    for (const outcome of ['CHANGED', 'UNCHANGED']) for (const responseCode of [200, '200']) {
        const comment = "Change Item '13955' Fulfilled. " + (outcome === 'CHANGED' ? 'Membership changed.' : 'No change required: already a direct member.');
        const response = {response_code:responseCode,content_type:'application/json;charset=UTF-8',content:JSON.stringify({fulfillmentId:'iga-13955',comment,outcome})};
        check(() => {
            assert.equal(execute(scripts.fulfillmentId,response),'iga-13955');
            assert.equal(execute(scripts.comment,response),comment);
        });
    }
    for (const name of ['fulfillmentId','comment']) check(() => {
        assert.throws(() => execute(scripts[name],{response_code:503,content_type:'application/json',content:'{}'}));
        assert.throws(() => execute(scripts[name],{response_code:200,content_type:'application/json',content:'{}'}));
    });
}
const dnScript=fs.readFileSync(path.join(root,'scripts/ig-request.js'),'utf8');
check(() => {
    const output=JSON.parse(execute(dnScript,{changeItemId:'114',changeRequestType:'REMOVE_ACCOUNT',accountProvId:'CN=Disposable,DC=example,DC=com'}));
    assert.equal(output.http_body.account.dn,'CN=Disposable,DC=example,DC=com');
    assert.equal(output.http_body.user,undefined);assert.equal(output.http_body.group,undefined);
});
check(() => assert.throws(() => execute(dnScript,{changeItemId:'114',changeRequestType:'REMOVE_ACCOUNT',userProfile:{userId:identity}})));
check(() => {
    const output=JSON.parse(execute(dnScript,{changeItemId:'114',changeRequestType:'ADD_APPLICATION_TO_USER'}));
    assert.equal(output.http_body.context.changeRequestType,'ADD_APPLICATION_TO_USER');
});
console.log('PASS: ' + count + ' import configuration and embedded transformation cases; no IG/AD calls');
