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

class ProfileHttpMappingTest {
 private final String guid="35c3d1fc-6e13-493e-9501-0d103bcc344f";
 private final String payload="{\"changeItemId\":\"174\",\"target\":\"DEMO\",\"requestType\":\"MODIFY_USER_PROFILE\",\"user\":{\"guid\":\""+guid+"\"},\"modifications\":[{\"attribute\":\"department\",\"add\":[\"Not-Sales\"],\"remove\":[\"Sales\"]}]}";
 private String auth(){return "Basic "+Base64.getEncoder().encodeToString("cn=svc,dc=example:synthetic".getBytes(StandardCharsets.UTF_8));}
 private ProvisioningResource resource(AtomicInteger calls,boolean changed,Api.Failure failure) {
  var r=new ProvisioningResource();r.mapper=new ObjectMapper();
  r.http=(HttpServerRequest)Proxy.newProxyInstance(HttpServerRequest.class.getClassLoader(),new Class<?>[]{HttpServerRequest.class},(p,m,a)->{if(m.getName().equals("isSSL"))return true;throw new UnsupportedOperationException(m.getName());});
  r.ldap=new LdapService(){@Override public DirectoryClient.Outcome update(Api.ProvisioningRequest q,String u,String p,String c){
   calls.incrementAndGet();assertEquals("MODIFY_USER_PROFILE",q.requestType());assertEquals(guid,q.user().guid());
   assertEquals("Not-Sales",q.modifications().get(0).add().get(0));assertNull(q.group());assertNull(q.account());
   if(failure!=null)throw failure;
   return new DirectoryClient.Outcome(changed,changed?"PROFILE_UPDATED":"PROFILE_ALREADY_SATISFIED","cn=Test,dc=example,dc=com",null);
  }};return r;
 }
 @Test void successAndRetryHaveProfileSpecificCommentsAndIds() {
  for(boolean changed:new boolean[]{true,false}) {
   var calls=new AtomicInteger();var response=resource(calls,changed,null).update(payload,auth(),"iga-174");
   assertEquals(200,response.getStatus());assertEquals(1,calls.get());var reply=(Api.Reply)response.getEntity();
   assertEquals("iga-174",reply.fulfillmentId());assertTrue(reply.comment().contains("MODIFY_USER_PROFILE"));assertFalse(reply.comment().contains("Target Permission"));
   assertEquals(changed?"CHANGED":"UNCHANGED",reply.outcome());assertNull(reply.groupDN());
  }
 }
 @Test void badIdentifiersAndMixedOperationFieldsNeverReachLdap() {
  for(String invalid:new String[]{payload.replace("\"guid\":\""+guid+"\"","\"reference\":\"1006\""),payload.replace("\"modifications\":","\"group\":{\"dn\":\"cn=Group,dc=example\"},\"modifications\":"),payload.replace("\"add\":[\"Not-Sales\"]","\"add\":[123]"),payload.replace("\"add\":[\"Not-Sales\"]","\"add\":[true]"),payload.replace("\"add\":[\"Not-Sales\"]","\"add\":[null]"),payload.replace("\"add\":[\"Not-Sales\"]","\"add\":[[\"Not-Sales\"]]"),payload.replace("\"department\"","\"manager\"")}) {
   var calls=new AtomicInteger();assertEquals(400,resource(calls,true,null).update(invalid,auth(),"iga-174").getStatus());assertEquals(0,calls.get());
  }
 }
 @Test void missingAuthenticationCannotUpdate() {
  var calls=new AtomicInteger();assertEquals(401,resource(calls,true,null).update(payload,null,"iga-174").getStatus());assertEquals(0,calls.get());
 }
 @Test void conflictAndDeniedResponsesDoNotContainSuccessfulReceipt() {
  for(var error:new Api.Failure[]{new Api.Failure(409,"ATTRIBUTE_CONFLICT","Conflicting values."),new Api.Failure(403,"ACCESS_DENIED","AD denied the operation.")}) {
   var calls=new AtomicInteger();var response=resource(calls,true,error).update(payload,auth(),"iga-174");
   assertEquals(error.status,response.getStatus());var reply=(Api.Reply)response.getEntity();assertNull(reply.fulfillmentId());assertEquals("FAILED",reply.outcome());assertEquals(error.code,reply.code());
  }
 }
 @Test void otherOperationCannotCarryModifications() {
  var calls=new AtomicInteger();assertEquals(400,resource(calls,true,null).update(payload.replace("MODIFY_USER_PROFILE","REMOVE_ACCOUNT"),auth(),"iga-174").getStatus());assertEquals(0,calls.get());
 }
}
