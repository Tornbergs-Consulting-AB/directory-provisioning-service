# Contributing

Discuss substantial scope changes in an issue before implementing them. Submit focused changes with a clear problem description, resulting behavior and relevant validation. Contributions are reviewed on a best-effort basis; acceptance and response times are not guaranteed.

Use Java 21 and Maven. Run `mvn clean verify`. For REST/logging changes, also run `python3 scripts/verify_packaged_diagnostics.py`. Real AD behavior requires designated lab objects and a recorded directory/IG verification result. Preserve credential isolation, LDAPS/HTTPS verification, direct-membership semantics and one-value LDAP modifications.

Do not submit deployment secrets, certificates with private keys, production logs or customer-owned material. Use generic examples. Confirm you have the rights to submit your contribution; intentionally submitted contributions are under Apache-2.0 as described by the project licence, unless explicitly agreed otherwise.

When changing dependencies, update and review third-party licences/notices and required corresponding sources with `scripts/generate_third_party.py`, then run `scripts/package_release.py`. Generated inventory is a review aid, not proof that upstream metadata describes all shaded/native components. Preserve manually reviewed supplemental notices.
