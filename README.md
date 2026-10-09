# Directory Provisioning Service — 0.4.0

Java 21 / Quarkus / UnboundID LDAPS service. Implements add/remove direct AD group membership using DN or AD objectGUID identifiers.

## Quick start from this archive

A prebuilt distribution is included at `dist/quarkus-app`, compiled and tested using Java 17-compatible bytecode. Run it using your Java 21. Set up HTTPS and the LDAP truststore first, then run `java -jar dist/quarkus-app/quarkus-run.jar` from the project root. Maven rebuilds use Java 21 by default.

## Build

```powershell
mvn clean verify
```

Or the same command in a Linux shell. The built distribution is the **entire** `target/quarkus-app` directory. Preserve its `lib`, `app` and `quarkus` subdirectories; do not copy only quarkus-run.jar.

## Configure and run

1. Copy `examples/service-config.properties` to `config/application.properties`.
2. Place your existing LDAP truststore at `config/certs/ldap-truststore.p12`, or change its path.
3. Configure a separate HTTPS server PKCS12 keystore containing a private key and a certificate trusted by IG. See [lab setup](docs/demo-setup.md).
4. Set `DPS_TRUSTSTORE_PASSWORD` and `DPS_HTTPS_KEYSTORE_PASSWORD` securely on the service host. AD credentials are not configured here.
5. From the project root run:

```powershell
java -jar target/quarkus-app/quarkus-run.jar
```

Linux uses the same command. External `config/application.properties` is loaded relative to the current working directory. Relative certificate/log paths are also relative to that directory.

GET `/api/v1/provisioning` performs a TLS LDAP connection and bind without changing any objects; it accepts the same Basic credentials as POST. This supports GET-based connection tests.

Health: `https://<service-host>:8443/health`. It checks only the application; it does not bind to AD or assert LDAP readiness.

## Test

See [DEMO setup and membership tests](docs/demo-setup.md). Use a LAB user/group and supplied credentials. No arbitrary LDAP URL is accepted. Allowed bind DN is compared as an LDAP DN, not as an arbitrary string. The configured administrator account is a LAB choice; AD ACLs control what it can change.

First version supports one configured target, DEMO. `dps.search-base` scopes subtree GUID searches; DN operations read the supplied DNs directly. It is **not** an authorization boundary.

## IG integration

POST `/api/v1/provisioning` over HTTPS; Basic credentials are passed to AD. Use `scripts/ig-request.js` for the body and `scripts/ig-headers.js` for correlation. Map `fulfillmentId` and `comment` from response JSON. All real requests require `changeItemId`.

Observed IG contract: 200 -> COMPLETED/FULFILLED and response comment mapped; 400/503 -> RETRY/PENDING with IG's generic error; single-item retry dispatches again after correcting the endpoint. Service logs are the detailed failure evidence.

Subordinate applications with missing account context remain unresolved. The request script deliberately fails if accountProvId is missing. Do not use an identity DN as an unverified account fallback.

## Probe

Original standalone probe remains in `dist/response-probe.jar` and can still run separately. The Quarkus probe paths `/test/responses/<scenario>` are disabled by default. Enable `dps.probe-enabled=true` only for isolated synthetic tests. GET returns a connection-test response while enabled. POST response comments identify the change item and say SIMULATED. Credentials on probe paths are syntax-checked only, not authenticated against AD.

Original `scripts/smoke_test.py` tests the **old standalone probe**, not the new LDAP application. Run Maven tests for the new application.

## Current limits

No failover, durable request ledger, credential caching, or automatic write retries. Each request opens and closes its own TLS LDAP connection. Operation timeouts are 5 seconds each, connection timeout 5 seconds; set IG HTTP timeout above the cumulative steps (suggest 60 seconds for initial testing). Repeat operations converge on requested membership state; changeItemId alone is not a deduplication key.

TLS failures reject the request; never enable trust-all. LDAP referrals are not followed. Logs never intentionally include Authorization headers, passwords, payloads or raw LDAP exception messages. Keep third-party protocol loggers at INFO even when enabling application DEBUG/TRACE.

Existing project initialization instructions apply. No GitHub repository has been created by this package, and no license has been selected.

## Operations and production pilot

See [operations](docs/operations.md) for startup validation, diagnostics, Linux systemd and Windows WinSW templates, upgrade/rollback and certificate renewal. See [pilot acceptance checks](docs/pilot-validation.md). Production startup requires HTTPS with HTTP disabled and a loadable LDAP truststore. This release does not add multi-target support or DC failover.
