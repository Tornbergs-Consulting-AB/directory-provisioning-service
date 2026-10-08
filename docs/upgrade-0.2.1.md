# Upgrade from 0.2.0 to 0.2.1

Fix: LDAP compare result 16 (NO_SUCH_ATTRIBUTE) means no direct member value for an existing empty group. It is handled only around the membership compare, including post-race confirmation. Missing-object, access-denied, connection errors and arbitrary modify errors are not silently treated as successful.

Stop the service with Ctrl+C. Back up the current quarkus-app directory and replace the entire directory with dist/quarkus-app from this archive. Preserve config/application.properties, certificates/truststores and logs. Do not merge old and new dependency folders. From the same working directory, with keystore password environment variables still exported:

java -jar quarkus-app/quarkus-run.jar

Repeat the existing curl add call twice, then the remove call twice. Inspect direct AD membership. No IG script or configuration changes are required for this fix.
