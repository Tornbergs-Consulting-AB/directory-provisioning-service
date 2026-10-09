# Operations — 0.5.0

## Startup and logs

Production startup validation is enabled by default. It checks target format, nonempty valid bind/search DNs, positive timeouts/concurrency, port range, disabled HTTP, readable HTTPS keystore, and a loadable nonempty PKCS12 LDAP truststore. Quarkus loads the HTTPS keystore. It performs no LDAP bind at startup because credentials belong to individual requests. A failed check prevents startup; diagnostics omit configuration values and exception causes. `dps.startup-validation=false` is for isolated local test runs only. The probe remains disabled.

INFO success logs include correlation ID, change item, request type, outcome and code. DEBUG logs include identifier type (GUID/DN) and resolved user/group DN. Enable temporarily with:

```properties
quarkus.log.category."consulting.tornbergs.directory".level=DEBUG
```

Payload logging is disabled by default. 0.5.0 adds opt-in redacted JSON diagnostics; see upgrade-0.5.0.md for configuration and redaction limits. Authentication headers and raw LDAP exceptions are not logged. Resolved DNs are personal/organizational data: restrict access to logs. Default file rotation: logs/directory-provisioning-service.log, 10 MB, five backups. Protect both console and file logs. /health reports process availability, not LDAP readiness; authenticated GET /api/v1/provisioning checks TLS and bind.

## Linux / RHEL systemd

Install Java 21 using your approved distribution. Prepare directories as root:

```bash
useradd --system --home-dir /opt/directory-provisioning-service --shell /sbin/nologin dps
install -d -m 0750 -o root -g dps /opt/directory-provisioning-service
install -d -m 0750 -o root -g dps /opt/directory-provisioning-service/config/certs
install -d -m 0700 -o dps -g dps /opt/directory-provisioning-service/logs
install -d -m 0700 -o root -g root /etc/directory-provisioning-service
```

Copy the complete dist/quarkus-app directory under /opt/directory-provisioning-service. Copy your existing configuration and certificates to config/application.properties and config/certs. Keep binaries/configuration/certificates root-owned, group dps, directories 0750 and files 0640; dps needs read access only. Do not put the application under /root. Resolve Java's actual executable path and update ExecStart in the unit if /usr/bin/java is not the intended Java 21.

Create /etc/directory-provisioning-service/secrets.env using a root editor, mode 0600, containing the HTTPS keystore and LDAP truststore passwords (not AD credentials):

```text
DPS_HTTPS_KEYSTORE_PASSWORD='replace locally'
DPS_TRUSTSTORE_PASSWORD='replace locally'
```

This is a systemd EnvironmentFile, not a shell script. Do not use export. Environment variables are not a secret vault; restrict host/process administration and use your organization's secret deployment mechanism if available. Never commit this file.

```bash
install -m 0644 deployment/linux/directory-provisioning.service /etc/systemd/system/
systemd-analyze verify /etc/systemd/system/directory-provisioning.service
systemctl daemon-reload
systemctl enable --now directory-provisioning.service
systemctl status directory-provisioning.service
journalctl -u directory-provisioning.service -n 100 --no-pager
```

Permit incoming HTTPS only from required clients through host/network firewall. Preserve SELinux enforcement; inspect AVC messages if access fails. No generic SELinux exception is supplied. Test authenticated connection, fulfillment and service restart under dps before deployment approval.

## Windows service

Use a pinned approved stable WinSW release from https://github.com/winsw/winsw/releases (wrapper not bundled). Follow the bundled executable/XML installation mode: place the renamed wrapper directory-provisioning-service.exe alongside directory-provisioning-service.xml in C:\Services\directory-provisioning-service. Copy quarkus-app, config and certificates there. Edit the XML executable to your actual Java 21 path.

Use an approved dedicated local/domain OS service account with Log on as a service, read access to binaries/config/certificates and Modify access only to logs. This OS identity is separate from the AD credentials supplied per request. Install the wrapper, then set its Log On account in services.msc before starting; the wrapper defaults to LocalSystem and should not run that way for the pilot. Use services.msc rather than putting account passwords on the command line.

Provide DPS_HTTPS_KEYSTORE_PASSWORD and DPS_TRUSTSTORE_PASSWORD through your approved deployment mechanism. If using machine environment variables, remember they are not a vault and restart the service host as needed so the service sees them; test the actual service environment. Alternatively use protected local properties with literal keystore passwords, outside Git, readable only by administrators and the service account. Do not store AD credentials in either location.

From an elevated terminal in the service directory:

```powershell
.\directory-provisioning-service.exe install
# Set Log On account and file permissions before starting.
.\directory-provisioning-service.exe start
.\directory-provisioning-service.exe status
.\directory-provisioning-service.exe stop
```

Validate this template on Windows; Windows service installation has not been executed here. For wrapper-specific options consult https://winsw.github.io/v2/doc/installation/ and the documentation for your pinned release.

## Upgrade / rollback

Stop the service before replacing the entire quarkus-app directory. Back up the previous distribution; preserve config, certs and logs. Start, check health and authenticated connection, then run a lab operation through IG. Roll back by stopping, restoring the previous complete quarkus-app and any corresponding configuration changes, and restarting. Keep a record of changed memberships; rolling back software does not undo AD operations.

## Certificates and monitoring

Monitor process health, service restart failures, 401/403/5xx rates and request duration. LDAP availability requires an authenticated synthetic check using your credential management policy. Renew the HTTPS certificate/private key in the service keystore, then restart and test IG trust. Renew DC/CA trust before expiry or issuing-CA changes, restart and test authenticated LDAP bind. Use issuing CA trust where appropriate; preserve hostname verification. Record expiry and renewal ownership.

## Remaining production decisions

Single target and single DC remain supported; failover and multiple targets are not implemented. Sub-application account context is unresolved. No persistent execution ledger or automatic write retry. AD ACLs are the authorization boundary; search-base scopes GUID discovery. Both objects must remain visible under that base after moves. HTTPS terminates directly at the service. Restrict request volume and choose timeout/concurrency values for your environment.

## Reference documentation

- systemd execution/sandbox options: https://github.com/systemd/systemd/blob/main/man/systemd.exec.xml
- WinSW installation: https://winsw.github.io/v2/doc/installation/

Mandatory external directory settings and the full default review are in upgrade-0.5.0.md. Existing deployments must set target, LDAP host/port, search base and allowed bind DN explicitly.
