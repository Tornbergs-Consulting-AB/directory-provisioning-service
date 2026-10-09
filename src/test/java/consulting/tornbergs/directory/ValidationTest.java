// Copyright 2026 Marcus Tornberg
// SPDX-License-Identifier: Apache-2.0
package consulting.tornbergs.directory;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
class ValidationTest {
 @Test void basicPasswordMayContainColon(){
  String v="Basic "+Base64.getEncoder().encodeToString("cn=svc,dc=example:p:a:ss".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  assertArrayEquals(new String[]{"cn=svc,dc=example","p:a:ss"},ProvisioningResource.credentials(v));
 }
 @Test void rejectEmptyPassword(){
  String v="Basic "+Base64.getEncoder().encodeToString("svc:".getBytes());
  assertEquals(401,assertThrows(Api.Failure.class,()->ProvisioningResource.credentials(v)).status);
 }
 @Test void rejectMalformedGuid(){
  var r=new Api.MembershipRequest("81",null,"DEMO","ADD_PERMISSION_TO_USER",new Api.ObjectRef(null,"guid"),new Api.ObjectRef("cn=g,dc=e",null));
  assertEquals("INVALID_IDENTIFIER",assertThrows(Api.Failure.class,()->ProvisioningResource.validate(r)).code);
 }
 @Test void rejectAccountCreation(){
  var r=new Api.MembershipRequest("81",null,"DEMO","ADD_APPLICATION_TO_USER",new Api.ObjectRef("cn=u,dc=e",null),new Api.ObjectRef("cn=g,dc=e",null));
  assertEquals("UNSUPPORTED_OPERATION",assertThrows(Api.Failure.class,()->ProvisioningResource.validate(r)).code);
 }

 @Test void acceptGuidAndMixedIdentifiers(){
  ProvisioningResource.validate(new Api.MembershipRequest("81",null,"DEMO","ADD_PERMISSION_TO_USER",new Api.ObjectRef(null,"00112233-4455-6677-8899-aabbccddeeff"),new Api.ObjectRef("cn=g,dc=e",null)));
 }
 @Test void rejectBothIdentifiers(){
  assertThrows(Api.Failure.class,()->ProvisioningResource.validate(new Api.MembershipRequest("81",null,"DEMO","ADD_PERMISSION_TO_USER",new Api.ObjectRef("cn=u,dc=e","00112233-4455-6677-8899-aabbccddeeff"),new Api.ObjectRef("cn=g,dc=e",null))));
 }
}
