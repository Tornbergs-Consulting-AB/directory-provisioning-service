// Copyright 2026 Marcus Tornberg
// SPDX-License-Identifier: Apache-2.0
package consulting.tornbergs.directory;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.UUID;

@Path("/test/responses") @Produces(MediaType.APPLICATION_JSON)
public class ProbeResource {
    @ConfigProperty(name="dps.probe-enabled") boolean enabled;
    @POST @Path("/{scenario}") @Consumes(MediaType.APPLICATION_JSON)
    public Response probe(@PathParam("scenario") String scenario, Api.MembershipRequest r,
                          @HeaderParam("Authorization") String auth,@HeaderParam("X-Correlation-ID") String header) {
        String id=header!=null && header.matches("[A-Za-z0-9._:-]{1,128}")?header:UUID.randomUUID().toString();
        if (!enabled) return ProvisioningResource.response(404,null,id);
        ProvisioningResource.credentials(auth); // synthetic credential syntax only; no LDAP bind
        int status=switch(scenario) {
            case "success","already-member","not-member" -> 200;
            case "bad-request" -> 400; case "unauthorized" -> 401; case "forbidden" -> 403;
            case "unavailable" -> 503; case "internal-error" -> 500; default -> 404;
        };
        String item=r!=null?r.changeItemId():null;
        if (item==null || !item.matches("[0-9]{1,20}")) item="unknown";
        String comment="Change Item '"+item+"' "+(status==200?"Fulfilled":"Failed")+". SIMULATED: "+scenario;
        return ProvisioningResource.response(status,new Api.Reply(item,id,"probe-"+id,status==200?(scenario.equals("success")?"CHANGED":"UNCHANGED"):"FAILED",scenario,comment,null,null),id);
    }
    @GET @Path("/{scenario}")
    public Response connectionTest(@PathParam("scenario") String scenario) {
        return enabled?Response.ok(java.util.Map.of("status","PROBE_READY","simulated",true)).build():Response.status(404).build();
    }
}
