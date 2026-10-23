package ai.devforge.integration;

import org.jspecify.annotations.Nullable;

/** Fetches tickets from whichever tracker a project uses. Read-only: no operation here can create, edit or comment. */
public interface Trackers {
    /**
     * @param providerType e.g. JIRA_MCP
     * @param siteHint     optional project setting that disambiguates the site/workspace
     * @throws TrackerException with a kind the caller can act on
     */
    Ticket fetch(String providerType, TrackerCredential credential, @Nullable String siteHint, String key);

    boolean supports(String providerType);
}
