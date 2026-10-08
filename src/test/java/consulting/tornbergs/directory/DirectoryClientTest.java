package consulting.tornbergs.directory;

import com.unboundid.ldap.listener.*;
import com.unboundid.ldap.sdk.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class DirectoryClientTest {
 private InMemoryDirectoryServer server;
 private final DirectoryClient client=new DirectoryClient();
 private final String user="cn=Anna,dc=example,dc=com",group="cn=Store,dc=example,dc=com";
 @BeforeEach void setup() throws Exception {
  var cfg=new InMemoryDirectoryServerConfig("dc=example,dc=com");cfg.setSchema(null);
  server=new InMemoryDirectoryServer(cfg);
  server.add("dn: dc=example,dc=com","objectClass: domain","dc: example");
  server.add("dn: "+user,"objectClass: user","cn: Anna");
  server.add("dn: cn=Other,dc=example,dc=com","objectClass: user","cn: Other");
  server.add("dn: "+group,"objectClass: group","cn: Store","member: cn=Other,dc=example,dc=com");
 }
 @AfterEach void stop(){server.shutDown(true);}
 @Test void repeatedAddAndRemovePreserveOtherMembers() throws Exception {
  assertTrue(client.update(server,user,group,true).changed());
  assertEquals("ALREADY_MEMBER",client.update(server,user,group,true).code());
  assertTrue(client.update(server,user,group,false).changed());
  assertEquals("NOT_MEMBER",client.update(server,user,group,false).code());
  assertTrue(server.compare(group,"member","cn=Other,dc=example,dc=com").compareMatched());
 }
 @Test void nestedMembershipDoesNotCountAsDirect() throws Exception {
  String nested="cn=Nested,dc=example,dc=com";
  server.add("dn: "+nested,"objectClass: group","cn: Nested","member: "+user);
  server.modify(group,new Modification(ModificationType.ADD,"member",nested));
  assertEquals("NOT_MEMBER",client.update(server,user,group,false).code());
  assertTrue(client.update(server,user,group,true).changed());
  assertTrue(server.compare(group,"member",nested).compareMatched());
 }
 @Test void missingUserIsNotSuccessfulRemoval(){
  Api.Failure e=assertThrows(Api.Failure.class,()->client.update(server,"cn=Missing,dc=example,dc=com",group,false));
  assertEquals(404,e.status);
 }
 @Test void rejectNonGroup(){
  assertEquals("INVALID_GROUP_OBJECT",assertThrows(Api.Failure.class,()->client.update(server,user,user,true)).code);
 }
 @Test void rejectComputer() throws Exception {
  server.modify(user,new Modification(ModificationType.ADD,"objectClass","computer"));
  assertEquals("INVALID_USER_OBJECT",assertThrows(Api.Failure.class,()->client.update(server,user,group,true)).code);
 }

 private LDAPInterface simulateAbsentMemberCompare() {
  return (LDAPInterface)java.lang.reflect.Proxy.newProxyInstance(LDAPInterface.class.getClassLoader(),new Class<?>[]{LDAPInterface.class},(proxy,method,args)->{
   if (method.getName().equals("compare") && server.getEntry(group,"member").getAttribute("member")==null)
    throw new LDAPException(ResultCode.NO_SUCH_ATTRIBUTE,"Simulated AD empty member attribute");
   try { return method.invoke(server,args); }
   catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
  });
 }
 @Test void addToEmptyGroupWhenAdCompareReturns16() throws Exception {
  server.modify(group,new Modification(ModificationType.DELETE,"member"));
  LDAPInterface ad=simulateAbsentMemberCompare();
  assertTrue(client.update(ad,user,group,true).changed());
  assertEquals("ALREADY_MEMBER",client.update(ad,user,group,true).code());
  assertTrue(client.update(ad,user,group,false).changed());
  assertEquals("NOT_MEMBER",client.update(ad,user,group,false).code());
 }
 @Test void removalFromEmptyGroupWhenAdCompareReturns16() throws Exception {
  server.modify(group,new Modification(ModificationType.DELETE,"member"));
  assertEquals("NOT_MEMBER",client.update(simulateAbsentMemberCompare(),user,group,false).code());
 }
 @Test void compareAccessDeniedIsNotSuppressed() {
  LDAPInterface denied=(LDAPInterface)java.lang.reflect.Proxy.newProxyInstance(LDAPInterface.class.getClassLoader(),new Class<?>[]{LDAPInterface.class},(proxy,method,args)->{
   if (method.getName().equals("compare")) throw new LDAPException(ResultCode.INSUFFICIENT_ACCESS_RIGHTS,"Denied");
   try { return method.invoke(server,args); }
   catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
  });
  assertEquals(ResultCode.INSUFFICIENT_ACCESS_RIGHTS,assertThrows(LDAPException.class,()->client.update(denied,user,group,false)).getResultCode());
 }

 @Test void guidEncodingMatchesAdBinaryLayout() {
  assertArrayEquals(new byte[]{0x33,0x22,0x11,0,0x55,0x44,0x77,0x66,(byte)0x88,(byte)0x99,(byte)0xaa,(byte)0xbb,(byte)0xcc,(byte)0xdd,(byte)0xee,(byte)0xff},AdGuid.bytes("00112233-4455-6677-8899-aabbccddeeff"));
 }
 @Test void guidResolvesRenamedUserAndGroupThenChangesMembership() throws Exception {
  String u="00112233-4455-6677-8899-aabbccddeeff",g="11223344-5566-7788-99aa-bbccddeeff00";
  server.modify(user,new Modification(ModificationType.ADD,"objectGUID",AdGuid.bytes(u)));
  server.modify(group,new Modification(ModificationType.ADD,"objectGUID",AdGuid.bytes(g)));
  server.modifyDN(user,"cn=Renamed",true);
  String current=client.resolve(server,new Api.ObjectRef(null,u),"dc=example,dc=com");
  String resolvedGroup=client.resolve(server,new Api.ObjectRef(null,g),"dc=example,dc=com");
  assertEquals("cn=Renamed,dc=example,dc=com",current);
  assertTrue(client.update(server,current,resolvedGroup,true).changed());
  assertEquals("ALREADY_MEMBER",client.update(server,current,resolvedGroup,true).code());
  assertTrue(client.update(server,current,resolvedGroup,false).changed());
 }
 @Test void missingGuidDoesNotChangeMembership() {
  assertEquals("OBJECT_NOT_FOUND",assertThrows(Api.Failure.class,()->client.resolve(server,new Api.ObjectRef(null,"00112233-4455-6677-8899-aabbccddeeff"),"dc=example,dc=com")).code);
 }
 @Test void guidOutsideSearchBaseIsNotFound() throws Exception {
  server.modify(user,new Modification(ModificationType.ADD,"objectGUID",AdGuid.bytes("00112233-4455-6677-8899-aabbccddeeff")));
  assertEquals(404,assertThrows(Api.Failure.class,()->client.resolve(server,new Api.ObjectRef(null,"00112233-4455-6677-8899-aabbccddeeff"),group)).status);
 }
}
