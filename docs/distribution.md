# Distribution — 1.0.0

## Contents and licences

Publish the complete source alongside the versioned binary archive and SHA-256. Our source, tests, scripts, templates and documentation use Apache-2.0. Retain `LICENSE` and `NOTICE`. Third-party components retain their own licences; include `THIRD_PARTY_NOTICES.md` and the complete `third-party/` folder with binary redistribution, including after copying the runtime out of this archive.

Java and WinSW are external prerequisites. No JDK, Windows service-wrapper executable, AD credentials, deployment keystores or production configuration is shipped. WinSW is obtained separately using the operator's approved pinned release.

`third-party/runtime-components.json` records exact bundled runtime JARs, hashes, Maven coordinates and declared/selected licences. `third-party/sbom.cdx.json` is a CycloneDX 1.6 flat runtime SBOM; it does not claim a full dependency graph or exhaustive shaded/native component modeling. `third-party/retained/` preserves licence/notice material from those JARs and available source archives. `third-party/supplemental/` contains reviewed upstream notices for components whose JARs omit them, including Netty, CRaC, Reactive Streams and Brotli. `third-party/sources/` provides corresponding source JARs for the EPL-2.0 components under their original licences. Source archives contain source for libraries, not Java or WinSW executables.

Test/build tools are not runtime dependencies. Their licences remain relevant if someone distributes those tools or their binaries separately. Dependencies can contain shaded/native code, so review upstream notices as well as POM metadata when changing versions.

## Build and package

```bash
mvn clean verify
python3 scripts/verify_packaged_diagnostics.py
# Copy the complete runtime from the verified build into a fresh dist tree.
python3 - <<'PYTHON'
import shutil
from pathlib import Path
runtime = Path('dist/quarkus-app')
if runtime.exists():
    shutil.rmtree(runtime)
shutil.copytree('target/quarkus-app', runtime)
PYTHON
python3 scripts/generate_third_party.py --download-sources
python3 scripts/package_release.py
```

The inventory generator uses the local Maven repository for POMs and downloads source archives from Maven Central when requested. It preserves reviewed supplemental files and refuses missing required Eclipse sources. Review changed licences before publication. Python 3 is needed for these release-preparation scripts, not to run the Java service.

Keep an exact copy of each published archive. Once a release is public, publish corrections under a new version rather than replacing its contents. This documentation/licensing consolidation is preparation for the initial public 1.0.0 release; it introduces no directory behavior change.

## Public repository preparation

1. Publish source under the intended company/owner account, with the licence and maintenance documents.
2. Enable private vulnerability reporting or provide a verified private contact and update `SECURITY.md`.
3. Check for secrets/customer material and ensure examples are clearly lab-only.
4. Create a version tag that matches the reviewed source and publish the matching binary archive, source and checksum.
5. Keep build/test instructions and complete third-party materials available so customers can independently rebuild and redistribute their copy.

This archive does not create a remote repository, tag, support contract or public release.
