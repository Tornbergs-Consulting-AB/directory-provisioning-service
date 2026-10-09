# Certificates and application.properties — start here

This guide prepares the configuration and certificate files needed **before** installing Directory Provisioning Service 1.0.0. It applies to Linux and Windows. Existing working installations can keep their certificates; do not regenerate them just to follow this guide.

## 1. Understand the two connections

IG sends requests to the service over HTTPS. The service connects to a domain controller over LDAPS. Each server presents its own certificate, and the client must trust it.

| Connection | Server certificate and private key | Where the client trusts that certificate |
|---|---|---|
| IG/DaaS → this service, HTTPS | `config/certs/service-https.p12` on the service host | The outbound HTTPS trust configuration used by IG/DaaS |
| This service → AD, LDAPS | Already installed on the domain controller | `config/certs/ldap-truststore.p12` on the service host |

**Keystore** means the service's certificate **and private key**. **Truststore** means public certificates of the CA(s), or a deliberately trusted lab certificate. Both files in this guide use the PKCS12 format (`.p12`). A `.pfx` file can contain the same format.

The service needs these two files. It does not need the domain controller's private key or a client certificate from IG. The two connections can use certificates from the same CA, but the files have different purposes.

The keystore and truststore passwords protect/open those local files. They are separate from the AD account password, which IG supplies in each request.

## 2. Create application.properties

`application.properties` is an ordinary text file containing the settings for **your installation**: listening port, certificate file locations, LDAP host, allowed AD bind DN and logging. It is not a certificate, database or Java source file. Edit it with a text editor; no build is needed. Restart the service after changes.

`examples/service-config.properties` is only a template. The service does not automatically load that filename. Copy it to **`config/application.properties`**. On Windows, ensure the editor does not append `.txt`.

Start in the extracted release folder. Prepare files there first; the Linux installer will copy them into the installation folder. For a manual/Windows installation, prepare them in the folder that will hold `quarkus-app`.

Linux/RHEL:

```bash
mkdir -p config/certs
cp examples/service-config.properties config/application.properties
```

PowerShell:

```powershell
New-Item -ItemType Directory -Force config/certs | Out-Null
Copy-Item examples/service-config.properties config/application.properties
```

These commands are for a new installation. Do not overwrite an existing customized configuration.

The service reads `config/application.properties` relative to its **working directory**, not relative to the JAR. Certificate paths in the example are also relative to that working directory.

| File | Linux installed location | Windows example location |
|---|---|---|
| Configuration | `/opt/directory-provisioning-service/config/application.properties` | `C:\Services\directory-provisioning-service\config\application.properties` |
| HTTPS keystore | `/opt/directory-provisioning-service/config/certs/service-https.p12` | `C:\Services\directory-provisioning-service\config\certs\service-https.p12` |
| LDAP truststore | `/opt/directory-provisioning-service/config/certs/ldap-truststore.p12` | `C:\Services\directory-provisioning-service\config\certs\ldap-truststore.p12` |
| Java launcher | `/opt/directory-provisioning-service/quarkus-app/quarkus-run.jar` | `C:\Services\directory-provisioning-service\quarkus-app\quarkus-run.jar` |

The systemd unit sets the Linux working directory. The WinSW XML sets `<workingdirectory>%BASE%</workingdirectory>`; `%BASE%` is the wrapper's directory. Environment variables and Java `-D` options can override file settings; check them if the effective configuration differs from the file.

## 3. Choose names and obtain the AD public certificate

Choose the DNS name IG will use in the service URL, for example `provisioning.example.com`. The HTTPS certificate must contain that name in its **Subject Alternative Name (SAN)**. Connecting by IP requires an IP SAN; using a DNS name is usually easier. Configure DNS so IG can resolve and reach that host.

Separately choose the domain controller's actual DNS name for `dps.ldap-host`. The service verifies that name against the DC certificate. Opening port 636 alone does not enable LDAPS: the DC must have an appropriate certificate with a private key and Server Authentication usage. If LDAPS already works, retain its existing certificate. See [Microsoft's LDAPS requirements](https://learn.microsoft.com/en-us/troubleshoot/windows-server/active-directory/enable-ldap-over-ssl-3rd-certification-authority) if the DC still needs setup.

For production, obtain the **public root CA certificate**, and any required intermediate CA certificates, from your AD/PKI administrator. This guide calls them `ad-root-ca.pem` and `ad-issuing-ca.pem`. Do not export the DC's private key.

For an isolated lab, you can explicitly trust the DC's public leaf certificate instead. To retrieve it on Linux with OpenSSL:

```bash
# Replace this with your DC's DNS name.
DPS_DC_FQDN='demodc01.demo.tornbergs.consulting'
set -o pipefail
openssl s_client -connect "${DPS_DC_FQDN}:636" \
  -servername "$DPS_DC_FQDN" -showcerts </dev/null 2>dc-certificate-inspection.log \
  | openssl x509 -outform PEM -out ad-dc.pem
openssl x509 -in ad-dc.pem -noout -subject -issuer -dates -fingerprint -sha256
```

Retrieving a certificate does not establish that it is trustworthy. Compare its SHA-256 fingerprint with the certificate on the DC through a trusted administrative channel before importing it. Direct leaf trust must be updated when the DC certificate is renewed; trusting the approved issuing CA avoids that dependency.

## 4. Prepare the HTTPS keystore — choose ONE option

Use Java 21's `keytool`. Run `java -version` and `keytool -help` to confirm they are available. The commands prompt for passwords; record them securely for step 7. Use the same password for the HTTPS private key and keystore in these examples.

### Option A: production certificate from your PKI administrator

Request a **Server Authentication** certificate for the service's DNS name, with the correct DNS SAN. Obtain a password-protected PKCS12/PFX containing the certificate, its matching private key and intermediate certificate chain. Put that file at `config/certs/service-https.p12`. Renaming a PKCS12 `.pfx` to `.p12` is fine; renaming a public `.cer` does not create a keystore.

If the private key must be generated on the service host, first generate the keypair using option B below, then create a certificate signing request (CSR):

```bash
keytool -certreq -alias service-https \
  -keystore config/certs/service-https.p12 \
  -file service-https.csr \
  -ext "SAN=dns:${DPS_SERVICE_FQDN}" -ext "EKU=serverAuth"
```

Send **only the CSR** to your CA. Ask it to include the requested DNS SAN and Server Authentication usage. After receiving the public CA certificates and signed server certificate, import them into the same keystore:

```bash
# Confirm CA fingerprints with your PKI administrator when prompted.
keytool -importcert -alias https-root-ca -file https-root-ca.pem \
  -keystore config/certs/service-https.p12
# If there is an intermediate CA:
keytool -importcert -alias https-issuing-ca -file https-issuing-ca.pem \
  -keystore config/certs/service-https.p12
# IMPORTANT: use the original private-key alias for the certificate reply.
keytool -importcert -alias service-https -file service-https-issued.pem \
  -keystore config/certs/service-https.p12
```

For multiple intermediate CAs, import each under a different alias, from root toward the issuing CA. Verify the issued certificate's SAN/usage and chain in step 6 before using it. Keep the keystore on the service host; the CA does not need your private key or its password.

If your PKI supplies PEM files instead of a PFX, convert them with OpenSSL. Here `https-intermediates.pem` contains the intermediate certificate(s):

```bash
openssl pkcs12 -export -name service-https \
  -inkey service-https.key -in service-https-issued.pem \
  -certfile https-intermediates.pem -out config/certs/service-https.p12
```

### Option B: self-signed certificate for a lab

Use this when you control both the service and IG's trust configuration. It creates a new private key and a self-signed server certificate. Do not run it over an existing keystore.

Linux/RHEL:

```bash
# The exact DNS name in IG's HTTPS URL; replace this example.
DPS_SERVICE_FQDN='provisioning.example.com'
keytool -genkeypair -alias service-https -keyalg RSA -keysize 3072 \
  -validity 365 -storetype PKCS12 \
  -keystore config/certs/service-https.p12 \
  -dname "CN=${DPS_SERVICE_FQDN}" \
  -ext "SAN=dns:${DPS_SERVICE_FQDN}" -ext "EKU=serverAuth"
keytool -exportcert -rfc -alias service-https \
  -keystore config/certs/service-https.p12 -file service-https.pem
```

PowerShell (equivalent commands):

```powershell
$dpsServiceFqdn = 'provisioning.example.com'
keytool -genkeypair -alias service-https -keyalg RSA -keysize 3072 -validity 365 -storetype PKCS12 -keystore config/certs/service-https.p12 -dname "CN=$dpsServiceFqdn" -ext "SAN=dns:$dpsServiceFqdn" -ext "EKU=serverAuth"
keytool -exportcert -rfc -alias service-https -keystore config/certs/service-https.p12 -file service-https.pem
```

`service-https.pem` is the **public certificate** for clients to trust. `service-https.p12` also contains the **private key** and must stay protected on the service host. The Linux keystore/CSR workflow can also prepare a Windows-compatible file; securely transfer it to its intended service host, without leaving extra private-key copies behind.

## 5. Create the LDAP truststore

Choose either CA trust (recommended) or explicit DC leaf trust (lab). Do not import every certificate you retrieve without deciding which authority to trust.

CA trust; run this one-line command on Linux or PowerShell:

```text
keytool -importcert -alias ad-root-ca -file ad-root-ca.pem -keystore config/certs/ldap-truststore.p12 -storetype PKCS12
```

This creates the truststore and asks you to choose its password. Confirm the displayed CA fingerprint. If required, add the intermediate CA(s) using separate aliases and the same truststore password:

```text
keytool -importcert -alias ad-issuing-ca -file ad-issuing-ca.pem -keystore config/certs/ldap-truststore.p12 -storetype PKCS12
```

For explicit lab leaf trust, use this **instead** of the CA imports, after verifying the DC fingerprint in step 3:

```text
keytool -importcert -alias ad-dc -file ad-dc.pem -keystore config/certs/ldap-truststore.p12 -storetype PKCS12
```

This file contains public certificates only. The service reads this explicit truststore for LDAPS; importing the CA only into the Windows certificate store or Java's global `cacerts` does not populate it.

## 6. Inspect both files

These commands work on either platform and prompt for each file's password:

```text
keytool -list -v -storetype PKCS12 -keystore config/certs/service-https.p12
keytool -list -v -storetype PKCS12 -keystore config/certs/ldap-truststore.p12
```

Check that:

- The HTTPS keystore has a `PrivateKeyEntry`, a valid certificate, the service URL's DNS SAN and Server Authentication usage. A CA-issued server entry should include its intermediate chain.
- The LDAP truststore has the intended `trustedCertEntry` certificate(s). It does not need a private key.
- Certificates have not expired and the server clocks are correct.

## 7. Connect the files to application.properties

Edit the file created in step 2. The following example uses HTTPS port **8444** throughout. Use your environment's actual DNS names, DNs and target label:

```properties
# INCOMING: IG connects to this service over HTTPS.
quarkus.http.host=0.0.0.0
quarkus.http.ssl-port=8444
quarkus.http.insecure-requests=disabled
quarkus.http.ssl.certificate.key-store-file=config/certs/service-https.p12
quarkus.http.ssl.certificate.key-store-file-type=PKCS12
quarkus.http.ssl.certificate.key-store-password=${DPS_HTTPS_KEYSTORE_PASSWORD}

# OUTGOING: this service connects to AD over LDAPS.
dps.ldap-host=dc01.example.com
dps.ldap-port=636
dps.truststore=config/certs/ldap-truststore.p12
dps.truststore-password=${DPS_TRUSTSTORE_PASSWORD}

# Directory settings; replace these values for your AD environment.
dps.target-name=DEMO
dps.search-base=OU=Managed,DC=example,DC=com
dps.allowed-bind-dn=CN=Provisioning Service,OU=Service Accounts,DC=example,DC=com

dps.probe-enabled=false
dps.log-request-payload=false
quarkus.log.category."consulting.tornbergs.directory".level=INFO
```

`${DPS_HTTPS_KEYSTORE_PASSWORD}` means “read the environment variable containing the password chosen for `service-https.p12`.” `${DPS_TRUSTSTORE_PASSWORD}` reads the password chosen for `ldap-truststore.p12`. Leave these expressions in the file; supply their values to the service process.

| How you start the service | How to supply these two passwords |
|---|---|
| Linux installer/systemd | The [installer](../deployment/linux/README.md) prompts for both and writes `/etc/directory-provisioning-service/secrets.env`, readable only by root. systemd passes them to Java. |
| Windows service/WinSW | Put the two `<env>` elements in the local WinSW XML as described in [Windows password setup](../deployment/windows/password-setup.md). Restrict access and keep that customized XML out of Git. |
| Temporary Linux foreground test | Use the prompts below in the same terminal that launches Java. This does not configure systemd. |

```bash
read -r -s -p 'HTTPS keystore password: ' DPS_HTTPS_KEYSTORE_PASSWORD
echo
export DPS_HTTPS_KEYSTORE_PASSWORD
read -r -s -p 'LDAP truststore password: ' DPS_TRUSTSTORE_PASSWORD
echo
export DPS_TRUSTSTORE_PASSWORD
# Run from the folder containing config/ and quarkus-app/.
java -jar quarkus-app/quarkus-run.jar
```

Keep private keys, local configuration and passwords outside Git. Restrict filesystem access to the administrators and service OS account as described in [operations](operations.md).

## 8. Make IG trust the HTTPS service

For a CA-issued service certificate, configure IG/DaaS to trust its public root CA and required intermediate chain. For the self-signed lab option, explicitly trust `service-https.pem` exported in step 4.

Use the trust configuration actually used by **IG/DaaS's outgoing REST connector**. Its location depends on your IG deployment. Importing into an unrelated Java installation or the Linux/Windows OS trust store may not affect the connector. Follow your IG version's certificate/truststore procedure and restart the affected component if required. This guide cannot name that deployment-specific truststore path.

Do not give IG `service-https.p12`, either local store password or any private key. If IG already connects successfully to the same HTTPS certificate, this trust step is already complete.

Set IG's URL to `https://YOUR-SERVICE-DNS-NAME:8444` and its connection-test and fulfillment paths to `/api/v1/provisioning`, following [API and IG integration](api-and-ig.md). The JSON `target` must equal `dps.target-name`. Configure Basic authentication with the AD account whose DN matches `dps.allowed-bind-dn`.

## 9. Install and test without changing AD

With the configuration and two `.p12` files prepared, continue with the [Linux installer](../deployment/linux/README.md) or [Windows service instructions](operations.md). Allow IG to reach the chosen HTTPS port and the service to reach the DC's LDAPS port.

For a Linux client, use the public HTTPS CA certificate (or `service-https.pem` for the self-signed lab):

```bash
SERVICE_URL='https://YOUR-SERVICE-DNS-NAME:8444'
SERVICE_CA='/path/to/https-root-ca.pem'
AD_BIND_DN='CN=Provisioning Service,OU=Service Accounts,DC=example,DC=com'
curl --cacert "$SERVICE_CA" --user "$AD_BIND_DN" -i \
  "$SERVICE_URL/api/v1/provisioning"
```

curl prompts for the AD password. Expected response: HTTP 200 and `"status":"DIRECTORY_READY"`. This tests HTTPS, the configured LDAPS trust/hostname and AD bind, without changing group membership. Then test IG's connection button. Continue with [lab membership tests](demo-setup.md) only after these succeed.

For a CA-based LDAPS setup, an optional independent TLS check from Linux is:

```bash
# This PEM bundle contains the approved root and any needed intermediate CAs.
openssl s_client -connect "${DPS_DC_FQDN}:636" \
  -servername "$DPS_DC_FQDN" -verify_hostname "$DPS_DC_FQDN" \
  -verify_return_error -CAfile ad-ca-chain.pem </dev/null
```

Confirm verification succeeds. `-showcerts` alone displays certificates; `-verify_return_error` makes verification failures stop the check. This checks TLS only, not an LDAP bind or AD permissions.

## Common setup mistakes

| Symptom | What to check |
|---|---|
| Configuration seems ignored or port 8080 appears | Exact filename `config/application.properties`, working directory, and environment/`-D` overrides |
| Service stops before it listens | Both `.p12` files exist, are readable, passwords match, and password variables reach the service process |
| HTTPS certificate has no private key | HTTPS file must contain a `PrivateKeyEntry`; a public `.cer`/`.pem` alone is insufficient |
| IG/curl rejects the HTTPS certificate | Client trusts the service issuer/chain, certificate is valid, and the URL hostname matches SAN |
| HTTPS works but the AD connection test fails | LDAPS truststore, DC hostname/certificate, port 636, configured allowed bind DN and supplied AD password |
| Trust worked until certificate renewal | Explicit leaf trust may need replacement; check chain, validity and hostname after renewal |

## Reference documentation

- [Quarkus configuration file loading and overrides](https://quarkus.io/guides/config-reference/)
- [Quarkus HTTPS keystore configuration](https://quarkus.io/guides/http-reference/)
- [Java 21 keytool commands](https://docs.oracle.com/en/java/javase/21/docs/specs/man/keytool.html)
- [OpenSSL s_client](https://docs.openssl.org/3.0/man1/openssl-s_client/) and [PKCS12 conversion](https://docs.openssl.org/3.0/man1/openssl-pkcs12/)
- [Microsoft AD LDAPS certificate requirements](https://learn.microsoft.com/en-us/troubleshoot/windows-server/active-directory/enable-ldap-over-ssl-3rd-certification-authority)
