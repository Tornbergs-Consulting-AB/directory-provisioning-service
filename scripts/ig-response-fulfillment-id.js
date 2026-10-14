// Copyright 2026 Tornbergs Consulting AB
// SPDX-License-Identifier: Apache-2.0
// IG Generic REST Fulfiller: fulfillment ID response transformation.
// inputValue is the connector response envelope, not the service body alone.
var dpsIdResponse = JSON.parse(inputValue);
if (String(dpsIdResponse.response_code) !== '200') {
    // IG normally handles non-2xx responses before invoking this transformation.
    throw new Error('Provisioning returned HTTP ' + dpsIdResponse.response_code + '.');
}
var dpsIdMediaType = String(dpsIdResponse.content_type || '').split(';')[0]
    .replace(/^\s+|\s+$/g, '').toLowerCase();
if (dpsIdMediaType !== 'application/json') {
    throw new Error('Expected application/json provisioning response.');
}
var dpsIdBody = JSON.parse(dpsIdResponse.content);
if (!dpsIdBody || typeof dpsIdBody.fulfillmentId !== 'string' || !dpsIdBody.fulfillmentId) {
    throw new Error('Provisioning response is missing fulfillmentId.');
}
outputValue = dpsIdBody.fulfillmentId;
