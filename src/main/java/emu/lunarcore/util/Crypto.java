package emu.lunarcore.util;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import emu.lunarcore.LunarCore;

public final class Crypto {
    private static final SecureRandom secureRandom = new SecureRandom();

    public static void xor(byte[] packet, byte[] key) {
        try {
            for (int i = 0; i < packet.length; i++) {
                packet[i] ^= key[i % key.length];
            }
        } catch (Exception e) {
            LunarCore.getLogger().error("Crypto error.", e);
        }
    }

    // Simple way to create a unique session key
    public static String createSessionKey(String accountUid) {
        byte[] random = new byte[64];
        secureRandom.nextBytes(random);

        return Base64.getEncoder().encodeToString(random);
    }
}
