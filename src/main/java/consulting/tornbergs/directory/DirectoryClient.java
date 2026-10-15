// Copyright 2026 Tornbergs Consulting AB
// SPDX-License-Identifier: Apache-2.0
package consulting.tornbergs.directory;

import com.unboundid.ldap.sdk.*;

/** LDAP operations use one caller-bound connection. Generic LDAP tests; AD tested separately. */
public final class DirectoryClient {
    public record Outcome(boolean changed, String code, String userDN, String groupDN) {}
    public String resolve(LDAPInterface ldap, Api.ObjectRef ref, String searchBase) throws LDAPException {
        if (ref.guid()==null) return ref.dn();
        SearchRequest search=new SearchRequest(searchBase,SearchScope.SUB,
            Filter.createEqualityFilter("objectGUID",AdGuid.bytes(ref.guid())),"1.1");
        search.setSizeLimit(2);
        SearchResult result=ldap.search(search);
        if (result.getEntryCount()==0)
            throw new Api.Failure(404,"OBJECT_NOT_FOUND","GUID was not found within the configured search base or is not visible to the bind account.");
        if (result.getEntryCount()!=1)
            throw new Api.Failure(409,"AMBIGUOUS_IDENTIFIER","GUID lookup returned multiple objects.");
        return result.getSearchEntries().get(0).getDN();
    }
    /** Delete one AD user by its immutable GUID, even if its DN changes after inspection.
     * AD alternative DN syntax is required; no tree-delete or other delete controls are sent.
     */
    public Outcome deleteAccount(LDAPInterface ldap, Api.ObjectRef ref, String searchBase) throws LDAPException {
        String dn=resolve(ldap,ref,searchBase);
        SearchResultEntry entry=ldap.getEntry(dn,"objectClass","objectGUID");
        if (entry==null) throw new Api.Failure(404,"OBJECT_NOT_FOUND","Account was not found or is not visible to the bind account.");
        if (!entry.hasAttributeValue("objectClass","user") || entry.hasAttributeValue("objectClass","computer"))
            throw new Api.Failure(400,"INVALID_USER_OBJECT","Target account must be an AD user, not a computer or group.");
        byte[] guid=entry.getAttributeValueBytes("objectGUID");
        if (guid==null || guid.length!=16)
            throw new Api.Failure(409,"ACCOUNT_GUID_UNAVAILABLE","Account objectGUID must be readable before deletion.");
        if (ref.guid()!=null && !java.util.Arrays.equals(guid,AdGuid.bytes(ref.guid())))
            throw new Api.Failure(409,"ACCOUNT_CHANGED","Account changed during GUID resolution; no deletion attempted.");
        // Never delete by a previously read DN: it could now identify a different object.
        String stableName="<GUID="+java.util.HexFormat.of().formatHex(guid)+">";
        ldap.delete(new DeleteRequest(stableName));
        return new Outcome(true,"ACCOUNT_DELETED",entry.getDN(),null);
    }
    private boolean isDirectMember(LDAPInterface ldap, String groupDN, String userDN) throws LDAPException {
        try {
            return ldap.compare(groupDN,"member",userDN).compareMatched();
        } catch (LDAPException e) {
            // A compare against an existing, empty AD group can return code 16.
            // Catch only absence of member; never turn ACL, missing-object or transport errors into success.
            if (e.getResultCode()==ResultCode.NO_SUCH_ATTRIBUTE) return false;
            throw e;
        }
    }
    public Outcome update(LDAPInterface ldap, String userDN, String groupDN, boolean add) throws LDAPException {
        SearchResultEntry user=ldap.getEntry(userDN, "objectClass");
        SearchResultEntry group=ldap.getEntry(groupDN, "objectClass");
        if (user==null || group==null) throw new Api.Failure(404,"OBJECT_NOT_FOUND","User or group was not found or is not visible to the bind account.");
        if (!user.hasAttributeValue("objectClass","user") || user.hasAttributeValue("objectClass","computer"))
            throw new Api.Failure(400,"INVALID_USER_OBJECT","Target user must be an AD user, not a computer.");
        if (!group.hasAttributeValue("objectClass","group"))
            throw new Api.Failure(400,"INVALID_GROUP_OBJECT","Target permission must be an AD group.");
        String resolvedUser=user.getDN(), resolvedGroup=group.getDN();
        boolean member=isDirectMember(ldap,resolvedGroup,resolvedUser);
        if (member==add) return new Outcome(false,add?"ALREADY_MEMBER":"NOT_MEMBER",resolvedUser,resolvedGroup);
        try {
            ldap.modify(resolvedGroup,new Modification(add?ModificationType.ADD:ModificationType.DELETE,"member",resolvedUser));
        } catch (LDAPException e) {
            // AD can report WILL_NOT_PERFORM for an absent membership. Only suppress a
            // membership-related failure after confirming the requested final state.
            boolean possibleRace=e.getResultCode()==ResultCode.ATTRIBUTE_OR_VALUE_EXISTS
                || e.getResultCode()==ResultCode.NO_SUCH_ATTRIBUTE || e.getResultCode()==ResultCode.UNWILLING_TO_PERFORM;
            if (possibleRace && isDirectMember(ldap,resolvedGroup,resolvedUser)==add)
                return new Outcome(false,add?"ALREADY_MEMBER":"NOT_MEMBER",resolvedUser,resolvedGroup);
            throw e;
        }
        return new Outcome(true,"MEMBERSHIP_CHANGED",resolvedUser,resolvedGroup);
    }
}
