// Copyright 2026 Tornbergs Consulting AB
// SPDX-License-Identifier: Apache-2.0
package consulting.tornbergs.directory;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
@Path("/health") public class HealthResource {
 @GET @Produces(MediaType.APPLICATION_JSON) public java.util.Map<String,String> health() {
  return java.util.Map.of("status","UP","version","1.3.1-SNAPSHOT","ldap","not checked");
 }
}
