// Copyright 2026 Tornbergs Consulting AB
// SPDX-License-Identifier: Apache-2.0
package consulting.tornbergs.directory;

import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.matchingrules.CaseIgnoreStringMatchingRule;
import com.unboundid.ldap.sdk.*;
import java.util.*;

/** AD user text deltas. One atomic, strict DELETE/ADD Modify; never permissive or replayed. */
final class AttributeUpdates {
    private static final Set<String> FORBIDDEN=Set.of(
        "cn","name","distinguishedname","objectclass","objectcategory","objectguid","objectsid",
        "employeeid","useraccountcontrol","unicodepwd","userpassword","password","pwdlastset",
        "member","memberof","manager","directreports","primarygroupid","ntsecuritydescriptor",
        "sidhistory","samaccounttype","accountexpires","samaccountname","userprincipalname",
        "serviceprincipalname","msds-additionalsamaccountname","msds-allowedtoactonbehalfofotheridentity",
        "msds-allowedtodelegateto","msds-keycredentiallink","altsecurityidentities","usnchanged","usncreated");
    private static final Set<String> TEXT_SYNTAX=Set.of("2.5.5.12","2.5.5.4");
    private record Definition(String name,boolean single) {}
    private AttributeUpdates() {}
    static Set<String> allowedNames(String configured) {
        if(configured==null || configured.isBlank())throw new IllegalArgumentException("Empty update allowlist");
        Set<String> result=new HashSet<>();
        for(String value:configured.split(",",-1)) {
            String name=value.trim().toLowerCase(Locale.ROOT);
            if(!name.matches("[a-z][a-z0-9-]*") || FORBIDDEN.contains(name))throw new IllegalArgumentException("Invalid update allowlist");
            result.add(name);
        }
        return result;
    }
    static void validate(List<Api.AttributeChange> changes) {
        if(changes==null || changes.isEmpty() || changes.size()>32)throw bad("Supply 1 to 32 attribute modifications.");
        Set<String> names=new HashSet<>();int total=0;
        for(var c:changes) {
            if(c==null || c.attribute()==null || !c.attribute().matches("[A-Za-z][A-Za-z0-9-]*") || c.attribute().length()>128)
                throw bad("Invalid LDAP attribute name; options and numeric OIDs are not allowed.");
            String name=c.attribute().toLowerCase(Locale.ROOT);
            if(FORBIDDEN.contains(name))throw bad("Attribute is reserved and cannot be updated through profile changes.");
            if(!names.add(name))throw bad("Each LDAP attribute must appear only once.");
            if(c.add()==null || c.remove()==null || c.add().size()+c.remove().size()==0)throw bad("Supply nonempty add/remove changes, with both arrays present.");
            if((total+=c.add().size()+c.remove().size())>256)throw bad("At most 256 values per profile update.");
            for(String value:c.add())text(value);
            for(String value:c.remove())text(value);
            for(String a:c.add())for(String b:c.remove())if(same(a,b))throw bad("A value cannot be both added and removed.");
            unique(c.add());unique(c.remove());
        }
    }
    private static void text(String value) {
        if(value==null || value.isBlank() || value.length()>4096 || value.chars().anyMatch(Character::isISOControl))
            throw bad("Values must be nonempty text, at most 4096 characters, without control characters.");
    }
    private static void unique(List<String> values) {
        for(int i=0;i<values.size();i++)for(int j=i+1;j<values.size();j++)if(same(values.get(i),values.get(j)))throw bad("Duplicate attribute value.");
    }
    private static boolean same(String a,String b) {
        return CaseIgnoreStringMatchingRule.getInstance().valuesMatch(new ASN1OctetString(a),new ASN1OctetString(b));
    }
    private static boolean contains(List<String> values,String value){return values.stream().anyMatch(v->same(v,value));}
    private static Api.Failure bad(String message){return new Api.Failure(400,"INVALID_MODIFICATIONS",message);}
    private static Api.Failure conflict(){return new Api.Failure(409,"ATTRIBUTE_CONFLICT","Profile values conflict with the approved changes; no update applied.");}
    private static Definition definition(LDAPInterface ldap,String schemaBase,String name) throws LDAPException {
        var query=new SearchRequest(schemaBase,SearchScope.SUB,Filter.createANDFilter(
            Filter.createEqualityFilter("objectClass","attributeSchema"),Filter.createEqualityFilter("lDAPDisplayName",name)),
            "lDAPDisplayName","attributeSyntax","isSingleValued","systemOnly","linkID");
        query.setSizeLimit(2);
        SearchResult result;
        try{result=ldap.search(query);}catch(LDAPSearchException e){
            if(e.getResultCode()==ResultCode.SIZE_LIMIT_EXCEEDED)throw new Api.Failure(503,"ATTRIBUTE_SCHEMA_UNAVAILABLE","Attribute schema lookup is ambiguous.");
            throw e;
        }
        if(result.getEntryCount()!=1)throw new Api.Failure(503,"ATTRIBUTE_SCHEMA_UNAVAILABLE","Required attribute schema is not readable or is unknown.");
        var e=result.getSearchEntries().get(0);
        String canonical=e.getAttributeValue("lDAPDisplayName"),syntax=e.getAttributeValue("attributeSyntax"),single=e.getAttributeValue("isSingleValued");
        if(canonical==null || syntax==null || !canonical.equalsIgnoreCase(name) || single==null || !(single.equalsIgnoreCase("TRUE")||single.equalsIgnoreCase("FALSE")))
            throw new Api.Failure(503,"ATTRIBUTE_SCHEMA_UNAVAILABLE","Required attribute schema is incomplete.");
        if(!TEXT_SYNTAX.contains(syntax) || e.hasAttributeValue("systemOnly","TRUE") || (e.hasAttribute("linkID") && !"0".equals(e.getAttributeValue("linkID"))))
            throw bad("Only writable unlinked Unicode/case-insensitive text attributes are supported.");
        return new Definition(canonical,Boolean.parseBoolean(single));
    }
    static DirectoryClient.Outcome update(LDAPInterface ldap,Api.ObjectRef user,String searchBase,List<Api.AttributeChange> changes,String configured) throws LDAPException {
        validate(changes);
        if(user==null || user.reference()!=null || (user.dn()!=null)==(user.guid()!=null))
            throw new Api.Failure(400,"INVALID_IDENTIFIER","Profile updates require exactly one explicit dn or guid.");
        Set<String> allowed;
        try{allowed=allowedNames(configured);}catch(IllegalArgumentException e){throw new Api.Failure(503,"INVALID_UPDATE_CONFIGURATION","Invalid update attribute allowlist.");}
        for(var c:changes)if(!allowed.contains(c.attribute().toLowerCase(Locale.ROOT)))throw new Api.Failure(400,"ATTRIBUTE_NOT_ALLOWED","Profile attribute is not enabled in the update allowlist.");
        var root=ldap.getRootDSE();String schemaBase=root==null?null:root.getAttributeValue("schemaNamingContext");
        if(schemaBase==null || schemaBase.isBlank())throw new Api.Failure(503,"ATTRIBUTE_SCHEMA_UNAVAILABLE","DC must expose schemaNamingContext.");
        List<Definition> definitions=new ArrayList<>();
        for(var c:changes)definitions.add(definition(ldap,schemaBase,c.attribute()));
        String dn=new DirectoryClient().resolve(ldap,user,searchBase);
        List<String> attrs=new ArrayList<>(List.of("objectClass","objectGUID"));changes.forEach(c->attrs.add(c.attribute()));
        var entry=ldap.getEntry(dn,attrs.toArray(String[]::new));
        if(entry==null)throw new Api.Failure(404,"OBJECT_NOT_FOUND","Target user was not found or is not visible.");
        if(!entry.hasAttributeValue("objectClass","user") || entry.hasAttributeValue("objectClass","computer"))
            throw new Api.Failure(400,"INVALID_USER_OBJECT","Profile target must be an AD user.");
        byte[] guid=entry.getAttributeValueBytes("objectGUID");
        if(guid==null || guid.length!=16)throw new Api.Failure(409,"ACCOUNT_GUID_UNAVAILABLE","Readable objectGUID is required before a profile update.");
        if(user.guid()!=null && !Arrays.equals(guid,AdGuid.bytes(user.guid())))throw new Api.Failure(409,"ACCOUNT_CHANGED","User changed during GUID resolution.");
        List<Modification> operations=new ArrayList<>();
        for(int i=0;i<changes.size();i++) {
            var change=changes.get(i);var def=definitions.get(i);
            // AD returns range=0-1499, not a bare range option.
            for(var a:entry.getAttributes())if(a.getBaseName().equalsIgnoreCase(def.name()) && a.getName().toLowerCase(Locale.ROOT).contains(";range="))
                throw new Api.Failure(409,"INCOMPLETE_ATTRIBUTE_VALUES","Ranged attribute values are not supported; no update applied.");
            String[] raw=entry.getAttributeValues(def.name());List<String> current=raw==null?List.of():Arrays.asList(raw);
            if(def.single()) {
                if(change.add().size()>1 || change.remove().size()>1)throw bad("Single-valued attributes accept at most one add and one remove value.");
                if(current.size()>1)throw conflict();
                if(!current.isEmpty() && !contains(change.remove(),current.get(0)) && !contains(change.add(),current.get(0)))throw conflict();
            }
            List<String> removes=new ArrayList<>(),adds=new ArrayList<>();
            for(String v:current)if(contains(change.remove(),v))removes.add(v);
            for(String v:change.add())if(!contains(current,v))adds.add(v);
            // DELETE only observed values; ADD only absent values. AD evaluates all in one atomic Modify.
            // Strict operations detect relevant intervening changes without overwriting unrelated values.
            if(!removes.isEmpty())operations.add(new Modification(ModificationType.DELETE,def.name(),removes.toArray(String[]::new)));
            if(!adds.isEmpty())operations.add(new Modification(ModificationType.ADD,def.name(),adds.toArray(String[]::new)));
        }
        if(operations.isEmpty())return new DirectoryClient.Outcome(false,"PROFILE_ALREADY_SATISFIED",entry.getDN(),null);
        String stable="<GUID="+HexFormat.of().formatHex(guid)+">";
        try{ldap.modify(new ModifyRequest(stable,operations));}
        catch(LDAPException e) {
            if(e.getResultCode()==ResultCode.NO_SUCH_ATTRIBUTE || e.getResultCode()==ResultCode.ATTRIBUTE_OR_VALUE_EXISTS || e.getResultCode()==ResultCode.CONSTRAINT_VIOLATION)
                throw new Api.Failure(409,"ATTRIBUTE_CONFLICT","AD rejected conflicting profile values; no update applied. Re-read the account before retrying.");
            throw e;
        }
        return new DirectoryClient.Outcome(true,"PROFILE_UPDATED",entry.getDN(),null);
    }
}
