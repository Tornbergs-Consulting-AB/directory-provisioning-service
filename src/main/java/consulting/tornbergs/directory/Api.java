// Copyright 2026 Tornbergs Consulting AB
// SPDX-License-Identifier: Apache-2.0
package consulting.tornbergs.directory;

public final class Api {
    private Api() {}
    public record ObjectRef(String dn, String guid) {}
    public record MembershipRequest(String changeItemId, String requestId, String target,
                                    String requestType, ObjectRef user, ObjectRef group) {}
    public record Reply(String changeItemId, String requestId, String fulfillmentId,
                        String outcome, String code, String comment, String userDN, String groupDN) {}
    public static class Failure extends RuntimeException {
        public final int status; public final String code;
        public Failure(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    }
}
