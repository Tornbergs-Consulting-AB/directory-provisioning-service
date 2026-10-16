# Documentation — 1.3.1-SNAPSHOT

The account-deletion and API/import guides describe the development version; release-1.0.0.md documents the published baseline. Earlier upgrade notes and development milestones have been consolidated into the current configuration, design and release documentation.

| Document | Purpose |
|---|---|
| [Business-role provisioning](business-role-provisioning.md) | Reference lookup before IG collection, ordering, application removal and lab validation |
| [AD delegation](ad-delegation.md) | Bind-account rights for lookup, creation, membership and deletion |
| [Account creation](account-creation.md) | 1.3.1-SNAPSHOT creation contract, IG configuration, suffixes and retry matching |
| [Account deletion](account-deletion.md) | 1.3.1-SNAPSHOT upgrade, payloads, deletion semantics and real AD/IG validation |
| [Start here: certificates and application.properties](certificates-and-configuration.md) | First installation: certificate roles, lab/production creation, file layout, passwords and connection test |
| [Configuration](configuration.md) | Required settings, defaults, TLS and JSON diagnostics |
| [Operations](operations.md) | Linux/Windows services, logs, monitoring and certificate renewal |
| [Linux installer](../deployment/linux/README.md) | Fresh installation on Linux/RHEL |
| [Windows passwords](../deployment/windows/password-setup.md) | Supplying keystore/truststore passwords to the service |
| [IG target imports](ig-import.md) | Sanitized account/identity GUID target exports, embedded transformations and post-import checklist |
| [API and IG integration](api-and-ig.md) | Current request/response contract and IG scripts |
| [Design](design.md) | Implemented architecture and directory semantics |
| [Directory target](directory-target.md) | Logical target label and accepted single-DC limitation |
| [Lab setup](demo-setup.md) | Linux and Windows examples using designated AD test objects |
| [Validation](validation.md) | Build checks and limits of test evidence |
| [Pilot validation](pilot-validation.md) | Accepted pilot checks and a repeatable deployment checklist |
| [Release and upgrade](release-1.0.0.md) | Version 1.0.0, distribution replacement and rollback |
| [Uninstall](uninstall.md) | Linux and Windows service removal and optional cleanup |
| [Distribution](distribution.md) | Source/binary packaging, licences and public-release preparation |

The project [maintenance policy](../SUPPORT.md), [contribution guide](../CONTRIBUTING.md), [security reporting guidance](../SECURITY.md), [licence](../LICENSE) and [third-party notices](../THIRD_PARTY_NOTICES.md) apply to this release.
