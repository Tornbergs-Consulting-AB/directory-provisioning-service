// Copyright 2026 Tornbergs Consulting AB
// SPDX-License-Identifier: Apache-2.0
package consulting.tornbergs.directory;

import com.unboundid.ldap.sdk.*;
import java.util.*;

/** Single-user Add only; caller owns serialization and the caller-bound connection. */
final class AccountCreation {
    record Plan(String reference, DN container, String cn, Map<String,String> attributes, int maxSuffix) {}
    private static final Set<String> RESERVED=Set.of("cn","name","distinguishedname","objectclass","objectcategory","objectguid","objectsid","employeeid","useraccountcontrol","unicodepwd","userpassword","password","pwdlastset","member","memberof","primarygroupid","ntsecuritydescriptor","sidhistory","samaccounttype","accountexpires");
    static Set<String> configuredNames(String text) {
        Set<String> result=new HashSet<>();
        for(String name:text.split(",")) {
            name=name.trim().toLowerCase(Locale.ROOT);
            if(!name.matches("[a-z][a-z0-9-]*") || RESERVED.contains(name))
                throw new IllegalArgumentException("Invalid creation attribute configuration");
            result.add(name);
        }
        return result;
    }
    static Plan validate(Api.Creation c,String allowedText,String requiredText,int maxSuffix) {
        if(c==null)throw bad("creation is required.");
        if(maxSuffix<0 || maxSuffix>1000)throw new Api.Failure(503,"INVALID_CREATION_CONFIGURATION","Creation suffix limit must be 0-1000.");
        Set<String> allowed,required;
        try { allowed=configuredNames(allowedText);required=configuredNames(requiredText); }
        catch(IllegalArgumentException e){throw new Api.Failure(503,"INVALID_CREATION_CONFIGURATION","Invalid creation attribute configuration.");}
        if(!allowed.containsAll(required) || !required.containsAll(Set.of("samaccountname","userprincipalname")))
            throw new Api.Failure(503,"INVALID_CREATION_CONFIGURATION","Required creation attributes must include account names and be allowed.");
        text(c.reference(),"creation.reference",16);text(c.cn(),"creation.cn",64);
        DN container;
        try {container=new DN(c.containerDN());if(container.isNullDN())throw bad("Empty container DN.");}
        catch(Exception e){throw bad("A valid nonempty creation.containerDN is required.");}
        if(c.containerDN().length()>4096 || c.containerDN().chars().anyMatch(v->v<32))throw bad("Invalid creation.containerDN.");
        if(c.attributes()==null || c.attributes().isEmpty() || c.attributes().size()>64)throw bad("creation.attributes is required (maximum 64 attributes).");
        Map<String,String> values=new LinkedHashMap<>();
        for(var entry:c.attributes().entrySet()) {
            String name=entry.getKey().toLowerCase(Locale.ROOT);
            if(!allowed.contains(name) || RESERVED.contains(name))throw bad("Creation attribute is not allowed.");
            if(values.containsKey(name))throw bad("Duplicate creation attribute name.");
            if(entry.getValue()==null || !entry.getValue().isTextual())throw bad("Creation attributes must be single-valued JSON strings.");
            String value=entry.getValue().textValue();text(value,"Creation attribute",4096);values.put(name,value);
        }
        if(!values.keySet().containsAll(required))throw bad("Required creation attributes are missing.");
        String sam=values.get("samaccountname"),upn=values.get("userprincipalname");
        if(!sam.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,19}") || sam.endsWith("."))
            throw bad("sAMAccountName must be 1-20 ASCII letters/digits/dot/underscore/hyphen, start with a letter/digit and not end with a dot.");
        if(upn.length()>1024 || !upn.matches("[A-Za-z0-9][A-Za-z0-9._-]*@[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]"))
            throw bad("userPrincipalName must have a simple ASCII local part and DNS suffix.");
        return new Plan(c.reference(),container,c.cn(),Collections.unmodifiableMap(values),maxSuffix);
    }
    private static void text(String value,String label,int max) {
        if(value==null || value.isBlank() || value.length()>max || value.chars().anyMatch(v->v<32))
            throw bad(label+" must be nonempty text of at most "+max+" characters without control characters.");
    }
    private static Api.Failure bad(String message){return new Api.Failure(400,"INVALID_CREATION",message);}
    record Candidate(String dn,String cn,Map<String,String> attributes) {}
    static Candidate candidate(Plan plan,int suffix) {
        String tail=suffix==0?"":Integer.toString(suffix);
        Map<String,String> values=new LinkedHashMap<>(plan.attributes());
        values.put("samaccountname",append(values.get("samaccountname"),tail,20));
        String upn=values.get("userprincipalname");int at=upn.indexOf('@');
        values.put("userprincipalname",append(upn.substring(0,at),tail,1024-(upn.length()-at))+upn.substring(at));
        String cn=append(plan.cn(),tail,64);
        return new Candidate(new DN(new RDN("CN",cn),plan.container()).toString(),cn,values);
    }
    private static String append(String value,String tail,int max) {
        int end=Math.min(value.length(),max-tail.length());
        if(end<1)throw bad("Name cannot accommodate suffix.");
        if(end<value.length() && Character.isHighSurrogate(value.charAt(end-1)))end--;
        return value.substring(0,end)+tail;
    }
    static String domain(LDAPInterface ldap) throws LDAPException {
        var root=ldap.getRootDSE();String base=root==null?null:root.getAttributeValue("defaultNamingContext");
        try {if(base==null || new DN(base).isNullDN())throw new IllegalArgumentException();}
        catch(Exception e){throw new Api.Failure(503,"DOMAIN_LOOKUP_FAILED","DC must expose defaultNamingContext for domain-scoped creation lookups.");}
        return base;
    }
    private static SearchResult search(LDAPInterface ldap,String domain,Filter filter,String... attrs) throws LDAPException {
        SearchRequest request=new SearchRequest(domain,SearchScope.SUB,filter,attrs);request.setSizeLimit(2);
        try{return ldap.search(request);}
        catch(LDAPSearchException e){
            if(e.getResultCode()==ResultCode.SIZE_LIMIT_EXCEEDED)throw new Api.Failure(409,"AMBIGUOUS_CREATION_LOOKUP","Creation lookup returned too many objects; no account created.");
            throw e;
        }
    }
    private static SearchResultEntry reference(LDAPInterface ldap,String domain,Plan plan) throws LDAPException {
        Set<String> attrs=new HashSet<>(plan.attributes().keySet());attrs.add("objectClass");attrs.add("userAccountControl");attrs.add("cn");
        var found=search(ldap,domain,Filter.createEqualityFilter("employeeID",plan.reference()),attrs.toArray(String[]::new));
        if(found.getEntryCount()>1)throw new Api.Failure(409,"REFERENCE_CONFLICT","Multiple accounts have the creation reference.");
        return found.getEntryCount()==0?null:found.getSearchEntries().get(0);
    }
    private static DirectoryClient.Outcome existing(SearchResultEntry entry,Plan plan) throws LDAPException {
        if(!entry.hasAttributeValue("objectClass","user") || entry.hasAttributeValue("objectClass","computer"))throw conflict();
        String uac=entry.getAttributeValue("userAccountControl");
        try {if((Integer.parseInt(uac)&2)==0)throw conflict();}
        catch(NumberFormatException e){throw conflict();}
        // Recognize only values this exact plan could have generated. Never update/adopt another account.
        for(int n=0;n<=plan.maxSuffix();n++) {
            Candidate expected=candidate(plan,n);
            if(!new DN(entry.getDN()).equals(new DN(expected.dn())))continue;
            boolean match=true;
            for(var attr:expected.attributes().entrySet()) {
                String[] actual=entry.getAttributeValues(attr.getKey());
                if(actual==null || actual.length!=1 || !(attr.getKey().equals("samaccountname")||attr.getKey().equals("userprincipalname")?
                    actual[0].equalsIgnoreCase(attr.getValue()):actual[0].equals(attr.getValue()))) {match=false;break;}
            }
            if(match)return new DirectoryClient.Outcome(false,"ACCOUNT_ALREADY_EXISTS",entry.getDN(),null,entry.getAttributeValue("sAMAccountName"),entry.getAttributeValue("userPrincipalName"));
        }
        throw conflict();
    }
    private static Api.Failure conflict(){return new Api.Failure(409,"REFERENCE_CONFLICT","Existing reference does not match the requested disabled account; no changes made.");}
    private static boolean occupied(LDAPInterface ldap,String domain,Candidate c) throws LDAPException {
        if(ldap.getEntry(c.dn(),"1.1")!=null)return true;
        SearchRequest lookup=new SearchRequest(domain,SearchScope.SUB,Filter.createORFilter(
            Filter.createEqualityFilter("sAMAccountName",c.attributes().get("samaccountname")),
            Filter.createEqualityFilter("userPrincipalName",c.attributes().get("userprincipalname"))),"1.1");
        lookup.setSizeLimit(1);
        try{return ldap.search(lookup).getEntryCount()!=0;}
        catch(LDAPSearchException e){if(e.getResultCode()==ResultCode.SIZE_LIMIT_EXCEEDED)return true;throw e;}
    }
    static DirectoryClient.Outcome create(LDAPInterface ldap,Plan plan) throws LDAPException {
        String domain=domain(ldap);
        if(!plan.container().isDescendantOf(new DN(domain),true))throw bad("Creation container must be within the connected DC domain.");
        var prior=reference(ldap,domain,plan);if(prior!=null)return existing(prior,plan);
        var container=ldap.getEntry(plan.container().toString(),"objectClass");
        if(container==null)throw new Api.Failure(404,"CONTAINER_NOT_FOUND","Creation container does not exist or is not visible.");
        if(!container.hasAttributeValue("objectClass","organizationalUnit") && !container.hasAttributeValue("objectClass","container"))throw bad("Creation parent must be an OU or container.");
        for(int n=0;n<=plan.maxSuffix();n++) {
            Candidate c=candidate(plan,n);
            if(occupied(ldap,domain,c))continue;
            List<Attribute> attrs=new ArrayList<>();
            attrs.add(new Attribute("objectClass","top","person","organizationalPerson","user"));
            attrs.add(new Attribute("cn",c.cn()));attrs.add(new Attribute("employeeID",plan.reference()));
            attrs.add(new Attribute("userAccountControl","514"));
            c.attributes().forEach((name,value)->attrs.add(new Attribute(name,value)));
            try {ldap.add(new AddRequest(c.dn(),attrs));return new DirectoryClient.Outcome(true,"ACCOUNT_CREATED",c.dn(),null,c.attributes().get("samaccountname"),c.attributes().get("userprincipalname"));}
            catch(LDAPException e) {
                // Only advance on a confirmed name collision, never on an ambiguous transport outcome.
                if(e.getResultCode()!=ResultCode.ENTRY_ALREADY_EXISTS && e.getResultCode()!=ResultCode.CONSTRAINT_VIOLATION)throw e;
                var concurrent=reference(ldap,domain,plan);if(concurrent!=null)return existing(concurrent,plan);
                if(!occupied(ldap,domain,c))throw e;
            }
        }
        throw new Api.Failure(409,"NAME_COLLISION_LIMIT","No available account name within the configured suffix limit.");
    }
}
