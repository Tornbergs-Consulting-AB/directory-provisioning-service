# Linux installer (fresh installations)

The installer and directory-provisioning.service must be in the same folder.
Use the extracted distribution, prepared configuration and prepared certificates:

```bash
sudo bash deployment/linux/install.sh \
  --distribution "$PWD/dist/quarkus-app" \
  --config "$PWD/config/application.properties" \
  --certs "$PWD/config/certs" \
  --java /usr/bin/java
```

Java must be Java 21, outside /root and /home. Passwords are prompted without echo and stored in a root-only systemd EnvironmentFile. The AD password is never requested or stored. Configuration must use ${DPS_HTTPS_KEYSTORE_PASSWORD} and ${DPS_TRUSTSTORE_PASSWORD}, and certificate paths relative to /opt/directory-provisioning-service, e.g. config/certs/service-https.p12.

The script creates the dps account, copies the complete distribution/config/certs, applies permissions, installs/verifies the systemd unit, reloads systemd and enables boot startup. It leaves the service stopped unless --start is specified. Existing installations are refused before changes. Use documented upgrade steps for your already-working host.

It does not generate certificates, install Java, adjust firewall/SELinux or test AD. A failure during installation can leave partial files; inspect the error and the installation directories before retrying. Do not remove a working installation to run this fresh-install script.

Validation here: bash syntax, help/argument handling, existing-install protection and environment-file escaping logic. Full installation is pending a fresh RHEL lab host; this environment does not run systemd as PID 1.
