package ai.devforge.identity.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM sealing of connection secrets. The ciphertext is bound (as AAD) to the connection and owner ids, so a row copied to
 * another connection or user does not decrypt. Master keys live outside the database (configuration / secret store).
 */
@Component
class CredentialCipher {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Map<Integer, SecretKey> keys = new HashMap<>();
    private final int currentVersion;
    private final SecureRandom random = new SecureRandom();

    CredentialCipher(SecurityProperties props) {
        if (props.keys() == null || props.keys().isEmpty()) {
            throw new IllegalStateException("devforge.security.keys is empty: set DEVFORGE_MASTER_KEY (base64, 32 bytes)");
        }
        props.keys().forEach((version, b64) -> {
            if (b64 == null || b64.isBlank()) return; // unset versions are ignored; current must exist below
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(b64.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Vault key version " + version + " is not valid base64");
            }
            if (raw.length != 32) throw new IllegalStateException("Vault key version " + version + " must be 32 bytes");
            keys.put(version, new SecretKeySpec(raw, "AES"));
        });
        this.currentVersion = props.currentKeyVersion();
        if (!keys.containsKey(currentVersion)) {
            throw new IllegalStateException("Vault key for current-key-version " + currentVersion + " is missing: set DEVFORGE_MASTER_KEY");
        }
    }

    int currentVersion() { return currentVersion; }

    record Sealed(byte[] blob, int keyVersion) {}

    Sealed seal(String secret, UUID connectionId, UUID userId) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, keys.get(currentVersion), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad(connectionId, userId));
            byte[] ct = c.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return new Sealed(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array(), currentVersion);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt secret", e);
        }
    }

    String open(byte[] blob, int keyVersion, UUID connectionId, UUID userId) {
        SecretKey key = keys.get(keyVersion);
        if (key == null) throw new IllegalStateException("No vault key for version " + keyVersion);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES));
            c.updateAAD(aad(connectionId, userId));
            return new String(c.doFinal(blob, IV_BYTES, blob.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt secret (wrong key, owner, or tampered data)");
        }
    }

    private static byte[] aad(UUID connectionId, UUID userId) {
        return (connectionId + ":" + userId).getBytes(StandardCharsets.UTF_8);
    }
}
