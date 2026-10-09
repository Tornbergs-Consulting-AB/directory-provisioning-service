# Uninstall — Linux and Windows

Before removal, disable or redirect the IG fulfillment configuration so pending work is not sent to this instance. Uninstalling the service does not undo any AD membership changes. Keep configuration/certificates/logs until retention and rollback requirements are met. These commands remove service registration; they do not automatically delete data or accounts.

## Linux (systemd installer layout)

Stop/disable and unregister the service:

```bash
sudo systemctl disable --now directory-provisioning.service
sudo systemctl reset-failed directory-provisioning.service
sudo rm -- /etc/systemd/system/directory-provisioning.service
sudo systemctl daemon-reload
```

If reset-failed reports the unit was not loaded, continue only after verifying it is stopped and disabled. Confirm the HTTPS listener is gone with `ss -ltnp` for your configured port.

Retained paths:

- /opt/directory-provisioning-service — binaries, config, certificates, logs.
- /etc/directory-provisioning-service/secrets.env — keystore/truststore passwords, root-only.
- OS account/group dps.
- system journal entries, subject to journal retention.

For complete removal, after backing up anything required and confirming these are the dedicated installer directories:

```bash
sudo rm -rf -- /opt/directory-provisioning-service /etc/directory-provisioning-service
sudo userdel dps
```

Delete the dps group only if it remains and no other account uses it. Remove only a firewall rule created exclusively for this instance; do not close a shared port or remove shared Java/certificates/accounts. Do not remove the AD service account if other integrations use it. Restoring software does not restore deleted config/certificates.

## Windows (WinSW bundled mode)

Use elevated PowerShell from the installation directory. Stop through the service controller, then unregister using the same WinSW wrapper/config used at install time:

```powershell
Stop-Service -Name DirectoryProvisioningService -ErrorAction Stop
.\directory-provisioning-service.exe uninstall
if ($LASTEXITCODE -ne 0) { throw 'WinSW uninstall failed; inspect wrapper log.' }
```

If the service is already stopped, continue to uninstall. Replace the wrapper filename with the one actually used; the executable/XML basenames must match in bundled mode. If installed with an explicit XML argument, supply that same argument to uninstall instead. Verify service registration is gone:

```powershell
Get-Service -Name DirectoryProvisioningService -ErrorAction SilentlyContinue
```

Windows may show a service marked for deletion while Services or another process holds a handle; close those consoles before reinstallation. Config, XML (which may contain passwords), certificates, binaries and logs remain on disk. After verifying uninstall succeeded and retaining anything required, delete the dedicated installation folder. Remove only the dedicated firewall rule and unused OS service account/environment variables. Preserve shared Java installations and the AD account if used elsewhere.

Reference: https://winsw.github.io/v2/ (stop/uninstall) and https://winsw.github.io/documentation/v3/docs/cli-commands/ . Use documentation matching your pinned wrapper release.
