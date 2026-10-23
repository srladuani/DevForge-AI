package ai.devforge.repository.internal;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** @param allowedRoots directories under which repositories may be registered; empty means the user's home directory */
@ConfigurationProperties("devforge.repositories")
record RepositoryProperties(@DefaultValue List<String> allowedRoots) {}
