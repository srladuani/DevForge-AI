package ai.devforge.integration.internal;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A remote given as a local path ({@code file:///…/<marker>/<owner>/<repo>.git}); only accepted when a host adapter's test switch is on. The optional
 * marker directory (github or bitbucket) says which host the path stands for, so a test can use two hosts side by side.
 */
record FileRemote(@Nullable String marker, String owner, String name) {
    private static final Pattern URL = Pattern.compile("^file://(.*)$");

    static @Nullable FileRemote parse(String url) {
        Matcher m = URL.matcher(url);
        if (!m.matches()) return null;
        String[] parts = m.group(1).replaceAll("/+$", "").split("/");
        if (parts.length < 2) return null;
        String name = parts[parts.length - 1].replaceAll("\\.git$", "");
        String owner = parts[parts.length - 2];
        String marker = parts.length >= 3 && (parts[parts.length - 3].equals("github") || parts[parts.length - 3].equals("bitbucket")) ? parts[parts.length - 3] : null;
        return name.isEmpty() || owner.isEmpty() ? null : new FileRemote(marker, owner, name);
    }
}
