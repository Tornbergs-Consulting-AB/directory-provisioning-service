// Copyright 2026 Tornbergs Consulting AB
// SPDX-License-Identifier: Apache-2.0
package consulting.tornbergs.directory;

import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.jboss.logging.Logger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

@Path("/api/v1/provisioning")
@Produces(MediaType.APPLICATION_JSON)
public class ProvisioningResource {
    private static final Logger LOG=Logger.getLogger(ProvisioningResource.class);
    @Inject LdapService ldap;
    @Inject HttpServerRequest http;
    @Inject com.fasterxml.jackson.databind.ObjectMapper mapper;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="dps.log-request-payload") boolean logPayload;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="dps.log-request-payload-max-chars") int maxPayloadChars;

    @GET
    public Response connectionTest(@HeaderParam("Authorization") String auth) {
        String correlation=UUID.randomUUID().toString();
        try {
            if (!http.isSSL()) throw new Api.Failure(400,"HTTPS_REQUIRED","HTTPS is required.");
            String[] creds=credentials(auth);
            ldap.checkConnection(creds[0],creds[1],correlation);
            LOG.infof("request=%s operation=connection-test status=200",correlation);
            return response(200,java.util.Map.of("status","DIRECTORY_READY","comment","LDAP TLS connection and bind succeeded."),correlation);
        } catch (Api.Failure e) {
            LOG.warnf("request=%s operation=connection-test status=%d code=%s",correlation,e.status,e.code);
            return response(e.status,new Api.Reply(null,correlation,null,"FAILED",e.code,e.getMessage(),null,null),correlation);
        }
    }

    @POST @Consumes(MediaType.APPLICATION_JSON)
    public Response update(String rawBody, @HeaderParam("Authorization") String auth,
                           @HeaderParam("X-Correlation-ID") String header) {
        String correlation=header!=null && header.matches("[A-Za-z0-9._:-]{1,128}")?header:UUID.randomUUID().toString();
        Api.ProvisioningRequest request=null;
        String item=null;
        long started=System.nanoTime(); int status=500;
        try {
            if (!http.isSSL()) throw new Api.Failure(400,"HTTPS_REQUIRED","HTTPS is required for real LDAP credentials.");
            if (logPayload && LOG.isDebugEnabled())
                LOG.debugf("request=%s payload=%s",correlation,RequestPayloadLog.render(mapper,rawBody,maxPayloadChars));
            try {
                var reader=mapper.readerFor(Api.ProvisioningRequest.class)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
                request=reader.readValue(rawBody==null?"":rawBody);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new Api.Failure(400,"INVALID_JSON","Invalid JSON or unexpected payload field types.");
            }
            item=request!=null?request.changeItemId():null;
            if (item!=null && !item.matches("[0-9]{1,20}")) item=null;
            validate(request);
            String[] credentials=credentials(auth);
            DirectoryClient.Outcome result=ldap.update(request,credentials[0],credentials[1],correlation);
            boolean deleting=isDeletion(request.requestType());
            boolean creating=request.requestType().equals("ADD_APPLICATION_TO_USER");
            String suffix=result.changed()?"":" No change required: "+(result.code().equals("ALREADY_MEMBER")?"already a direct member.":"not a direct member.");
            String comment="Change Item '"+item+"' Fulfilled. Type: "+request.requestType()+", Target Account: "+result.userDN()+(creating?(result.changed()?". Disabled account created.":". No change required: matching disabled account already exists.")+" Account name: "+result.accountName()+", UPN: "+result.upn():
                    deleting?". Account deleted.":", Target Permission: "+result.groupDN()+suffix);
            var reply=new Api.Reply(item,correlation,"iga-"+item,result.changed()?"CHANGED":"UNCHANGED",result.code(),comment,result.userDN(),result.groupDN(),result.accountName(),result.upn());
            status=200;
            LOG.infof("request=%s changeItem=%s operation=%s outcome=%s code=%s",correlation,item,request.requestType(),result.changed()?"CHANGED":"UNCHANGED",result.code());
            return response(status,reply,correlation);
        } catch (Api.Failure e) {
            status=e.status;
            LOG.warnf("request=%s changeItem=%s code=%s",correlation,item,e.code);
            String comment="Change Item '"+(item==null?"unknown":item)+"' Failed. "+e.getMessage();
            return response(status,new Api.Reply(item,correlation,null,"FAILED",e.code,comment,null,null),correlation);
        } finally {
            LOG.infof("request=%s changeItem=%s status=%d durationMs=%d",correlation,item,status,(System.nanoTime()-started)/1_000_000);
        }
    }
    static void validate(Api.ProvisioningRequest r) {
        if (r==null || r.changeItemId()==null || !r.changeItemId().matches("[0-9]{1,20}") || r.target()==null)
            throw new Api.Failure(400,"INVALID_REQUEST","changeItemId and target are required.");
        if (r.requestType()==null || !Set.of("ADD_PERMISSION_TO_USER","REMOVE_ACCOUNT_PERMISSION","REMOVE_PERMISSION_ASSIGNMENT","REMOVE_ACCOUNT","REMOVE_APPLICATION_FROM_USER","ADD_APPLICATION_TO_USER").contains(r.requestType()))
            throw new Api.Failure(400,"UNSUPPORTED_OPERATION","Unsupported requestType.");
        if(r.requestType().equals("ADD_APPLICATION_TO_USER")) {
            if(r.user()!=null || r.group()!=null || r.account()!=null || r.creation()==null)
                throw new Api.Failure(400,"INVALID_REQUEST","Account creation requires creation only; omit user, group and account.");
            return;
        }
        if(r.creation()!=null)throw new Api.Failure(400,"INVALID_REQUEST","creation is valid only for ADD_APPLICATION_TO_USER.");
        if (isDeletion(r.requestType())) {
            if (r.user()!=null || r.group()!=null)
                throw new Api.Failure(400,"INVALID_REQUEST","Account deletion requires account only; omit user and group.");
            validateRef(r.account());
            if(r.account().reference()!=null) throw new Api.Failure(400,"INVALID_IDENTIFIER","Account deletion requires an explicit dn or guid; references are not allowed.");
        } else {
            if (r.account()!=null) throw new Api.Failure(400,"INVALID_REQUEST","Membership operations require user and group; omit account.");
            validateRef(r.user()); validateRef(r.group());
            if(r.user().reference()!=null && !"ADD_PERMISSION_TO_USER".equals(r.requestType()))
                throw new Api.Failure(400,"INVALID_IDENTIFIER","Account references are allowed only for membership additions, not removals.");
            if(r.group().reference()!=null) throw new Api.Failure(400,"INVALID_IDENTIFIER","Groups require dn or guid, not an account reference.");
        }
    }
    static boolean isDeletion(String type) { return "REMOVE_ACCOUNT".equals(type) || "REMOVE_APPLICATION_FROM_USER".equals(type); }
    private static void validateRef(Api.ObjectRef r) {
        if (r==null || (r.dn()!=null?1:0)+(r.guid()!=null?1:0)+(r.reference()!=null?1:0)!=1)
            throw new Api.Failure(400,"INVALID_IDENTIFIER","Supply exactly one of dn, guid or reference for each account/user.");
        if(r.reference()!=null) {
            if(r.reference().isBlank() || r.reference().length()>16 || r.reference().chars().anyMatch(c->Character.isISOControl(c)))
                throw new Api.Failure(400,"INVALID_REFERENCE","employeeID reference must contain 1 to 16 characters without control characters.");
            return;
        }
        if (r.guid()!=null) {
            AdGuid.bytes(r.guid());
            return;
        }
        if (r.dn().length()>4096 || r.dn().chars().anyMatch(c->c<32))
            throw new Api.Failure(400,"INVALID_DN","Invalid DN.");
        try { if (new DN(r.dn()).isNullDN()) throw new Api.Failure(400,"INVALID_DN","Empty DN is not allowed."); }
        catch (LDAPException e) { throw new Api.Failure(400,"INVALID_DN","Invalid DN."); }
    }
    static String[] credentials(String auth) {
        try {
            if (auth==null || !auth.regionMatches(true,0,"Basic ",0,6)) throw new IllegalArgumentException();
            String raw=new String(Base64.getDecoder().decode(auth.substring(6)),StandardCharsets.UTF_8);
            int colon=raw.indexOf(':');
            if (colon<1 || colon==raw.length()-1 || raw.indexOf('\u0000')>=0) throw new IllegalArgumentException();
            return new String[]{raw.substring(0,colon),raw.substring(colon+1)};
        } catch (IllegalArgumentException e) { throw new Api.Failure(401,"BASIC_REQUIRED","Nonempty Basic credentials are required."); }
    }
    static Response response(int status,Object body,String correlation) {
        var builder=Response.status(status).entity(body).header("X-Correlation-ID",correlation).header("Cache-Control","no-store");
        if (status==401) builder.header("WWW-Authenticate","Basic realm=\"directory-provisioning-service\", charset=\"UTF-8\"");
        return builder.build();
    }
}
