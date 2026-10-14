// Copyright 2026 Tornbergs Consulting AB
// SPDX-License-Identifier: Apache-2.0
// IG Generic REST Fulfiller: comment response transformation.
// Preserve the service comment, including change item, operation and resolved DNs.
var dpsCommentResponse = JSON.parse(inputValue);
if (String(dpsCommentResponse.response_code) !== '200') {
    // IG normally handles non-2xx responses before invoking this transformation.
    throw new Error('Provisioning returned HTTP ' + dpsCommentResponse.response_code + '.');
}
var dpsCommentMediaType = String(dpsCommentResponse.content_type || '').split(';')[0]
    .replace(/^\s+|\s+$/g, '').toLowerCase();
if (dpsCommentMediaType !== 'application/json') {
    throw new Error('Expected application/json provisioning response.');
}
var dpsCommentBody = JSON.parse(dpsCommentResponse.content);
if (!dpsCommentBody || typeof dpsCommentBody.comment !== 'string' || !dpsCommentBody.comment) {
    throw new Error('Provisioning response is missing comment.');
}
outputValue = dpsCommentBody.comment;
