// Copyright 2026 Tornbergs Consulting AB
// SPDX-License-Identifier: Apache-2.0
// Input mapping: changeItemId. The inputValue is the scalar change item ID.
var itemId = String(inputValue).trim();
if (!/^\d+$/.test(itemId)) throw new Error('Missing or invalid changeItemId.');
outputValue = JSON.stringify({'Accept-Language':'en','X-Correlation-ID':'iga-' + itemId});
