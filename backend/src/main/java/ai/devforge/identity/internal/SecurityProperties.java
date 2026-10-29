package ai.devforge.identity.internal;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param keys              vault master keys by version (base64, 32 bytes each)
 * @param currentKeyVersion version used for new encryptions
 * @param devAuthEnabled    header-based login for local development only
 * @param devAdmins         emails that get admin when dev auth creates them (comma separated)
 */
@ConfigurationProperties("devforge.security")
record SecurityProperties(Map<Integer, String> keys, int currentKeyVersion, @DefaultValue("false") boolean devAuthEnabled,
                          @DefaultValue("") List<String> devAdmins) {}
