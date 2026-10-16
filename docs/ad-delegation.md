# AD bind-account delegation

DPS uses the Basic authentication credentials supplied by IG for each LDAP bind. The Windows/Linux account running the Java service is a separate identity. Being allowed by dps.allowed-bind-dn does not grant any AD rights.

Use a dedicated ordinary AD service account, optionally in a dedicated delegation security group, and delegate only over the user OUs and groups the integration should manage. Domain Administrator membership is unnecessary. Microsoft documents both common-task delegation and custom object/property-specific delegation in the [Delegation of Control Wizard](https://learn.microsoft.com/en-us/windows-server/identity/ad-ds/manage/delegation-control-wizard) and [OU delegation guidance](https://learn.microsoft.com/en-us/windows-server/identity/ad-ds/plan/delegating-administration-by-using-ou-objects).

## Rights to review with the AD administrator

| Operation | Relevant AD access |
|---|---|
| Lookup and validation | List/read the relevant objects and properties, including objectClass, objectGUID, employeeID, sAMAccountName, userPrincipalName and all creation attributes used for reconciliation. Domain-wide reference/name checks require visibility of potential matches throughout the domain. Normal authenticated-user read access may already provide this; verify customer ACLs. |
| Create a disabled user | Create User objects on the permitted parent OU/container; property rights needed to supply sAMAccountName, userPrincipalName, employeeID, cn, userAccountControl and the configured text attributes on new user objects. |
| Delete a user | Delete access on the user or Delete User objects on its parent; explicit deny/protection from accidental deletion can still block it. No Delete Subtree permission is needed or used. |
| Add/remove membership | Read member and Write member on the permitted group objects. The writable attribute is the group's member, not the user's memberOf. |

The table is a review of the operations DPS performs, not a universal ACL template: effective rights depend on inheritance, existing denies and schema/security policy. Object creation permissions are evaluated as part of LDAP Add; validate the actual request with the delegated account, not just a successful connection test. No password reset right is required for this implementation: accounts are created disabled without a password. No permissions to edit ACLs, ownership or privileged group membership are required by the service.

## Configure through AD Users and Computers

1. Enable **View > Advanced Features**. Create the dedicated bind account/delegation group outside the OU containing disposable provisioned users.
2. On the OU that may contain new users, select **Delegate Control**, select the delegation group, and choose **Create a custom task to delegate**. Restrict creation/deletion to User objects. In **Properties > Security > Advanced**, inspect the resulting entries and their scope/inheritance. Ensure Create/Delete User objects applies on the intended parent containers; property grants apply to descendant User objects. Repeat for any other allowed placement OU.
3. Grant the required user property writes for the customer's creation attribute allowlist. Inspect effective access on a representative newly created user. The wizard's common task **Create, delete, and manage user accounts** is easier to start with in an isolated lab but broader than a custom property-specific delegation; review and narrow it before production.
4. Delegate **Modify the membership of a group** on the groups OU, or use a custom grant of Write member on only the specific groups. Inspect inheritance so it reaches the intended Group objects.
5. Verify read visibility across the domain for employeeID/SAM/UPN duplicate checks. Do not grant domain-wide write rights for this purpose.
6. Set dps.allowed-bind-dn to this account's DN, configure its credentials in IG, and restart DPS if configuration changed. Test GET connection, creation, membership add/remove, then deletion with disposable objects.

Protected administrative objects may not inherit normal OU delegation. Do not weaken their protection to make a provisioning test pass. Keep this integration's managed accounts and groups out of privileged administrative scope.

For troubleshooting, temporarily enable the application's DEBUG logging and JSON diagnostics. Explicit LDAP insufficient access maps to HTTP 403 ACCESS_DENIED. Other AD constraint/protection failures can produce 502 with an LDAP result code. A successful connection test proves TLS/bind, not authorization for writes. Test each operation individually and inspect AD effective access using the actual bind account.
