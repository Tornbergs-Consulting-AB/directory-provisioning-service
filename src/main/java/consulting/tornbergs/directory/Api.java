// Copyright 2026 Tornbergs Consulting AB
// SPDX-License-Identifier: Apache-2.0
package consulting.tornbergs.directory;

public final class Api {
    private Api() {}
    public record ObjectRef(String dn, String guid, String reference) {
        public ObjectRef(String dn,String guid) { this(dn,guid,null); }
        public String identifierType() { return guid!=null?"GUID":reference!=null?"REFERENCE":"DN"; }
    }
    public record Creation(String reference, String containerDN, String cn, java.util.Map<String,com.fasterxml.jackson.databind.JsonNode> attributes) {}
    public record ProvisioningRequest(String changeItemId, String requestId, String target,
                                      String requestType, ObjectRef user, ObjectRef group, ObjectRef account, Creation creation) {
        public ProvisioningRequest(String changeItemId, String requestId, String target,
                                   String requestType, ObjectRef user, ObjectRef group, ObjectRef account) {
            this(changeItemId,requestId,target,requestType,user,group,account,null);
        }
        public ProvisioningRequest(String changeItemId, String requestId, String target,
                                   String requestType, ObjectRef user, ObjectRef group) {
            this(changeItemId, requestId, target, requestType, user, group, null, null);
        }
    }
    public record Reply(String changeItemId, String requestId, String fulfillmentId,
                        String outcome, String code, String comment, String userDN, String groupDN,
                        String accountName, String userPrincipalName) {
        public Reply(String changeItemId,String requestId,String fulfillmentId,String outcome,String code,String comment,String userDN,String groupDN) {
            this(changeItemId,requestId,fulfillmentId,outcome,code,comment,userDN,groupDN,null,null);
        }
    }
    public static class Failure extends RuntimeException {
        public final int status; public final String code;
        public Failure(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    }
}
