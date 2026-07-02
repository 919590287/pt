package com.jts.gjcxfzksh.api.service.impl;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密码哈希单测：PBKDF2 新格式 + 旧单轮 SHA-256 格式的兼容验证（登录时透明重哈希的前提）。
 */
class AuthServiceImplPasswordTest {

    private final AuthServiceImpl service = new AuthServiceImpl();

    @Test
    void pbkdf2HashRoundTrip() {
        String hash = service.createPasswordHash("secret-password");
        assertTrue(hash.startsWith("pbkdf2$"));
        assertFalse(AuthServiceImpl.isLegacyHash(hash));
        assertTrue(service.verifyPassword("secret-password", hash));
        assertFalse(service.verifyPassword("wrong-password", hash));
    }

    @Test
    void hashesAreSaltedAndUnique() {
        String first = service.createPasswordHash("same-password");
        String second = service.createPasswordHash("same-password");
        assertFalse(first.equals(second));
        assertTrue(service.verifyPassword("same-password", first));
        assertTrue(service.verifyPassword("same-password", second));
    }

    @Test
    void legacyShaHashStillVerifies() throws Exception {
        // 旧格式: base64(salt)$base64(sha256(salt||password))
        byte[] salt = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(salt);
        byte[] hash = digest.digest("legacy-password".getBytes(StandardCharsets.UTF_8));
        String legacy = Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash);

        assertTrue(AuthServiceImpl.isLegacyHash(legacy));
        assertTrue(service.verifyPassword("legacy-password", legacy));
        assertFalse(service.verifyPassword("wrong-password", legacy));
    }

    @Test
    void malformedHashesAreRejected() {
        assertFalse(service.verifyPassword("anything", null));
        assertFalse(service.verifyPassword("anything", "no-dollar-sign"));
        assertFalse(service.verifyPassword("anything", "pbkdf2$broken"));
        assertFalse(service.verifyPassword("anything", "!!!$!!!"));
    }
}
