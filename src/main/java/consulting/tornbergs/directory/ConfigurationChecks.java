// Copyright 2026 Marcus Tornberg
// SPDX-License-Identifier: Apache-2.0
package consulting.tornbergs.directory;

import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;

final class ConfigurationChecks {
    static void validate(String target,String host,int port,String bind,String base,int connect,int operation,int concurrent) throws LDAPException {
        if (target==null || !target.matches("[A-Za-z0-9._-]{1,64}") || host==null || host.isBlank()
            || host.contains("://") || host.chars().anyMatch(Character::isWhitespace)
            || port<1 || port>65535 || connect<1 || operation<1 || concurrent<1)
            throw new IllegalArgumentException("Invalid target or limits");
        if (new DN(bind).isNullDN() || new DN(base).isNullDN())
            throw new IllegalArgumentException("Nonempty bind DN and search base required");
    }
}
