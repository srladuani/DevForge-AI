package ai.devforge.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CredentialCipherTest {
    private static String key(int seed) {
        byte[] b = new byte[32];
        for (int i = 0; i < 32; i++) b[i] = (byte) (seed * 31 + i);
        return Base64.getEncoder().encodeToString(b);
    }

    private static CredentialCipher cipher(int current, Map<Integer, String> keys) {
        return new CredentialCipher(new SecurityProperties(keys, current, false, List.of()));
    }

    private final UUID conn = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @Test
    void roundTripsAndNeverStoresPlaintext() {
        var c = cipher(1, Map.of(1, key(1)));
        var sealed = c.seal("sk-live-supersecret-value", conn, user);
        assertThat(new String(sealed.blob())).doesNotContain("supersecret");
        assertThat(c.open(sealed.blob(), sealed.keyVersion(), conn, user)).isEqualTo("sk-live-supersecret-value");
    }

    @Test
    void sameSecretSealsDifferentlyEachTime() {
        var c = cipher(1, Map.of(1, key(1)));
        assertThat(c.seal("same", conn, user).blob()).isNotEqualTo(c.seal("same", conn, user).blob());
    }

    @Test
    void ciphertextIsBoundToConnectionAndOwner() {
        var c = cipher(1, Map.of(1, key(1)));
        var sealed = c.seal("secret-value", conn, user);
        assertThatThrownBy(() -> c.open(sealed.blob(), 1, UUID.randomUUID(), user)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.open(sealed.blob(), 1, conn, UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperedCiphertextIsRejected() {
        var c = cipher(1, Map.of(1, key(1)));
        byte[] blob = c.seal("secret-value", conn, user).blob().clone();
        blob[blob.length - 1] ^= 1;
        assertThatThrownBy(() -> c.open(blob, 1, conn, user)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void oldKeyVersionStillOpensAfterRotationToNewKey() {
        var v1 = cipher(1, Map.of(1, key(1)));
        var sealedV1 = v1.seal("secret-value", conn, user);
        var v2 = cipher(2, Map.of(1, key(1), 2, key(2)));
        assertThat(v2.open(sealedV1.blob(), 1, conn, user)).isEqualTo("secret-value");
        var resealed = v2.seal("secret-value", conn, user);
        assertThat(resealed.keyVersion()).isEqualTo(2);
        assertThat(v2.open(resealed.blob(), 2, conn, user)).isEqualTo("secret-value");
        assertThatThrownBy(() -> v1.open(resealed.blob(), 2, conn, user)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartWithoutAUsableCurrentKey() {
        assertThatThrownBy(() -> cipher(1, Map.of(1, ""))).isInstanceOf(IllegalStateException.class).hasMessageContaining("DEVFORGE_MASTER_KEY");
        assertThatThrownBy(() -> cipher(1, Map.of(1, Base64.getEncoder().encodeToString(new byte[16])))).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> cipher(3, Map.of(1, key(1)))).hasMessageContaining("current-key-version");
    }
}
