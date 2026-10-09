package emu.lunarcore.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class AuthTokens {
    public static boolean matches(String expected, String supplied) {
        return expected != null && supplied != null && !expected.isBlank() && !supplied.isBlank() &&
            MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
    private AuthTokens() {}
}
