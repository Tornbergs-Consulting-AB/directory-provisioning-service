# Validation performed

- Source compiled using OpenJDK 17.0.20 compiler module with --release 17.
- 16 live HTTP cases passed: all 9 response scenarios; missing, malformed and empty-password Basic headers; wrong method; unknown scenario and path; body limit.
- Verified JSON response and correlation ID propagation; WWW-Authenticate on 401.
- Verified supplied password, Authorization value and body marker absent from logs with probe FINEST logging enabled. JDK protocol loggers remain at INFO.
- Runnable dist/response-probe.jar built from this source with Java 17-compatible bytecode.
- Maven Java 21 build and Windows execution have not been run in this environment.
- IG integration and AD/LDAP behavior have not yet been tested or implemented.
