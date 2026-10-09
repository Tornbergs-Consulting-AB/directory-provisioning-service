// Copyright 2026 Marcus Tornberg
// SPDX-License-Identifier: Apache-2.0
package consulting.tornbergs.directory;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
@QuarkusTest class HttpContractTest {
 @Test void healthDoesNotClaimLdapReady(){
  given().get("/health").then().statusCode(200).body("ldap",equalTo("not checked"),"version",equalTo("1.0.0"));
 }
 @Test void rejectPlaintextRealOperation(){
  given().contentType("application/json").header("X-Correlation-ID","iga-81")
   .body("{\"changeItemId\":\"81\",\"target\":\"DEMO\"}")
   .post("/api/v1/provisioning").then().statusCode(400).body("code",equalTo("HTTPS_REQUIRED"));
 }
 @Test void connectionTestRequiresTls(){given().get("/api/v1/provisioning").then().statusCode(400).body("code",equalTo("HTTPS_REQUIRED"));}
 @Test void probesAreDisabled(){given().get("/test/responses/success").then().statusCode(404);}
}
