package emu.lunarcore.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuthTokensTest {
    @Test void requiresANonEmptyExactToken() {
        assertTrue(AuthTokens.matches("issued-token", "issued-token"));
        assertFalse(AuthTokens.matches("issued-token", "wrong-token"));
        assertFalse(AuthTokens.matches(null, "token"));
        assertFalse(AuthTokens.matches("token", null));
        assertFalse(AuthTokens.matches("", ""));
    }
}
