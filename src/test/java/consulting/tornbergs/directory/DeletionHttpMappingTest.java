// Copyright 2026 Tornbergs Consulting AB
// SPDX-License-Identifier: Apache-2.0
package consulting.tornbergs.directory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.http.HttpServerRequest;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Resource-level envelope/dispatch check; LDAP behavior is verified separately. */
class DeletionHttpMappingTest {
 private final String guid="86613765-80db-4d5e-a857-dced76823650";
 private ProvisioningResource resource(AtomicInteger calls) {
  var resource=new ProvisioningResource();resource.mapper=new ObjectMapper();
  resource.http=(HttpServerRequest)Proxy.newProxyInstance(HttpServerRequest.class.getClassLoader(),new Class<?>[]{HttpServerRequest.class},(p,m,a)->{
   if(m.getName().equals("isSSL"))return true;throw new UnsupportedOperationException(m.getName());
  });
  resource.ldap=new LdapService(){
   @Override public DirectoryClient.Outcome update(Api.ProvisioningRequest r,String user,String password,String correlation){
    calls.incrementAndGet();assertEquals("REMOVE_ACCOUNT",r.requestType());assertEquals(guid,r.account().guid());
    assertNull(r.user());assertNull(r.group());
    return new DirectoryClient.Outcome(true,"ACCOUNT_DELETED","cn=Account,dc=example,dc=com",null);
   }
  };
  return resource;
 }
 private String auth(){return "Basic "+Base64.getEncoder().encodeToString("cn=svc,dc=example:synthetic".getBytes(StandardCharsets.UTF_8));}
 @Test void accountPayloadIsParsedAndCommentDoesNotMentionPermission() {
  var calls=new AtomicInteger();
  var response=resource(calls).update("{\"changeItemId\":\"114\",\"target\":\"DEMO\",\"requestType\":\"REMOVE_ACCOUNT\",\"account\":{\"guid\":\""+guid+"\"}}",auth(),"iga-114");
  assertEquals(200,response.getStatus());assertEquals(1,calls.get());
  var reply=(Api.Reply)response.getEntity();assertEquals("ACCOUNT_DELETED",reply.code());assertEquals("iga-114",reply.fulfillmentId());
  assertTrue(reply.comment().contains("Change Item '114' Fulfilled. Type: REMOVE_ACCOUNT"));assertFalse(reply.comment().contains("Target Permission"));assertNull(reply.groupDN());
 }
 @Test void discoveryEnvelopeDoesNotDelete() {
  var calls=new AtomicInteger();
  var response=resource(calls).update("{\"changeItemId\":\"114\",\"target\":\"DEMO\",\"requestType\":\"REMOVE_ACCOUNT\",\"context\":{\"account\":\""+guid+"\"}}",auth(),"iga-114");
  assertEquals(400,response.getStatus());assertEquals(0,calls.get());
 }
 @Test void missingAuthenticationDoesNotDelete() {
  var calls=new AtomicInteger();
  var response=resource(calls).update("{\"changeItemId\":\"114\",\"target\":\"DEMO\",\"requestType\":\"REMOVE_ACCOUNT\",\"account\":{\"guid\":\""+guid+"\"}}",null,"iga-114");
  assertEquals(401,response.getStatus());assertEquals(0,calls.get());
 }
}
