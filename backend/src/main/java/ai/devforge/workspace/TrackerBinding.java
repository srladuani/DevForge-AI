package ai.devforge.workspace;

import org.jspecify.annotations.Nullable;

/**
 * Which issue tracker a project uses. The credential is never part of it: each user brings their own connection of this type.
 *
 * @param providerType adapter type, e.g. JIRA_MCP
 * @param siteHint     optional site/workspace name or id for credentials that reach several
 */
public record TrackerBinding(String providerType, @Nullable String siteHint) {}
