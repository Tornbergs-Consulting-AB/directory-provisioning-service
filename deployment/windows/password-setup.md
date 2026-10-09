# Windows keystore passwords and startup diagnosis

An interactive PowerShell $env variable is not passed to a service launched by the Windows Service Control Manager. For an explicit per-service environment, add these two elements inside the <service> element in the WinSW XML beside the wrapper executable:

```xml
<env name="DPS_HTTPS_KEYSTORE_PASSWORD" value="REPLACE_LOCALLY" />
<env name="DPS_TRUSTSTORE_PASSWORD" value="REPLACE_LOCALLY" />
```

This stores passwords in plaintext XML. Restrict access to Administrators, SYSTEM and the chosen service OS account, and keep the file out of Git. They are keystore passwords, not AD credentials. XML attribute values must escape &: &amp;, <: &lt;, and double quote: &quot;. Do not add a literal password to a shell command/history.

A protected config/application.properties with literal password values is also supported; Java properties require their own escaping. Prefer the XML mechanism for this initial setup because it preserves the existing ${...} configuration. Machine environment variables require the service host to inherit refreshed settings; the per-service XML avoids that uncertainty.

The wrapper executable and XML must share a basename in bundled mode, e.g. directory-provisioning-service.exe and directory-provisioning-service.xml. Verify <executable> points to a real Java 21 executable; the supplied jdk-21 path is only a template. <workingdirectory>%BASE%</workingdirectory> must point to the folder holding config and quarkus-app.

Restart via Services or Restart-Service -Name DirectoryProvisioningService. Reinstall is unnecessary for these env elements. Inspect logs/*.wrapper.log and logs/*err.log or logs/*out.log (filenames depend on wrapper version), plus logs/directory-provisioning-service.log. Wrapper errors can occur before the application log is created. Missing env substitutions, incorrect keystore passwords, unreadable files, wrong Java path and occupied HTTPS port can all cause startup failure.

Reference: https://winsw.github.io/v2/doc/installation/ and https://github.com/winsw/winsw/blob/v3/docs/xml-config-file.md . Match documentation to your chosen WinSW version.
