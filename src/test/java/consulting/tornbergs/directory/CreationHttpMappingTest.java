// Copyright 2026 Tornbergs Consulting AB
// SPDX-License-Identifier: Apache-2.0
package consulting.tornbergs.directory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.http.HttpServerRequest;
import java.lang.reflect.Proxy;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CreationHttpMappingTest {
 private ProvisioningResource resource(boolean changed){
  var r=new ProvisioningResource();r.mapper=new ObjectMapper();
  r.http=(HttpServerRequest)Proxy.newProxyInstance(HttpServerRequest.class.getClassLoader(),new Class<?>[]{HttpServerRequest.class},(p,m,a)->{
   if(m.getName().equals("isSSL"))return true;throw new UnsupportedOperationException();
  });
  r.ldap=new LdapService(){@Override public DirectoryClient.Outcome update(Api.ProvisioningRequest request,String u,String p,String correlation){
   assertEquals("123456-EMP",request.creation().reference());assertNull(request.account());assertNull(request.group());assertNull(request.user());
   return new DirectoryClient.Outcome(changed,changed?"ACCOUNT_CREATED":"ACCOUNT_ALREADY_EXISTS","cn=Daniel,ou=Users,dc=example,dc=com",null,"dper1","dper1@example.com");
  }};return r;
 }
 private String body(){return "{\"changeItemId\":\"116\",\"target\":\"DEMO\",\"requestType\":\"ADD_APPLICATION_TO_USER\",\"creation\":{\"reference\":\"123456-EMP\",\"containerDN\":\"ou=Users,dc=example,dc=com\",\"cn\":\"Daniel\",\"attributes\":{\"sAMAccountName\":\"dper\"}}}";}
 private String auth(){return "Basic "+Base64.getEncoder().encodeToString("cn=svc,dc=example:synthetic".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 @Test void creationReturnsSelectedNamesAndExistingMappingsStillWork(){
  for(boolean changed:new boolean[]{true,false}) {
   var response=resource(changed).update(body(),auth(),"iga-116");assertEquals(200,response.getStatus());
   var reply=(Api.Reply)response.getEntity();assertEquals("iga-116",reply.fulfillmentId());assertEquals(changed?"CHANGED":"UNCHANGED",reply.outcome());
   assertEquals("dper1",reply.accountName());assertEquals("dper1@example.com",reply.userPrincipalName());assertTrue(reply.comment().contains("dper1"));
   assertFalse(reply.comment().contains("Target Permission"));assertFalse(reply.comment().contains("not a direct member"));
  }
 }
 @Test void creationCannotCarryAnExistingAccount(){
  assertThrows(Api.Failure.class,()->ProvisioningResource.validate(new Api.ProvisioningRequest("116",null,"DEMO","ADD_APPLICATION_TO_USER",null,null,new Api.ObjectRef("cn=a,dc=e",null),new Api.Creation("1-EMP","dc=e","A",java.util.Map.of()))));
 }
}
