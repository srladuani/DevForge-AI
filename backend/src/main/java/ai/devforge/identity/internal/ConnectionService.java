package ai.devforge.identity.internal;

import ai.devforge.audit.AuditLog;
import ai.devforge.identity.AuthMethod;
import ai.devforge.identity.ConnectionKind;
import ai.devforge.identity.ConnectionStatus;
import ai.devforge.identity.ConnectionVerifier;
import ai.devforge.identity.ConnectionView;
import ai.devforge.identity.Connections;
import ai.devforge.platform.DomainException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The per-user credential vault. Secrets enter here, are stored only as ciphertext, and leave only through {@link #reveal}. */
@Service
class ConnectionService implements Connections {
    private static final String COLS = "id, user_id, kind, provider_type, auth_method, label, scope, status, status_message, last_verified_at, created_at";

    private final JdbcClient jdbc;
    private final CredentialCipher cipher;
    private final List<ConnectionVerifier> verifiers;
    private final AuditLog audit;
    private final Clock clock;

    ConnectionService(JdbcClient jdbc, CredentialCipher cipher, List<ConnectionVerifier> verifiers, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.verifiers = verifiers;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public List<ConnectionView> listFor(UUID userId) {
        return jdbc.sql("select " + COLS + " from connection where user_id = ? order by created_at").param(userId).query(ConnectionService::map).list();
    }

    @Override
    public Optional<ConnectionView> findUsable(UUID userId, ConnectionKind kind, String providerType) {
        return jdbc.sql("select " + COLS + " from connection where user_id = ? and kind = ? and provider_type = ? and status = 'VALID' "
                + "order by last_verified_at desc nulls last limit 1")
            .params(userId, kind.name(), providerType).query(ConnectionService::map).optional();
    }

    @Override
    public Optional<ConnectionView> findUsableForRepository(UUID userId, ConnectionKind kind, String providerType, String owner, String name) {
        String exact = owner + "/" + name;
        String wholeOwner = owner + "/*";
        return jdbc.sql("select " + COLS + " from connection where user_id = ? and kind = ? and provider_type = ? and status = 'VALID' "
                + "and (scope is null or lower(scope) = lower(?) or lower(scope) = lower(?)) "
                + "order by case when lower(scope) = lower(?) then 0 when scope is not null then 1 else 2 end, last_verified_at desc nulls last limit 1")
            .params(userId, kind.name(), providerType, exact, wholeOwner, exact).query(ConnectionService::map).optional();
    }

    @Override
    @Transactional(readOnly = true)
    public String reveal(UUID connectionId, UUID ownerUserId) {
        var row = jdbc.sql("select encrypted_secret, key_version, user_id from connection where id = ?").param(connectionId)
            .query((rs, i) -> new Object[] {rs.getBytes(1), rs.getInt(2), rs.getObject(3, UUID.class)}).optional()
            .orElseThrow(() -> DomainException.notFound("CONNECTION_NOT_FOUND", "Connection not found"));
        if (!ownerUserId.equals(row[2])) throw DomainException.forbidden("CONNECTION_NOT_OWNED", "Connection belongs to another user");
        return cipher.open((byte[]) row[0], (Integer) row[1], connectionId, ownerUserId);
    }

    @Transactional
    ConnectionView create(UUID userId, ConnectionKind kind, String providerType, AuthMethod authMethod, String label, String secret, @Nullable String scope) {
        String normalizedScope = normalizeScope(kind, scope);
        UUID id = UUID.randomUUID();
        var sealed = cipher.seal(secret, id, userId);
        jdbc.sql("insert into connection (id, user_id, kind, provider_type, auth_method, label, scope, encrypted_secret, key_version) values (?,?,?,?,?,?,?,?,?)")
            .params(id, userId, kind.name(), providerType, authMethod.name(), label, normalizedScope, sealed.blob(), sealed.keyVersion()).update();
        audit.record(userId.toString(), "CONNECTION_CREATED", "connection", id.toString(), kind + "/" + providerType + " via " + authMethod);
        return get(id, userId);
    }

    @Override
    @Transactional
    public ConnectionView saveOAuth(UUID userId, ConnectionKind kind, String providerType, String label, String secret, ConnectionStatus status, String statusMessage) {
        UUID existing = jdbc.sql("select id from connection where user_id = ? and kind = ? and provider_type = ? and auth_method = 'OAUTH' order by created_at limit 1")
            .params(userId, kind.name(), providerType).query(UUID.class).optional().orElse(null);
        UUID id;
        if (existing == null) {
            id = create(userId, kind, providerType, AuthMethod.OAUTH, label, secret, null).id();
        } else {
            id = existing;
            var sealed = cipher.seal(secret, id, userId);
            jdbc.sql("update connection set encrypted_secret = ?, key_version = ?, label = ?, updated_at = now() where id = ?").params(sealed.blob(), sealed.keyVersion(), label, id).update();
            audit.record(userId.toString(), "CONNECTION_SECRET_REPLACED", "connection", id.toString(), kind + "/" + providerType + " via OAUTH");
        }
        jdbc.sql("update connection set status = ?, status_message = ?, last_verified_at = ?, updated_at = now() where id = ?")
            .params(status.name(), statusMessage, Timestamp.from(Instant.now(clock)), id).update();
        return get(id, userId);
    }

    @Override
    @Transactional
    public void replaceSecret(UUID connectionId, UUID ownerUserId, String secret) {
        get(connectionId, ownerUserId); // 404 unless it is theirs
        var sealed = cipher.seal(secret, connectionId, ownerUserId);
        jdbc.sql("update connection set encrypted_secret = ?, key_version = ?, updated_at = now() where id = ?").params(sealed.blob(), sealed.keyVersion(), connectionId).update();
    }

    @Transactional
    ConnectionView update(UUID id, UUID userId, @Nullable String label, @Nullable String newSecret, @Nullable String scope) {
        ConnectionView existing = get(id, userId);
        if (scope != null) {
            jdbc.sql("update connection set scope = ?, updated_at = now() where id = ?").params(normalizeScope(existing.kind(), scope), id).update();
        }
        if (label != null) {
            jdbc.sql("update connection set label = ?, updated_at = now() where id = ?").params(label, id).update();
        }
        if (newSecret != null) {
            var sealed = cipher.seal(newSecret, id, userId);
            jdbc.sql("update connection set encrypted_secret = ?, key_version = ?, status = 'UNVERIFIED', status_message = null, "
                    + "last_verified_at = null, updated_at = now() where id = ?").params(sealed.blob(), sealed.keyVersion(), id).update();
        }
        audit.record(userId.toString(), newSecret != null ? "CONNECTION_SECRET_REPLACED" : "CONNECTION_UPDATED", "connection", id.toString(), existing.providerType());
        return get(id, userId);
    }

    @Transactional
    void delete(UUID id, UUID userId) {
        ConnectionView existing = get(id, userId);
        jdbc.sql("delete from connection where id = ?").param(id).update();
        audit.record(userId.toString(), "CONNECTION_DELETED", "connection", id.toString(), existing.kind() + "/" + existing.providerType());
    }

    /** Decrypts in memory, asks the provider's verifier, stores only the resulting status. */
    ConnectionView verify(UUID id, UUID userId) {
        ConnectionView c = get(id, userId);
        ConnectionVerifier.Result result = verifiers.stream().filter(v -> v.supports(c.kind(), c.providerType())).findFirst()
            .map(v -> v.verify(id, userId, reveal(id, userId)))
            .orElse(new ConnectionVerifier.Result(ConnectionStatus.UNVERIFIED, "No verifier is available for " + c.providerType()));
        jdbc.sql("update connection set status = ?, status_message = ?, last_verified_at = ?, updated_at = now() where id = ?")
            .params(result.status().name(), result.message(), Timestamp.from(Instant.now(clock)), id).update();
        audit.record(userId.toString(), "CONNECTION_VERIFIED", "connection", id.toString(), result.status().name());
        return get(id, userId);
    }

    /** Re-encrypts every secret under the current master key version (used after adding a new key version). */
    @Transactional
    int rotateAll(String actor) {
        var rows = jdbc.sql("select id, user_id, encrypted_secret, key_version from connection where key_version <> ?").param(cipher.currentVersion())
            .query((rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBytes(3), rs.getInt(4)}).list();
        for (Object[] r : rows) {
            UUID id = (UUID) r[0];
            UUID userId = (UUID) r[1];
            var sealed = cipher.seal(cipher.open((byte[]) r[2], (Integer) r[3], id, userId), id, userId);
            jdbc.sql("update connection set encrypted_secret = ?, key_version = ? where id = ?").params(sealed.blob(), sealed.keyVersion(), id).update();
        }
        audit.record(actor, "VAULT_ROTATED", "vault", null, rows.size() + " connection(s) re-encrypted to key v" + cipher.currentVersion());
        return rows.size();
    }

    private static final java.util.regex.Pattern SCOPE = java.util.regex.Pattern.compile("[A-Za-z0-9_.-]+/([A-Za-z0-9_.-]+|\\*)");

    /** Blank means "any repository". Only Git-host credentials can be narrowed, and only to a repository or to all of an owner's repositories. */
    private static @Nullable String normalizeScope(ConnectionKind kind, @Nullable String scope) {
        if (scope == null || scope.isBlank()) return null;
        if (kind != ConnectionKind.GIT_HOST) throw DomainException.badRequest("SCOPE_NOT_SUPPORTED", "Only Git-host connections can be limited to a repository");
        String s = scope.strip();
        if (!SCOPE.matcher(s).matches()) throw DomainException.badRequest("INVALID_SCOPE", "Use owner/repository, or owner/* for every repository of that owner or workspace");
        return s;
    }

    private ConnectionView get(UUID id, UUID userId) {
        ConnectionView c = jdbc.sql("select " + COLS + " from connection where id = ?").param(id).query(ConnectionService::map).optional()
            .orElseThrow(() -> DomainException.notFound("CONNECTION_NOT_FOUND", "Connection not found"));
        // 404 (not 403) for other users' rows so ids cannot be probed
        if (!c.userId().equals(userId)) throw DomainException.notFound("CONNECTION_NOT_FOUND", "Connection not found");
        return c;
    }

    private static ConnectionView map(ResultSet rs, int i) throws SQLException {
        Timestamp verified = rs.getTimestamp("last_verified_at");
        return new ConnectionView(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), ConnectionKind.valueOf(rs.getString("kind")),
            rs.getString("provider_type"), AuthMethod.valueOf(rs.getString("auth_method")), rs.getString("label"), rs.getString("scope"),
            ConnectionStatus.valueOf(rs.getString("status")), rs.getString("status_message"), verified == null ? null : verified.toInstant(),
            rs.getTimestamp("created_at").toInstant());
    }
}
