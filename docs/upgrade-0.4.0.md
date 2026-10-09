# Upgrade to 0.4.0

Keep `/api/v1/provisioning`, the existing IG scripts and response mappings. No payload change.

Stop the service, back up the old complete quarkus-app directory, then replace it with dist/quarkus-app. Preserve config/application.properties, certificates and logs. Start from the same working directory. Your existing HTTPS-only configuration should satisfy startup validation. Wrong/missing truststore passwords now prevent startup rather than waiting for the first request.

For DEBUG identifier diagnostics set:

```properties
quarkus.log.category."consulting.tornbergs.directory".level=DEBUG
```

Expect object=user/group identifierType=GUID and resolvedDN in logs, plus operation/outcome/code at INFO. Return to INFO after diagnosis.

Read operations.md before moving from an interactive root process to an OS service account. Move application/configuration/certificates out of /root; use the documented /opt layout. Copy the full distribution rather than merging old and new JAR files. Pilot acceptance tests are in pilot-validation.md.
