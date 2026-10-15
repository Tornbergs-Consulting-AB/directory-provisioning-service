// Copyright 2026 Tornbergs Consulting AB
// SPDX-License-Identifier: Apache-2.0
package consulting.tornbergs.directory;

import com.unboundid.ldap.listener.*;
import com.unboundid.ldap.sdk.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Generic LDAP fixture emulates AD's GUID-name delete resolution; live AD validation is separate. */
class AccountDeletionTest {
 private InMemoryDirectoryServer server;
 private final DirectoryClient client=new DirectoryClient();
 private final String base="dc=example,dc=com",dn="cn=Account,dc=example,dc=com";
 private final String guid="86613765-80db-4d5e-a857-dced76823650";
 private final AtomicInteger deletes=new AtomicInteger();
 @BeforeEach void setup() throws Exception {
  var config=new InMemoryDirectoryServerConfig(base);config.setSchema(null);
  server=new InMemoryDirectoryServer(config);
  server.add("dn: "+base,"objectClass: domain","dc: example");
  server.add(new Entry(dn,new Attribute("objectClass","user"),new Attribute("cn","Account"),new Attribute("objectGUID",AdGuid.bytes(guid))));
 }
 @AfterEach void close(){server.shutDown(true);}
 private LDAPInterface ad(Runnable beforeDelete,ResultCode failure) {
  return (LDAPInterface)Proxy.newProxyInstance(LDAPInterface.class.getClassLoader(),new Class<?>[]{LDAPInterface.class},(proxy,method,args)->{
   if (method.getName().equals("delete")) {
    deletes.incrementAndGet();
    DeleteRequest request=(DeleteRequest)args[0];
    assertEquals("<GUID="+java.util.HexFormat.of().formatHex(AdGuid.bytes(guid))+">",request.getDN());
    assertEquals(0,request.getControls().length,"No tree delete or other controls");
    if(beforeDelete!=null)beforeDelete.run();
    if(failure!=null)throw new LDAPException(failure,"Synthetic failure");
    var found=server.search(base,SearchScope.SUB,Filter.createEqualityFilter("objectGUID",AdGuid.bytes(guid)),"1.1");
    if(found.getEntryCount()!=1)throw new LDAPException(ResultCode.NO_SUCH_OBJECT);
    return server.delete(found.getSearchEntries().get(0).getDN());
   }
   try{return method.invoke(server,args);}catch(InvocationTargetException e){throw e.getCause();}
  });
 }
 @Test void deleteByDnAndRepeatIsNotFalseSuccess() throws Exception {
  var result=client.deleteAccount(ad(null,null),new Api.ObjectRef(dn,null),base);
  assertTrue(result.changed());assertEquals("ACCOUNT_DELETED",result.code());assertEquals(dn,result.userDN());assertNull(result.groupDN());
  assertNull(server.getEntry(dn));
  assertEquals(404,assertThrows(Api.Failure.class,()->client.deleteAccount(ad(null,null),new Api.ObjectRef(dn,null),base)).status);
  assertEquals(1,deletes.get());
 }
 @Test void guidDeletionResolvesMovedAccount() throws Exception {
  server.modifyDN(dn,"cn=Moved",true);
  var result=client.deleteAccount(ad(null,null),new Api.ObjectRef(null,guid),base);
  assertEquals("cn=Moved,"+base,result.userDN());assertNull(server.getEntry("cn=Moved,"+base));
  assertEquals(404,assertThrows(Api.Failure.class,()->client.deleteAccount(ad(null,null),new Api.ObjectRef(null,guid),base)).status);
 }
 @Test void concurrentRenameAndDnReuseCannotDeleteReplacement() throws Exception {
  Runnable rename=()->{try {
   server.modifyDN(dn,"cn=Moved",true);
   server.add(new Entry(dn,new Attribute("objectClass","user"),new Attribute("cn","Account"),new Attribute("objectGUID",AdGuid.bytes("11111111-2222-3333-4444-555555555555"))));
  }catch(Exception e){throw new RuntimeException(e);}};
  client.deleteAccount(ad(rename,null),new Api.ObjectRef(null,guid),base);
  assertNotNull(server.getEntry(dn));assertNull(server.getEntry("cn=Moved,"+base));
 }
 @Test void refuseComputerAndGroup() throws Exception {
  server.modify(dn,new Modification(ModificationType.ADD,"objectClass","computer"));
  assertEquals("INVALID_USER_OBJECT",assertThrows(Api.Failure.class,()->client.deleteAccount(ad(null,null),new Api.ObjectRef(dn,null),base)).code);
  server.modify(dn,new Modification(ModificationType.REPLACE,"objectClass","group"));
  assertEquals("INVALID_USER_OBJECT",assertThrows(Api.Failure.class,()->client.deleteAccount(ad(null,null),new Api.ObjectRef(dn,null),base)).code);
  assertEquals(0,deletes.get());
 }
 @Test void unreadableGuidFailsClosed() throws Exception {
  server.modify(dn,new Modification(ModificationType.DELETE,"objectGUID"));
  assertEquals("ACCOUNT_GUID_UNAVAILABLE",assertThrows(Api.Failure.class,()->client.deleteAccount(ad(null,null),new Api.ObjectRef(dn,null),base)).code);
  assertEquals(0,deletes.get());
 }
 @Test void guidResolutionReplacementIsRejected() {
  LDAPInterface changed=(LDAPInterface)Proxy.newProxyInstance(LDAPInterface.class.getClassLoader(),new Class<?>[]{LDAPInterface.class},(proxy,method,args)->{
   if(method.getName().equals("getEntry"))server.modify(dn,new Modification(ModificationType.REPLACE,"objectGUID",AdGuid.bytes("11111111-2222-3333-4444-555555555555")));
   try{return method.invoke(server,args);}catch(InvocationTargetException e){throw e.getCause();}
  });
  assertEquals("ACCOUNT_CHANGED",assertThrows(Api.Failure.class,()->client.deleteAccount(changed,new Api.ObjectRef(null,guid),base)).code);
 }
 @Test void deniedOrTimedOutDeleteIsNotRetried() throws Exception {
  for(var result:new ResultCode[]{ResultCode.INSUFFICIENT_ACCESS_RIGHTS,ResultCode.TIMEOUT})
   assertEquals(result,assertThrows(LDAPException.class,()->client.deleteAccount(ad(null,result),new Api.ObjectRef(null,guid),base)).getResultCode());
  assertEquals(2,deletes.get());assertNotNull(server.getEntry(dn));
 }
 @Test void noRecursiveDeletion() throws Exception {
  server.add("dn: cn=Child,"+dn,"objectClass: person","cn: Child");
  assertEquals(ResultCode.NOT_ALLOWED_ON_NONLEAF,assertThrows(LDAPException.class,()->client.deleteAccount(ad(null,null),new Api.ObjectRef(dn,null),base)).getResultCode());
  assertNotNull(server.getEntry(dn));assertNotNull(server.getEntry("cn=Child,"+dn));
 }
 @Test void guidOutsideSearchBaseDoesNotDelete() {
  assertThrows(LDAPException.class,()->client.deleteAccount(ad(null,null),new Api.ObjectRef(null,guid),"cn=Missing,"+base));
  assertEquals(0,deletes.get());
 }
}
