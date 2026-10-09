// Copyright 2026 Tornbergs Consulting AB
// SPDX-License-Identifier: Apache-2.0
package consulting.tornbergs.directory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ConfigurationChecksTest {
 @Test void validConfiguration() throws Exception {ConfigurationChecks.validate("DEMO","dc.example",636,"cn=svc,dc=example","dc=example",5000,5000,8);}
 @Test void rejectZeroTimeout(){assertThrows(IllegalArgumentException.class,()->ConfigurationChecks.validate("DEMO","dc.example",636,"cn=svc,dc=example","dc=example",0,5000,8));}
 @Test void rejectEmptyBase(){assertThrows(IllegalArgumentException.class,()->ConfigurationChecks.validate("DEMO","dc.example",636,"cn=svc,dc=example","",5000,5000,8));}
 @Test void rejectUrlAsHost(){assertThrows(IllegalArgumentException.class,()->ConfigurationChecks.validate("DEMO","ldaps://dc.example",636,"cn=svc,dc=example","dc=example",5000,5000,8));}
}
