package org.hkrpg.server.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientVersionCheckTest {
    @Test void betaBuildCannotUseReleaseNumbersToSelectTheReleaseProfile() {
        assertFalse(ClientVersionCheck.accepts("OSBETAWin4.6.0", "4.6.0"));
        assertFalse(ClientVersionCheck.accepts("CNDEVWin4.6.0", "4.6.0"));
    }
    @Test void releaseAndBetaVersionsAreSeparated() {
        assertTrue(ClientVersionCheck.accepts("CNPRODWin4.6.0", "4.6.0"));
        assertTrue(ClientVersionCheck.accepts("OSPRODWin4.6.0_D16707949", "4.6.0"));
        assertFalse(ClientVersionCheck.accepts("OSBETAWin4.6.51", "4.6.0"));
        assertFalse(ClientVersionCheck.accepts("CNPRODWin4.6.0", "4.2.0"));
        assertFalse(ClientVersionCheck.accepts("unknown", "4.6.0"));
    }
}
