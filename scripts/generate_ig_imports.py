#!/usr/bin/env python3
# Copyright 2026 Tornbergs Consulting AB
# SPDX-License-Identifier: Apache-2.0
"""Refresh sanitized IG target examples from the structural seed and standalone scripts.

No credentials are accepted. Configure connection credentials/certificates in IG after import.
Actual import behavior must be validated against the target IG version.
"""
import argparse
import copy
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
EXAMPLES = ROOT / 'examples/ig'
SUPPORTED = {'REMOVE_PERMISSION_ASSIGNMENT', 'ADD_PERMISSION_TO_USER', 'REMOVE_ACCOUNT_PERMISSION', 'REMOVE_ACCOUNT', 'REMOVE_APPLICATION_FROM_USER', 'ADD_APPLICATION_TO_USER'}
# Only these non-secret, environment-independent parameter values are retained.
PARAMETERS = {
    'use_bridge_connector': 0,
    'data_source_conn_id': 0,
    'authentication_method': 'basic_auth',
    'service-url-basic': 'https://CHANGE-ME.invalid:8444',
    'service-path': '/api/v1/provisioning',
    'access_token_header': 'Bearer',
    'content_type': 'application/json',
    'read-timeout-secs': 60,
    'chunk-request-ttl': 30,
}
VARIANTS = {
    'account-guid': ('ig-request-guid.js', 'accountProfile'),
    'user-guid': ('ig-request-user-guid.js', 'userProfile'),
}


def build(seed, variant, target):
    result = copy.deepcopy(seed)
    result.pop('targetId', None)
    label = 'Directory Provisioning Service - ' + variant
    result.update(name=label, displayName=label, description=(
        'AD provisioning example using ' + variant + '. Configure URL, AD bind credentials, '
        'HTTPS trust and dpsTarget after import. https://github.com/Tornbergs-Consulting-AB/directory-provisioning-service'))
    collectors = result['dataCollectors']['collectors']
    if len(collectors) != 1:
        raise ValueError('Expected exactly one fulfillment collector')
    collector = collectors[0]
    # Keep template identity/version; remove source-instance IDs, API links and runtime state.
    for field in list(collector):
        if field not in {'name', 'collectorType', 'templateUniqueId', 'templateVersion', 'configuration'}:
            del collector[field]
    collector['name'] = label
    config = json.loads(collector['configuration'])
    config['data-source-name'] = label
    config['collector-name'] = label
    config['service-identifier'] = 'RestGenericFulfillmentTemplate-dps-' + variant + '-example'
    for param in config['service-parms']:
        param['value'] = PARAMETERS.get(param['name'], '')
        param.pop('default-value', None)
        # A default must not restore source credentials/certificates when the value is cleared.
        param.pop('certificateLoading', None)
    script_file, profile = VARIANTS[variant]
    mapping = {
        'http_headers': 'ig-headers.js',
        'http_body': script_file,
        'fulfillmentId': 'ig-response-fulfillment-id.js',
        'comment': 'ig-response-comment.js',
    }
    scripts = config['ecma-scripts']
    if {s['name'] for s in scripts} != set(mapping):
        raise ValueError('Unexpected script bindings')
    for entry in scripts:
        script = (ROOT / 'scripts' / mapping[entry['name']]).read_text()
        if entry['name'] == 'http_body':
            if script.count("var dpsTarget = 'DEMO';") != 1:
                raise ValueError('Expected one configurable dpsTarget declaration')
            script = script.replace("var dpsTarget = 'DEMO';", "var dpsTarget = '" + target + "';")
        entry['script'] = script
    config['supportedChangeItems'] = {key: key in SUPPORTED for key in config['supportedChangeItems']}
    for view in config['views']:
        for param in view['schema-map-filter']['fulfillment-parms']:
            if param['app-name'] == 'content':
                param['view-name'] = json.dumps([
                    'changeItemId', 'changeRequestType', 'fulfillmentInstructions', 'userName',
                    'appName', 'reason', 'requesterName', 'permName', 'accountProfile',
                    'userProfile', 'permissionProfile'
                ], separators=(',', ':'))
    result['dataCollectors']['arraySize'] = 1
    collector['configuration'] = json.dumps(config, ensure_ascii=False, separators=(',', ':'))
    return json.dumps(result, indent=2, ensure_ascii=False) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', default='CHANGE_ME', help='Exact dps.target-name; default requires configuration')
    parser.add_argument('--check', action='store_true', help='Fail if checked-in examples differ from generated output')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9._-]{1,64}', args.target):
        parser.error('target must contain 1-64 letters, digits, periods, underscores or hyphens')
    seed = json.loads((EXAMPLES / 'directory-provisioning-account-guid.json').read_text())
    # Render both before writing, so overwriting the structural seed cannot affect the second variant.
    rendered = {variant: build(seed, variant, args.target) for variant in VARIANTS}
    for variant, content in rendered.items():
        path = EXAMPLES / ('directory-provisioning-' + variant + '.json')
        if args.check:
            if path.read_text() != content:
                raise SystemExit('Out of date: ' + str(path.relative_to(ROOT)))
        else:
            path.write_text(content)
    print('PASS: both IG examples match standalone scripts' if args.check else 'Generated both sanitized IG examples')


if __name__ == '__main__':
    main()
