package ai.devforge.integration.internal;

import ai.devforge.audit.AuditLog;
import ai.devforge.identity.ConnectionKind;
import ai.devforge.integration.AdapterAvailability;
import ai.devforge.integration.AdapterType;
import ai.devforge.integration.ModelPolicy;
import ai.devforge.integration.ProviderInstanceView;
import ai.devforge.integration.Providers;
import ai.devforge.platform.DomainException;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
class ProviderService implements Providers, ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ProviderService.class);
    private static final String COLS = "id, kind, adapter_type, display_name, base_url, enabled, model_policy, updated_at";

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AdapterRegistry registry;
    private final ProviderSeedProperties seeds;
    private final AuditLog audit;

    ProviderService(JdbcClient jdbc, JsonMapper json, AdapterRegistry registry, ProviderSeedProperties seeds, AuditLog audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.registry = registry;
        this.seeds = seeds;
        this.audit = audit;
    }

    /** First boot only: seeds instances from configuration. Admin edits are never overwritten afterwards. */
    @Override
    public void run(ApplicationArguments args) {
        if (jdbc.sql("select count(*) from provider_instance").query(Integer.class).single() > 0) return;
        seeds.seed().forEach(s -> insert(s.kind(), s.adapterType(), s.displayName(), s.baseUrl(), true, ModelPolicy.none(), "system"));
        log.info("Seeded {} provider instance(s)", seeds.seed().size());
    }

    @Override public List<AdapterType> adapterTypes() { return registry.all(); }

    @Override
    public List<ProviderInstanceView> instances(ConnectionKind kind, boolean enabledOnly) {
        return jdbc.sql("select " + COLS + " from provider_instance where (cast(? as text) is null or kind = ?) and (not ? or enabled) order by display_name")
            .params(kind == null ? null : kind.name(), kind == null ? null : kind.name(), enabledOnly).query(this::map).list();
    }

    @Override
    public Optional<ProviderInstanceView> findEnabled(ConnectionKind kind, String adapterType) {
        return jdbc.sql("select " + COLS + " from provider_instance where kind = ? and adapter_type = ? and enabled order by created_at limit 1")
            .params(kind.name(), adapterType.toUpperCase()).query(this::map).optional();
    }

    @Transactional
    ProviderInstanceView create(String actor, ConnectionKind kind, String adapterType, String displayName, String baseUrl, boolean enabled, ModelPolicy policy) {
        AdapterType type = registry.find(kind, adapterType).orElseThrow(() -> DomainException.badRequest("UNKNOWN_ADAPTER_TYPE", "No adapter type " + adapterType + " for " + kind));
        String url = validatedUrl(baseUrl);
        ProviderInstanceView v = insert(kind, type.type(), displayName.trim(), url, enabled, policy == null ? ModelPolicy.none() : policy, actor);
        return v;
    }

    @Transactional
    ProviderInstanceView update(String actor, UUID id, String displayName, String baseUrl, Boolean enabled, ModelPolicy policy) {
        ProviderInstanceView cur = get(id);
        jdbc.sql("update provider_instance set display_name = ?, base_url = ?, enabled = ?, model_policy = ?, updated_at = now() where id = ?")
            .params(displayName != null ? displayName.trim() : cur.displayName(), baseUrl != null ? validatedUrl(baseUrl) : cur.baseUrl(),
                enabled != null ? enabled : cur.enabled(), json.writeValueAsString(policy != null ? policy : cur.modelPolicy()), id).update();
        audit.record(actor, "PROVIDER_UPDATED", "provider_instance", id.toString(), cur.adapterType());
        return get(id);
    }

    /** Removes the instance unless users still hold connections for its adapter type; then it is only disabled so nothing breaks silently. */
    @Transactional
    boolean remove(String actor, UUID id) {
        ProviderInstanceView cur = get(id);
        int inUse = jdbc.sql("select count(*) from connection where kind = ? and provider_type = ?").params(cur.kind().name(), cur.adapterType()).query(Integer.class).single();
        if (inUse > 0) {
            jdbc.sql("update provider_instance set enabled = false, updated_at = now() where id = ?").param(id).update();
            audit.record(actor, "PROVIDER_DISABLED_IN_USE", "provider_instance", id.toString(), cur.adapterType() + " (" + inUse + " connection(s) remain)");
            return false;
        }
        jdbc.sql("delete from provider_instance where id = ?").param(id).update();
        audit.record(actor, "PROVIDER_REMOVED", "provider_instance", id.toString(), cur.adapterType());
        return true;
    }

    ProviderInstanceView get(UUID id) {
        return jdbc.sql("select " + COLS + " from provider_instance where id = ?").param(id).query(this::map).optional()
            .orElseThrow(() -> DomainException.notFound("PROVIDER_NOT_FOUND", "Provider instance not found"));
    }

    private ProviderInstanceView insert(ConnectionKind kind, String adapterType, String displayName, String baseUrl, boolean enabled, ModelPolicy policy, String actor) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("insert into provider_instance (id, kind, adapter_type, display_name, base_url, enabled, model_policy) values (?,?,?,?,?,?,?)")
                .params(id, kind.name(), adapterType, displayName, baseUrl, enabled, json.writeValueAsString(policy)).update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw DomainException.conflict("PROVIDER_EXISTS", "An instance with that type and name already exists");
        }
        audit.record(actor, "PROVIDER_CREATED", "provider_instance", id.toString(), kind + "/" + adapterType + " " + displayName);
        return get(id);
    }

    /** Base URLs come from admins only and must be http(s); this is the SSRF boundary for adapters (no model-supplied URLs ever). */
    private static String validatedUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI u = URI.create(url.trim());
            if (u.getScheme() == null || !(u.getScheme().equals("https") || u.getScheme().equals("http")) || u.getHost() == null || u.getUserInfo() != null) throw new IllegalArgumentException();
            return u.toString();
        } catch (IllegalArgumentException e) {
            throw DomainException.badRequest("INVALID_BASE_URL", "Base URL must be an http(s) URL without credentials");
        }
    }

    private ProviderInstanceView map(ResultSet rs, int i) throws SQLException {
        ConnectionKind kind = ConnectionKind.valueOf(rs.getString("kind"));
        String type = rs.getString("adapter_type");
        return new ProviderInstanceView(rs.getObject("id", UUID.class), kind, type, rs.getString("display_name"), rs.getString("base_url"), rs.getBoolean("enabled"),
            json.readValue(rs.getString("model_policy"), ModelPolicy.class), registry.find(kind, type).map(AdapterType::availability).orElse(AdapterAvailability.PLANNED),
            rs.getTimestamp("updated_at").toInstant());
    }
}
