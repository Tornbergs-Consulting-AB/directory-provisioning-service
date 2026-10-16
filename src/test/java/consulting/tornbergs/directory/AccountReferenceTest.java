// Copyright 2026 Tornbergs Consulting AB
// SPDX-License-Identifier: Apache-2.0
package consulting.tornbergs.directory;

import com.fasterxml.jackson.databind.node.TextNode;
import com.unboundid.ldap.listener.*;
import com.unboundid.ldap.sdk.*;
import java.lang.reflect.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class AccountReferenceTest {
 private InMemoryDirectoryServer server;
 private final String base="dc=example,dc=com",ou="ou=Users,dc=example,dc=com",dn="cn=Daniel Persson,ou=Users,dc=example,dc=com";
 private final DirectoryClient client=new DirectoryClient();
 private final Api.ObjectRef ref=new Api.ObjectRef(null,null,"123456-EMP");
 @BeforeEach void setup() throws Exception {
  var config=new InMemoryDirectoryServerConfig(base);config.setSchema(null);server=new InMemoryDirectoryServer(config);
  server.add("dn: "+base,"objectClass: domain","dc: example");
  server.add("dn: "+ou,"objectClass: organizationalUnit","ou: Users");
  for(int i=1;i<=2;i++) server.add("dn: cn=Group"+i+","+base,"objectClass: group","cn: Group"+i);
 }
 @AfterEach void stop(){server.shutDown(true);}
 private LDAPInterface ldap() {
  return (LDAPInterface)Proxy.newProxyInstance(LDAPInterface.class.getClassLoader(),new Class<?>[]{LDAPInterface.class},(p,m,a)->{
   if(m.getName().equals("getRootDSE"))return new RootDSE(new Entry("",new Attribute("defaultNamingContext",base)));
   if(m.getName().equals("delete")) {
    var hex=((DeleteRequest)a[0]).getDN();assertTrue(hex.startsWith("<GUID="));
    var match=server.search(base,SearchScope.SUB,Filter.createEqualityFilter("objectGUID",java.util.HexFormat.of().parseHex(hex.substring(6,hex.length()-1))),"1.1");
    return server.delete(match.getSearchEntries().get(0).getDN());
   }
   try{return m.invoke(server,a);}catch(InvocationTargetException e){throw e.getCause();}
  });
 }
 private void addUser() throws Exception {
  server.add(new Entry(dn,new Attribute("objectClass","user"),new Attribute("cn","Daniel Persson"),new Attribute("employeeID",ref.reference()),
   new Attribute("objectGUID",AdGuid.bytes("11111111-2222-3333-4444-555555555555"))));
 }
 @Test void createThenTwoMembershipsWithoutCollectedAccountContext() throws Exception {
  var creation=new Api.Creation(ref.reference(),ou,"Daniel Persson",Map.of("sAMAccountName",new TextNode("dper"),"userPrincipalName",new TextNode("dper@example.com")));
  AccountCreation.create(ldap(),AccountCreation.validate(creation,"sAMAccountName,userPrincipalName","sAMAccountName,userPrincipalName",100));
  for(int i=1;i<=2;i++) {
   String user=client.resolveForMembership(ldap(),ref,"ou=Unrelated,"+base,true,0);
   assertEquals(new DN(dn),new DN(user));
   assertTrue(client.update(ldap(),user,"cn=Group"+i+","+base,true).changed());
   assertEquals("ALREADY_MEMBER",client.update(ldap(),user,"cn=Group"+i+","+base,true).code());
  }
 }
 @Test void referenceFindsMovedUserAndDeletionUsesGuid() throws Exception {
  addUser();server.modifyDN(dn,"cn=Moved",true);
  assertEquals("cn=Moved,"+ou,client.resolve(ldap(),ref,base));
  assertEquals("ACCOUNT_DELETED",client.deleteAccount(ldap(),new Api.ObjectRef(null,"11111111-2222-3333-4444-555555555555"),base).code());
  assertNull(server.getEntry("cn=Moved,"+ou));
 }
 @Test void missingAddIsRetryableButMissingRemovalIsNotSuccess() {
  assertEquals("ACCOUNT_NOT_READY",assertThrows(Api.Failure.class,()->client.resolveForMembership(ldap(),ref,base,true,0)).code);
  assertEquals(503,assertThrows(Api.Failure.class,()->client.resolveForMembership(ldap(),ref,base,true,0)).status);
  assertEquals(400,assertThrows(Api.Failure.class,()->client.resolveForMembership(ldap(),ref,base,false,10000)).status);
 }
 @Test void waitsForCreationThatFinishesAfterInitialLookup() throws Exception {
  AtomicInteger searches=new AtomicInteger();
  LDAPInterface delayed=(LDAPInterface)Proxy.newProxyInstance(LDAPInterface.class.getClassLoader(),new Class<?>[]{LDAPInterface.class},(p,m,a)->{
   if(m.getName().equals("search") && searches.incrementAndGet()==2)addUser();
   try{return m.invoke(ldap(),a);}catch(InvocationTargetException e){throw e.getCause();}
  });
  assertEquals(dn,client.resolveForMembership(delayed,ref,base,true,1000));assertEquals(2,searches.get());
 }
 @Test void ambiguousReferenceAndNonUserAreRejected() throws Exception {
  addUser();server.add("dn: cn=Duplicate,"+ou,"objectClass: user","cn: Duplicate","employeeID: "+ref.reference());
  assertEquals("REFERENCE_CONFLICT",assertThrows(Api.Failure.class,()->client.resolveForMembership(ldap(),ref,base,true,1000)).code);
  server.delete("cn=Duplicate,"+ou);server.modify(dn,new Modification(ModificationType.ADD,"objectClass","computer"));
  assertEquals("REFERENCE_CONFLICT",assertThrows(Api.Failure.class,()->client.resolve(ldap(),ref,base)).code);
 }
 @Test void searchFilterEscapesReferenceAndAclErrorsAreNotRetried() throws Exception {
  addUser();assertEquals(404,assertThrows(Api.Failure.class,()->client.resolve(ldap(),new Api.ObjectRef(null,null,"*)(cn=*)"),base)).status);
  AtomicInteger searches=new AtomicInteger();
  LDAPInterface denied=(LDAPInterface)Proxy.newProxyInstance(LDAPInterface.class.getClassLoader(),new Class<?>[]{LDAPInterface.class},(p,m,a)->{
   if(m.getName().equals("search")){searches.incrementAndGet();throw new LDAPSearchException(ResultCode.INSUFFICIENT_ACCESS_RIGHTS,"Synthetic denied search");}
   try{return m.invoke(ldap(),a);}catch(InvocationTargetException e){throw e.getCause();}
  });
  assertEquals(ResultCode.INSUFFICIENT_ACCESS_RIGHTS,assertThrows(LDAPException.class,()->client.resolveForMembership(denied,ref,base,true,1000)).getResultCode());
  assertEquals(1,searches.get());
 }
 @Test void referenceChangedBetweenLookupAndInspectionCannotDelete() throws Exception {
  addUser();
  LDAPInterface changed=(LDAPInterface)Proxy.newProxyInstance(LDAPInterface.class.getClassLoader(),new Class<?>[]{LDAPInterface.class},(p,m,a)->{
   if(m.getName().equals("getEntry"))server.modify(dn,new Modification(ModificationType.REPLACE,"employeeID","OTHER-EMP"));
   try{return m.invoke(ldap(),a);}catch(InvocationTargetException e){throw e.getCause();}
  });
  assertEquals("INVALID_IDENTIFIER",assertThrows(Api.Failure.class,()->client.deleteAccount(changed,ref,base)).code);assertNotNull(server.getEntry(dn));
 }
 @Test void groupReferenceAndMixedIdentifiersAreRejected() {
  var group=new Api.ObjectRef("cn=Group1,"+base,null);
  ProvisioningResource.validate(new Api.ProvisioningRequest("1",null,"DEMO","ADD_PERMISSION_TO_USER",ref,group));
  for(var invalid:new Api.ObjectRef[]{new Api.ObjectRef(dn,null,ref.reference()),new Api.ObjectRef(null,null,""),new Api.ObjectRef(null,null,"12345678901234567")})
   assertThrows(Api.Failure.class,()->ProvisioningResource.validate(new Api.ProvisioningRequest("1",null,"DEMO","ADD_PERMISSION_TO_USER",invalid,group)));
  assertThrows(Api.Failure.class,()->ProvisioningResource.validate(new Api.ProvisioningRequest("1",null,"DEMO","ADD_PERMISSION_TO_USER",ref,ref)));
 }
 @Test void bothDeletionTypesAcceptOnlyDnOrGuid() {
  for(var type:new String[]{"REMOVE_ACCOUNT","REMOVE_APPLICATION_FROM_USER"})
   for(var id:new Api.ObjectRef[]{new Api.ObjectRef(dn,null),new Api.ObjectRef(null,"11111111-2222-3333-4444-555555555555")})
    ProvisioningResource.validate(new Api.ProvisioningRequest("1",null,"DEMO",type,null,null,id));
 }
 @Test void destructiveRequestsRejectReferenceAtApiAndClientBoundaries() {
  for(String type:new String[]{"REMOVE_ACCOUNT","REMOVE_APPLICATION_FROM_USER"})
   assertEquals(400,assertThrows(Api.Failure.class,()->ProvisioningResource.validate(new Api.ProvisioningRequest("1",null,"DEMO",type,null,null,ref))).status);
  for(String type:new String[]{"REMOVE_ACCOUNT_PERMISSION","REMOVE_PERMISSION_ASSIGNMENT"})
   assertEquals(400,assertThrows(Api.Failure.class,()->ProvisioningResource.validate(new Api.ProvisioningRequest("1",null,"DEMO",type,ref,new Api.ObjectRef("cn=Group1,"+base,null)))).status);
  assertEquals(400,assertThrows(Api.Failure.class,()->client.deleteAccount(ldap(),ref,base)).status);
 }

}
