// Copyright 2026 Tornbergs Consulting AB
// SPDX-License-Identifier: Apache-2.0
package consulting.tornbergs.directory;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.TextNode;
import com.unboundid.ldap.listener.*;
import com.unboundid.ldap.sdk.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class AccountCreationTest {
 private InMemoryDirectoryServer server;
 private final String domain="dc=example,dc=com",ou="ou=Users,dc=example,dc=com";
 private final String allowed="sAMAccountName,userPrincipalName,givenName,sn,department,title";
 private final AtomicInteger adds=new AtomicInteger();
 @BeforeEach void setup() throws Exception {
  var config=new InMemoryDirectoryServerConfig(domain);config.setSchema(null);server=new InMemoryDirectoryServer(config);
  server.add("dn: "+domain,"objectClass: domain","dc: example");
  server.add("dn: "+ou,"objectClass: organizationalUnit","ou: Users");
  server.add("dn: ou=Other,"+domain,"objectClass: organizationalUnit","ou: Other");
 }
 @AfterEach void stop(){server.shutDown(true);}
 private LDAPInterface ldap(ResultCode addError,boolean lostAfterAdd) {
  return (LDAPInterface)Proxy.newProxyInstance(LDAPInterface.class.getClassLoader(),new Class<?>[]{LDAPInterface.class},(p,m,a)->{
   if(m.getName().equals("getRootDSE"))return new RootDSE(new Entry("",new Attribute("defaultNamingContext",domain)));
   if(m.getName().equals("add")) {
    adds.incrementAndGet();
    if(addError!=null)throw new LDAPException(addError,"Synthetic error");
    if(lostAfterAdd){server.add((AddRequest)a[0]);throw new LDAPException(ResultCode.TIMEOUT,"Synthetic lost response");}
   }
   try{return m.invoke(server,a);}catch(InvocationTargetException e){throw e.getCause();}
  });
 }
 private Api.Creation body(String reference) {
  Map<String,JsonNode> attrs=new LinkedHashMap<>();
  attrs.put("sAMAccountName",new TextNode("dper"));attrs.put("userPrincipalName",new TextNode("dper@example.com"));
  attrs.put("givenName",new TextNode("Daniel"));attrs.put("sn",new TextNode("Persson"));attrs.put("department",new TextNode("Sales"));
  return new Api.Creation(reference,ou,"Daniel Persson",attrs);
 }
 private AccountCreation.Plan plan(Api.Creation c,int max){return AccountCreation.validate(c,allowed,"sAMAccountName,userPrincipalName,givenName,sn",max);}
 @Test void createsDisabledUserAndReferenceInOneAddThenReconcilesRetry() throws Exception {
  var result=AccountCreation.create(ldap(null,false),plan(body("123456-EMP"),100));
  assertTrue(result.changed());assertEquals("ACCOUNT_CREATED",result.code());assertEquals("dper",result.accountName());
  var entry=server.getEntry(result.userDN());assertEquals("514",entry.getAttributeValue("userAccountControl"));
  assertEquals("123456-EMP",entry.getAttributeValue("employeeID"));assertNull(entry.getAttribute("unicodePwd"));
  assertEquals("Sales",entry.getAttributeValue("department"));
  assertEquals("ACCOUNT_ALREADY_EXISTS",AccountCreation.create(ldap(null,false),plan(body("123456-EMP"),100)).code());assertEquals(1,adds.get());
 }
 @Test void suffixesSamUpnAndCnTogetherAndRetryDoesNotAllocateAnother() throws Exception {
  var first=AccountCreation.create(ldap(null,false),plan(body("123456-EMP"),100));
  var second=AccountCreation.create(ldap(null,false),plan(body("654321-EMP"),100));
  assertEquals("dper1",second.accountName());assertEquals("dper1@example.com",second.upn());
  assertEquals("Daniel Persson1",server.getEntry(second.userDN()).getAttributeValue("cn"));
  assertEquals(second.userDN(),AccountCreation.create(ldap(null,false),plan(body("654321-EMP"),100)).userDN());assertEquals(2,adds.get());assertNotEquals(first.userDN(),second.userDN());
 }
 @Test void checksNamesAcrossDomainOutsideCreationOu() throws Exception {
  server.add("dn: cn=Elsewhere,ou=Other,"+domain,"objectClass: user","cn: Elsewhere","sAMAccountName: dper");
  assertEquals("dper1",AccountCreation.create(ldap(null,false),plan(body("123456-EMP"),100)).accountName());
 }
 @Test void suffixBoundIsEnforced() throws Exception {
  AccountCreation.create(ldap(null,false),plan(body("123456-EMP"),0));
  assertEquals("NAME_COLLISION_LIMIT",assertThrows(Api.Failure.class,()->AccountCreation.create(ldap(null,false),plan(body("654321-EMP"),0))).code);assertEquals(1,adds.get());
 }
 @Test void changedValuesOrEnabledAccountAreConflicts() throws Exception {
  var result=AccountCreation.create(ldap(null,false),plan(body("123456-EMP"),100));
  server.modify(result.userDN(),new Modification(ModificationType.REPLACE,"department","Other"));
  assertEquals("REFERENCE_CONFLICT",assertThrows(Api.Failure.class,()->AccountCreation.create(ldap(null,false),plan(body("123456-EMP"),100))).code);
  server.modify(result.userDN(),new Modification(ModificationType.REPLACE,"department","Sales"),new Modification(ModificationType.REPLACE,"userAccountControl","512"));
  assertEquals("REFERENCE_CONFLICT",assertThrows(Api.Failure.class,()->AccountCreation.create(ldap(null,false),plan(body("123456-EMP"),100))).code);
 }
 @Test void multipleReferencesAreConflicts() throws Exception {
  AccountCreation.create(ldap(null,false),plan(body("123456-EMP"),100));
  server.add("dn: cn=Duplicate,"+ou,"objectClass: user","cn: Duplicate","employeeID: 123456-EMP");
  assertEquals("REFERENCE_CONFLICT",assertThrows(Api.Failure.class,()->AccountCreation.create(ldap(null,false),plan(body("123456-EMP"),100))).code);
 }
 @Test void noAddReplayOnTimeoutAndNextRequestFindsCreatedAccount() throws Exception {
  assertEquals(ResultCode.TIMEOUT,assertThrows(LDAPException.class,()->AccountCreation.create(ldap(null,true),plan(body("123456-EMP"),100))).getResultCode());
  assertEquals(1,adds.get());assertEquals("ACCOUNT_ALREADY_EXISTS",AccountCreation.create(ldap(null,false),plan(body("123456-EMP"),100)).code());assertEquals(1,adds.get());
 }
 @Test void accessDeniedAndNonCollisionConstraintArePropagatedOnce() {
  for(var code:new ResultCode[]{ResultCode.INSUFFICIENT_ACCESS_RIGHTS,ResultCode.CONSTRAINT_VIOLATION})
   assertEquals(code,assertThrows(LDAPException.class,()->AccountCreation.create(ldap(code,false),plan(body("123456-EMP"),100))).getResultCode());
  assertEquals(2,adds.get());
 }
 @Test void rejectedTextTypesReservedAttributesAndLongReference() {
  assertThrows(Api.Failure.class,()->plan(body("12345678901234567"),100));
  for(String json:List.of("123","true","null","[\"Sales\"]","{}")) {
   var c=body("123456-EMP");try{c.attributes().put("department",new ObjectMapper().readTree(json));}catch(Exception e){throw new RuntimeException(e);}
   assertThrows(Api.Failure.class,()->plan(c,100));
  }
  for(String name:List.of("employeeID","userAccountControl","unicodePwd","member")) {
   var c=body("123456-EMP");c.attributes().put(name,new TextNode("forbidden"));assertThrows(Api.Failure.class,()->plan(c,100));
  }
 }
 @Test void additionalAllowedTextAttributeNeedsNoJavaChange() {
  var c=body("123456-EMP");c.attributes().put("company",new TextNode("Example"));
  assertThrows(Api.Failure.class,()->plan(c,100));
  assertEquals("Example",AccountCreation.validate(c,allowed+",company","sAMAccountName,userPrincipalName",100).attributes().get("company"));
 }
 @Test void safelyEscapesCnAndKeepsNameLengthsWhenSuffixing() throws Exception {
  var c=body("123456-EMP");c.attributes().put("sAMAccountName",new TextNode("abcdefghijklmnopqrst"));
  var plan=plan(new Api.Creation(c.reference(),ou,"Persson, Daniel",c.attributes()),100);
  var candidate=AccountCreation.candidate(plan,12);
  assertEquals(20,candidate.attributes().get("samaccountname").length());
  assertEquals("Persson, Daniel12",new DN(candidate.dn()).getRDN().getAttributeValues()[0]);
 }
 @Test void placementOutsideDomainOrUnderUserIsRejected() throws Exception {
  var c=body("123456-EMP");
  assertThrows(Api.Failure.class,()->AccountCreation.create(ldap(null,false),plan(new Api.Creation(c.reference(),"ou=Users,dc=other,dc=com",c.cn(),c.attributes()),100)));
  var result=AccountCreation.create(ldap(null,false),plan(c,100));
  assertThrows(Api.Failure.class,()->AccountCreation.create(ldap(null,false),plan(new Api.Creation("654321-EMP",result.userDN(),c.cn(),c.attributes()),100)));
 }
 @Test void confirmedConcurrentNameCollisionAdvancesButDoesNotAdoptOtherReference() throws Exception {
  LDAPInterface race=(LDAPInterface)Proxy.newProxyInstance(LDAPInterface.class.getClassLoader(),new Class<?>[]{LDAPInterface.class},(p,m,a)->{
   if(m.getName().equals("getRootDSE"))return new RootDSE(new Entry("",new Attribute("defaultNamingContext",domain)));
   if(m.getName().equals("add") && adds.getAndIncrement()==0) {
    AddRequest request=(AddRequest)a[0];Entry blocked=request.toEntry();blocked.setAttribute("employeeID","OTHER-EMP");server.add(blocked);
    throw new LDAPException(ResultCode.ENTRY_ALREADY_EXISTS,"Concurrent naming race");
   }
   try{return m.invoke(server,a);}catch(InvocationTargetException e){throw e.getCause();}
  });
  var result=AccountCreation.create(race,plan(body("123456-EMP"),100));
  assertEquals("dper1",result.accountName());assertEquals(2,adds.get());
 }
}
