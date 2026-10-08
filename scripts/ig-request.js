// Request transformation: account context must identify the intended AD account.
var item = JSON.parse(inputValue);
if (!item.accountProvId) {
    throw new Error('Missing accountProvId: configure account selection/context before fulfillment.');
}
if (!item.permProvId) {
    throw new Error('Missing permProvId.');
}
var body = {
    changeItemId: String(item.changeItemId),
    target: 'DEMO',
    requestType: item.changeRequestType,
    user: {dn: item.accountProvId},
    group: {dn: item.permProvId}
};
outputValue = JSON.stringify({http_body: body, service_method: 'POST'});
