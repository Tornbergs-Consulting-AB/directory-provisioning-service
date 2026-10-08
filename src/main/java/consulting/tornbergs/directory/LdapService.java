package consulting.tornbergs.directory;

import com.unboundid.ldap.sdk.*;
import com.unboundid.util.ssl.*;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import javax.net.ssl.SSLSocketFactory;
import java.util.Optional;
import java.util.concurrent.Semaphore;

@ApplicationScoped
public class LdapService {
    private static final Logger LOG=Logger.getLogger(LdapService.class);
    @ConfigProperty(name="dps.target-name") String target;
    @ConfigProperty(name="dps.search-base") String searchBase;
    @ConfigProperty(name="dps.ldap-host") String host;
    @ConfigProperty(name="dps.ldap-port") int port;
    @ConfigProperty(name="dps.allowed-bind-dn") String allowed;
    @ConfigProperty(name="dps.truststore") Optional<String> truststore;
    @ConfigProperty(name="dps.truststore-password") Optional<String> trustPassword;
    @ConfigProperty(name="dps.connect-timeout-ms") int connectTimeout;
    @ConfigProperty(name="dps.operation-timeout-ms") int operationTimeout;
    @ConfigProperty(name="dps.max-concurrent-requests") int maxConcurrent;
    private volatile Semaphore slots;
    private volatile SSLSocketFactory sockets;

    private synchronized Semaphore slots() {
        if (slots==null) {
            if (maxConcurrent<1) throw new Api.Failure(500,"INVALID_CONFIGURATION","Concurrency must be positive.");
            slots=new Semaphore(maxConcurrent);
        }
        return slots;
    }
    private synchronized SSLSocketFactory sockets() throws Exception {
        if (sockets==null) {
            if (truststore.isEmpty() || trustPassword.isEmpty())
                throw new Api.Failure(503,"TLS_NOT_CONFIGURED","LDAP truststore is not configured.");
            sockets=new SSLUtil(new TrustStoreTrustManager(truststore.get(),trustPassword.get().toCharArray(),"PKCS12",true)).createSSLSocketFactory();
        }
        return sockets;
    }
    public DirectoryClient.Outcome update(Api.MembershipRequest request, String username, String password, String correlation) {
        return execute(request,username,password,correlation);
    }
    public void checkConnection(String username, String password, String correlation) {
        execute(null,username,password,correlation);
    }
    private DirectoryClient.Outcome execute(Api.MembershipRequest request, String username, String password, String correlation) {
        if (request!=null && !target.equals(request.target())) throw new Api.Failure(400,"UNKNOWN_TARGET","Unknown target.");
        try {
            if (!new DN(allowed).equals(new DN(username)))
                throw new Api.Failure(403,"BIND_PRINCIPAL_NOT_ALLOWED","Bind principal is not allowed for this target.");
        } catch (LDAPException e) { throw new Api.Failure(403,"BIND_PRINCIPAL_NOT_ALLOWED","Use the configured bind DN."); }
        Semaphore limit=slots();
        if (!limit.tryAcquire()) throw new Api.Failure(503,"SERVICE_BUSY","Concurrent request limit reached; retry later.");
        String phase="connect";
        try {
            LDAPConnectionOptions options=new LDAPConnectionOptions();
            options.setConnectTimeoutMillis(connectTimeout);
            options.setResponseTimeoutMillis(operationTimeout);
            options.setFollowReferrals(false);
            options.setSSLSocketVerifier(new HostNameSSLSocketVerifier(true));
            try (LDAPConnection connection=new LDAPConnection(sockets(), options)) {
                connection.connect(host,port);
                phase="bind";
                connection.bind(username,password);
                if (request==null) return null;
                phase="resolve";
                DirectoryClient client=new DirectoryClient();
                String userDN=client.resolve(connection,request.user(),searchBase);
                String groupDN=client.resolve(connection,request.group(),searchBase);
                phase="membership";
                return client.update(connection,userDN,groupDN,request.requestType().equals("ADD_PERMISSION_TO_USER"));
            }
        } catch (Api.Failure e) { throw e; }
        catch (LDAPException e) {
            LOG.warnf("request=%s phase=%s ldapResult=%d",correlation,phase,e.getResultCode().intValue());
            if (phase.equals("bind") && e.getResultCode()==ResultCode.INVALID_CREDENTIALS)
                throw new Api.Failure(401,"INVALID_CREDENTIALS","AD authentication failed.");
            if (e.getResultCode()==ResultCode.INSUFFICIENT_ACCESS_RIGHTS)
                throw new Api.Failure(403,"ACCESS_DENIED","AD denied the operation.");
            if (e.getResultCode()==ResultCode.NO_SUCH_OBJECT)
                throw new Api.Failure(404,"OBJECT_NOT_FOUND","Directory object was not found.");
            if (e.getResultCode()==ResultCode.CONNECT_ERROR || e.getResultCode()==ResultCode.SERVER_DOWN
                || e.getResultCode()==ResultCode.TIMEOUT || e.getResultCode()==ResultCode.UNAVAILABLE || e.getResultCode()==ResultCode.BUSY)
                throw new Api.Failure(503,"DIRECTORY_UNAVAILABLE","Directory connection or operation failed; check service logs and retry.");
            throw new Api.Failure(502,"LDAP_OPERATION_FAILED","AD rejected the operation. LDAP result code: "+e.getResultCode().intValue());
        } catch (Exception e) {
            // Do not log exception messages or request objects: they can contain sensitive data.
            LOG.errorf("request=%s phase=%s exceptionType=%s",correlation,phase,e.getClass().getSimpleName());
            throw new Api.Failure(503,"DIRECTORY_CONFIGURATION_ERROR","Directory TLS or connection configuration failed; check service configuration.");
        } finally { limit.release(); }
    }
}
