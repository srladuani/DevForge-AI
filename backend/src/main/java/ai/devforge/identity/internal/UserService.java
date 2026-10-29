package ai.devforge.identity.internal;

import ai.devforge.identity.CurrentUser;
import ai.devforge.identity.Users;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class UserService implements Users {
    private final JdbcClient jdbc;

    UserService(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<CurrentUser> find(UUID id) {
        return jdbc.sql("select id, email, display_name, admin from app_user where id = ?").param(id).query(UserService::map).optional();
    }

    @Override
    public Optional<CurrentUser> findByEmail(String email) {
        return jdbc.sql("select id, email, display_name, admin from app_user where lower(email) = lower(?)").param(email).query(UserService::map).optional();
    }

    /** Dev login helper: returns the existing user or creates one. Admin flag is only ever raised, never lowered, here. */
    CurrentUser getOrCreate(String email, String displayName, boolean admin) {
        return jdbc.sql("""
                insert into app_user (id, email, display_name, admin) values (?, lower(?), ?, ?)
                on conflict (email) do update set admin = app_user.admin or excluded.admin
                returning id, email, display_name, admin""")
            .params(UUID.randomUUID(), email, displayName, admin)
            .query(UserService::map).single();
    }

    /** Sign-in through the organisation's identity provider: it is the authority, so the admin flag follows the token in both directions. */
    CurrentUser syncFromIdentityProvider(String email, String displayName, boolean admin) {
        return jdbc.sql("""
                insert into app_user (id, email, display_name, admin) values (?, lower(?), ?, ?)
                on conflict (email) do update set admin = excluded.admin, display_name = excluded.display_name
                returning id, email, display_name, admin""")
            .params(UUID.randomUUID(), email, displayName, admin)
            .query(UserService::map).single();
    }

    private static CurrentUser map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new CurrentUser(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"), rs.getBoolean("admin"));
    }
}
