// Generic REST request transformation. PROBE ONLY: keep your existing payload if preferred.
// Select the scenario in the configured endpoint URL, not in the body.
var item = JSON.parse(inputValue);
var body = {
    requestId: String(item.changeItemId),
    requestType: item.changeRequestType,
    target: 'ad-dev',
    user: { dn: item.accountProvId || '' },
    group: { dn: item.permProvId || '' }
};
// Missing account DN is intentionally visible. Do not silently use an identity DN.
outputValue = JSON.stringify({http_body: body, service_method: 'POST'});
