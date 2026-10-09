package org.hkrpg.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BootstrapOptionsTest {
    @Test void defaultModeRunsBothServices() {
        assertEquals(BootstrapOptions.Mode.BOTH, BootstrapOptions.parse(new String[0]).mode());
    }
    @Test void dispatchCanRunWithoutGameResourcesOrConsole() {
        var options = BootstrapOptions.parse(new String[]{"--mode=dispatch", "--no-console", "--check"});
        assertEquals(BootstrapOptions.Mode.DISPATCH, options.mode());
        assertTrue(options.noConsole());
        assertTrue(options.check());
    }
    @Test void preservesUpstreamLaunchFlags() {
        assertEquals(BootstrapOptions.Mode.GAME, BootstrapOptions.parse(new String[]{"-game"}).mode());
    }
    @Test void rejectsTyposAndConflictingModes() {
        assertThrows(IllegalArgumentException.class, () -> BootstrapOptions.parse(new String[]{"--mode=4.6"}));
        assertThrows(IllegalArgumentException.class, () -> BootstrapOptions.parse(new String[]{"--typo"}));
        assertThrows(IllegalArgumentException.class, () -> BootstrapOptions.parse(new String[]{"-game", "-dispatch"}));
    }
}
