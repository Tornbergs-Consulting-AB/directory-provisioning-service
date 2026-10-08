# Directory Provisioning Service

REST-to-directory provisioning. First milestone: a DEV response-contract probe to establish how IG handles responses before implementing LDAP.

## Current implementation

The probe returns predetermined HTTP/JSON responses. It does not connect to LDAP, authenticate credentials, validate the JSON body, or change memberships. Basic authentication is checked for syntax only. Use synthetic credentials. Do not configure this as a real fulfillment target: a simulated 200 could cause IG to mark a real request fulfilled.

Production implementation planned: Java 21, Quarkus, UnboundID LDAP SDK. The probe intentionally uses only JDK libraries and will be replaced by the production REST layer.

## Windows quick start (PowerShell)

Extract the archive to `C:\Development\directory-provisioning-service`. Open that folder in VS Code.

```powershell
java -version
java -jar dist/response-probe.jar
```

The included JAR was compiled with Java 17-compatible bytecode and runs on Java 21. To rebuild with your Java 21 and Maven:

```powershell
mvn package
java -jar target/directory-provisioning-service-0.1.0-SNAPSHOT.jar
```

Alternatively, compile directly:

```powershell
New-Item -ItemType Directory -Force build/classes | Out-Null
javac --release 21 -d build/classes src/main/java/consulting/tornbergs/directory/ResponseProbe.java
java -cp build/classes consulting.tornbergs.directory.ResponseProbe
```

Default address: `http://127.0.0.1:8080`. Check `GET /health`.

To make it reachable from the IG DEV server, choose an appropriate listening address and permit only the needed inbound DEV connection:

```powershell
$env:DPS_HOST = '0.0.0.0'
$env:DPS_PORT = '8080'
$env:DPS_LOG_LEVEL = 'FINE'
java -jar target/directory-provisioning-service-0.1.0-SNAPSHOT.jar 2>&1 | Tee-Object -FilePath probe.log
```

This probe uses HTTP. Use synthetic credentials only. If the IG connector requires HTTPS, put it behind a TLS reverse proxy whose certificate IG trusts. Real AD credentials belong only on the later HTTPS service.

## Linux

```bash
mvn package
DPS_HOST=127.0.0.1 DPS_PORT=8080 java -jar target/directory-provisioning-service-0.1.0-SNAPSHOT.jar
```

## Response tests

See [IG test procedure](docs/ig-response-tests.md) and [design](docs/design.md). A Python smoke test is supplied:

```bash
python scripts/smoke_test.py
```

It compiles the source with a JDK 17-compatible release, starts an isolated probe, tests responses, and checks that credentials are absent from logs. Production builds target Java 21.

## Start your repository

This archive is a source scaffold, not an already-created GitHub repository. In the extracted folder:

```bash
git init -b main
git add .
git commit -m "Add DEV fulfillment response probe and design"
```

Create an empty GitHub repository named `directory-provisioning-service` under your company account, then follow GitHub's instructions to add the remote and push. No license is selected yet; decide ownership and licensing before distribution.
