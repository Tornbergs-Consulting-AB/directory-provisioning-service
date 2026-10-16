// Copyright 2026 Tornbergs Consulting AB
// SPDX-License-Identifier: Apache-2.0
// OPTIONAL IG application changeset modification script, not a REST transformation.
// Review existing ordering first. Business role must already generate account creation.
// Published IG changesetUtils API; validate scheduling behavior in your IG version.
try {
    changesetUtils.updateChangesetOrderByChangeRequestType(0, 'ADD_APPLICATION_TO_USER');
    changesetUtils.updateChangesetOrderByChangeRequestType(10, 'ADD_PERMISSION_TO_USER');
    changesetUtils.updateChangesetOrderByChangeRequestType(20, 'REMOVE_ACCOUNT_PERMISSION');
    changesetUtils.updateChangesetOrderByChangeRequestType(20, 'REMOVE_PERMISSION_ASSIGNMENT');
    changesetUtils.updateChangesetOrderByChangeRequestType(30, 'REMOVE_ACCOUNT');
    changesetUtils.updateChangesetOrderByChangeRequestType(30, 'REMOVE_APPLICATION_FROM_USER');
} catch (e) {
    changesetUtils.rollback();
    throw e;
}
