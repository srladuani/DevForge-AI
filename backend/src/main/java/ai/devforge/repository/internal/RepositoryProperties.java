package ai.devforge.repository.internal;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param allowedRoots    directories under which repositories may be registered by path; empty means the user's home directory
 * @param managedRoot     where DevForge keeps the repositories it clones from a URL; empty means {@code ~/.devforge/repos}
 * @param allowLocalPaths whether people may register a repository by a path on the server; turn off on a shared deployment, where everyone
 *                        should add repositories by URL (a path lets a project member point DevForge at anything the server can read)
 */
@ConfigurationProperties("devforge.repositories")
record RepositoryProperties(@DefaultValue List<String> allowedRoots, @DefaultValue("") String managedRoot, @DefaultValue("true") boolean allowLocalPaths) {}
