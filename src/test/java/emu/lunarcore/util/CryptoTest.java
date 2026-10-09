package emu.lunarcore.util;

import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CryptoTest {
    @Test void sessionKeysUseRandomnessEvenWithinTheSameMillisecond() {
        var keys = new HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(keys.add(Crypto.createSessionKey("same-account")), "Duplicate session token");
        }
    }
}
