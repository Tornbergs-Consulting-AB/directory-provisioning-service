// Copyright 2026 Tornbergs Consulting AB
// SPDX-License-Identifier: Apache-2.0
package consulting.tornbergs.directory;

import com.unboundid.ldap.listener.*;
import com.unboundid.ldap.sdk.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Generic LDAP fixtures emulate AD schema metadata and native GUID Modify addressing. */
class AttributeUpdatesTest {
 private InMemoryDirectoryServer server;
 private final String base="dc=example,dc=com",schema="cn=Schema,dc=example,dc=com",dn="cn=Test,dc=example,dc=com";
 private final String guid="35c3d1fc-6e13-493e-9501-0d103bcc344f";
 private final Api.ObjectRef id=new Api.ObjectRef(null,guid);
 private final AtomicInteger writes=new AtomicInteger();
 @BeforeEach void setup() throws Exception {
  var c=new InMemoryDirectoryServerConfig(base);c.setSchema(null);server=new InMemoryDirectoryServer(c);
  server.add("dn: "+base,"objectClass: domain","dc: example");
  server.add("dn: "+schema,"objectClass: container","cn: Schema");
  for(String name:List.of("otherTelephone","department","description"))server.add("dn: cn="+name+","+schema,"objectClass: attributeSchema","cn: "+name,
   "lDAPDisplayName: "+name,"attributeSyntax: 2.5.5.12","isSingleValued: "+name.equals("department"),"systemOnly: FALSE");
  server.add(new Entry(dn,new Attribute("objectClass","user"),new Attribute("cn","Test"),new Attribute("objectGUID",AdGuid.bytes(guid)),
   new Attribute("department","Sales"),new Attribute("otherTelephone","1236","1235","9999")));
 }
 @AfterEach void stop(){server.shutDown(true);}
 private LDAPInterface ldap(Runnable race,ResultCode error) {
  return (LDAPInterface)Proxy.newProxyInstance(LDAPInterface.class.getClassLoader(),new Class<?>[]{LDAPInterface.class},(p,m,a)->{
   if(m.getName().equals("getRootDSE"))return new RootDSE(new Entry("",new Attribute("schemaNamingContext",schema)));
   if(m.getName().equals("modify")) {
    writes.incrementAndGet();var req=(ModifyRequest)a[0];assertEquals(0,req.getControls().length);
    assertEquals("<GUID="+HexFormat.of().formatHex(AdGuid.bytes(guid))+">",req.getDN());
    for(var op:req.getModifications())assertNotEquals(ModificationType.REPLACE,op.getModificationType());
    if(race!=null)race.run();if(error!=null)throw new LDAPException(error,"Synthetic failure");
    var found=server.search(base,SearchScope.SUB,Filter.createEqualityFilter("objectGUID",AdGuid.bytes(guid)),"1.1");
    return server.modify(new ModifyRequest(found.getSearchEntries().get(0).getDN(),req.getModifications()));
   }
   try{return m.invoke(server,a);}catch(InvocationTargetException e){throw e.getCause();}
  });
 }
 private Api.AttributeChange change(String name,List<String> add,List<String> remove){return new Api.AttributeChange(name,add,remove);}
 private List<Api.AttributeChange> sample(){return List.of(change("otherTelephone",List.of("4321","4221"),List.of("1236","1235")),change("department",List.of("Not-Sales"),List.of("Sales")));}
 private DirectoryClient.Outcome update(List<Api.AttributeChange> c) throws Exception{return AttributeUpdates.update(ldap(null,null),id,base,c,"otherTelephone,department");}
 @Test void sampleIsAtomicPreservesUnrelatedValuesAndRetryDoesNotWrite() throws Exception {
  assertEquals("PROFILE_UPDATED",update(sample()).code());assertEquals(1,writes.get());
  assertEquals(Set.of("4321","4221","9999"),Set.of(server.getEntry(dn).getAttributeValues("otherTelephone")));
  assertEquals("Not-Sales",server.getEntry(dn).getAttributeValue("department"));
  assertEquals("PROFILE_ALREADY_SATISFIED",update(sample()).code());assertEquals(1,writes.get());
 }
 @Test void unexpectedSingleValueConflictsBeforeAnyWrite() throws Exception {
  server.modify(dn,new Modification(ModificationType.REPLACE,"department","Finance"));
  assertEquals("ATTRIBUTE_CONFLICT",assertThrows(Api.Failure.class,()->update(sample())).code);
  assertEquals(0,writes.get());assertTrue(server.getEntry(dn).hasAttributeValue("otherTelephone","1236"));
 }
 @Test void partialPriorApplicationCanBeRetriedWithoutRemovingOtherValues() throws Exception {
  server.modify(dn,new Modification(ModificationType.DELETE,"otherTelephone","1236"),new Modification(ModificationType.ADD,"otherTelephone","4321"));
  update(sample());assertEquals(Set.of("4321","4221","9999"),Set.of(server.getEntry(dn).getAttributeValues("otherTelephone")));
 }
 @Test void explicitAddToEmptyAndRemoveLastValueUseDeltas() throws Exception {
  server.modify(dn,new Modification(ModificationType.DELETE,"department"));
  update(List.of(change("department",List.of("Sales"),List.of())));
  assertEquals("Sales",server.getEntry(dn).getAttributeValue("department"));
  update(List.of(change("department",List.of(),List.of("Sales"))));
  assertNull(server.getEntry(dn).getAttribute("department"));
  assertFalse(update(List.of(change("department",List.of(),List.of("Sales")))).changed());
 }
 @Test void singleAddCannotOverwriteUnmentionedCurrentValue() {
  assertEquals("ATTRIBUTE_CONFLICT",assertThrows(Api.Failure.class,()->update(List.of(change("department",List.of("Finance"),List.of())))).code);
  assertEquals(0,writes.get());
 }
 @Test void relevantConcurrentChangeRollsBackWholeModify() throws Exception {
  Runnable race=()->{try{server.modify(dn,new Modification(ModificationType.REPLACE,"department","Finance"));}catch(Exception e){throw new RuntimeException(e);}};
  assertEquals("ATTRIBUTE_CONFLICT",assertThrows(Api.Failure.class,()->AttributeUpdates.update(ldap(race,null),id,base,sample(),"otherTelephone,department")).code);
  assertEquals("Finance",server.getEntry(dn).getAttributeValue("department"));
  assertEquals(Set.of("1236","1235","9999"),Set.of(server.getEntry(dn).getAttributeValues("otherTelephone")));assertEquals(1,writes.get());
 }
 @Test void unrelatedConcurrentMultivalueAdditionIsPreserved() throws Exception {
  Runnable race=()->{try{server.modify(dn,new Modification(ModificationType.ADD,"otherTelephone","8888"));}catch(Exception e){throw new RuntimeException(e);}};
  AttributeUpdates.update(ldap(race,null),id,base,sample(),"otherTelephone,department");
  assertEquals(Set.of("4321","4221","9999","8888"),Set.of(server.getEntry(dn).getAttributeValues("otherTelephone")));
 }
 @Test void concurrentAdditionOfRequestedValueFailsWithoutPartialChanges() throws Exception {
  Runnable race=()->{try{server.modify(dn,new Modification(ModificationType.ADD,"otherTelephone","4321"));}catch(Exception e){throw new RuntimeException(e);}};
  assertEquals("ATTRIBUTE_CONFLICT",assertThrows(Api.Failure.class,()->AttributeUpdates.update(ldap(race,null),id,base,sample(),"otherTelephone,department")).code);
  assertEquals("Sales",server.getEntry(dn).getAttributeValue("department"));assertTrue(server.getEntry(dn).hasAttributeValue("otherTelephone","1236"));
 }
 @Test void movedGuidAndDnTargetsBothWork() throws Exception {
  server.modifyDN(dn,"cn=Moved",true);update(sample());assertEquals("Not-Sales",server.getEntry("cn=Moved,"+base).getAttributeValue("department"));
  assertFalse(AttributeUpdates.update(ldap(null,null),new Api.ObjectRef("cn=Moved,"+base,null),base,sample(),"otherTelephone,department").changed());
 }
 @Test void renameAndDnReuseBetweenReadAndWriteCannotModifyReplacement() throws Exception {
  Runnable race=()->{try{server.modifyDN(dn,"cn=Moved",true);server.add(new Entry(dn,new Attribute("objectClass","user"),new Attribute("cn","Test"),new Attribute("department","Replacement")));}catch(Exception e){throw new RuntimeException(e);}};
  AttributeUpdates.update(ldap(race,null),new Api.ObjectRef(dn,null),base,sample(),"otherTelephone,department");
  assertEquals("Replacement",server.getEntry(dn).getAttributeValue("department"));assertEquals("Not-Sales",server.getEntry("cn=Moved,"+base).getAttributeValue("department"));
 }
 @Test void noWriteReplayOnAccessDeniedOrLostResponse() throws Exception {
  for(var code:new ResultCode[]{ResultCode.INSUFFICIENT_ACCESS_RIGHTS,ResultCode.TIMEOUT})
   assertEquals(code,assertThrows(LDAPException.class,()->AttributeUpdates.update(ldap(null,code),id,base,sample(),"otherTelephone,department")).getResultCode());
  assertEquals(2,writes.get());assertEquals("Sales",server.getEntry(dn).getAttributeValue("department"));
 }
 @Test void protectedAttributesAndInvalidListsAreRejected() {
  for(String name:List.of("manager","member","employeeID","userAccountControl","objectGUID","sAMAccountName","userPrincipalName","department;binary","1.2.3"))
   assertThrows(Api.Failure.class,()->update(List.of(change(name,List.of("X"),List.of()))));
  assertThrows(Api.Failure.class,()->update(List.of(change("department",List.of("Sales"),List.of("sales")))));
  assertThrows(Api.Failure.class,()->update(List.of(change("department",List.of("X","x"),List.of()))));
  assertThrows(Api.Failure.class,()->update(List.of(change("department",List.of("X"),List.of()),change("DEPARTMENT",List.of("Y"),List.of()))));
  assertThrows(Api.Failure.class,()->update(List.of(change("department",List.of(),List.of()))));
  assertThrows(Api.Failure.class,()->update(List.of(change("department",List.of(" "),List.of()))));
  assertThrows(IllegalArgumentException.class,()->AttributeUpdates.allowedNames("manager"));assertEquals(0,writes.get());
 }
 @Test void allowlistAndSchemaAreBothRequired() throws Exception {
  assertEquals("ATTRIBUTE_NOT_ALLOWED",assertThrows(Api.Failure.class,()->AttributeUpdates.update(ldap(null,null),id,base,sample(),"department")).code);
  server.delete("cn=department,"+schema);
  assertEquals("ATTRIBUTE_SCHEMA_UNAVAILABLE",assertThrows(Api.Failure.class,()->update(sample())).code);assertEquals(0,writes.get());
 }
 @Test void binaryLinkedAndSystemOnlySchemaAreRejectedEvenIfAllowed() throws Exception {
  for(var m:List.of(new Modification(ModificationType.REPLACE,"attributeSyntax","2.5.5.10"),new Modification(ModificationType.ADD,"linkID","10"),new Modification(ModificationType.REPLACE,"systemOnly","TRUE"))) {
   server.modify("cn=department,"+schema,m);
   assertEquals("INVALID_MODIFICATIONS",assertThrows(Api.Failure.class,()->update(sample())).code);
   server.modify("cn=department,"+schema,new Modification(ModificationType.REPLACE,"attributeSyntax","2.5.5.12"),new Modification(ModificationType.REPLACE,"systemOnly","FALSE"));
   if(server.getEntry("cn=department,"+schema).hasAttribute("linkID"))server.modify("cn=department,"+schema,new Modification(ModificationType.DELETE,"linkID"));
  }
 }
 @Test void comparisonsUseCaseIgnoreTextSemantics() throws Exception {
  assertFalse(update(List.of(change("department",List.of("sales"),List.of()))).changed());
  update(List.of(change("department",List.of("Not-Sales"),List.of("sales"))));assertEquals("Not-Sales",server.getEntry(dn).getAttributeValue("department"));
 }
 @Test void nonUserAndUnreadableGuidFailClosed() throws Exception {
  server.modify(dn,new Modification(ModificationType.ADD,"objectClass","computer"));assertEquals("INVALID_USER_OBJECT",assertThrows(Api.Failure.class,()->update(sample())).code);
  server.modify(dn,new Modification(ModificationType.DELETE,"objectClass","computer"),new Modification(ModificationType.DELETE,"objectGUID"));
  assertEquals("ACCOUNT_GUID_UNAVAILABLE",assertThrows(Api.Failure.class,()->AttributeUpdates.update(ldap(null,null),new Api.ObjectRef(dn,null),base,sample(),"otherTelephone,department")).code);
 }
 @Test void referenceIdentifierIsNeverAcceptedForUpdates() {
  assertEquals("INVALID_IDENTIFIER",assertThrows(Api.Failure.class,()->AttributeUpdates.update(ldap(null,null),new Api.ObjectRef(null,null,"1006"),base,sample(),"otherTelephone,department")).code);assertEquals(0,writes.get());
 }
 @Test void timeoutAfterCommittedModifyIsReconciledOnRetry() throws Exception {
  LDAPInterface lost=(LDAPInterface)Proxy.newProxyInstance(LDAPInterface.class.getClassLoader(),new Class<?>[]{LDAPInterface.class},(p,m,a)->{
   if(m.getName().equals("modify")) {
    try{m.invoke(ldap(null,null),a);}catch(InvocationTargetException e){throw e.getCause();}
    throw new LDAPException(ResultCode.TIMEOUT,"Synthetic response lost after commit");
   }
   try{return m.invoke(ldap(null,null),a);}catch(InvocationTargetException e){throw e.getCause();}
  });
  assertEquals(ResultCode.TIMEOUT,assertThrows(LDAPException.class,()->AttributeUpdates.update(lost,id,base,sample(),"otherTelephone,department")).getResultCode());
  assertEquals(1,writes.get());assertEquals("PROFILE_ALREADY_SATISFIED",update(sample()).code());assertEquals(1,writes.get());
 }
 @Test void replacementBetweenGuidLookupAndReadFailsClosed() throws Exception {
  LDAPInterface replaced=(LDAPInterface)Proxy.newProxyInstance(LDAPInterface.class.getClassLoader(),new Class<?>[]{LDAPInterface.class},(p,m,a)->{
   if(m.getName().equals("getEntry"))server.modify(dn,new Modification(ModificationType.REPLACE,"objectGUID",AdGuid.bytes("11111111-2222-3333-4444-555555555555")));
   try{return m.invoke(ldap(null,null),a);}catch(InvocationTargetException e){throw e.getCause();}
  });
  assertEquals("ACCOUNT_CHANGED",assertThrows(Api.Failure.class,()->AttributeUpdates.update(replaced,id,base,sample(),"otherTelephone,department")).code);assertEquals(0,writes.get());
 }

}
