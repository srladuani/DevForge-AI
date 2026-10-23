package ai.devforge.integration;

/** A repository on a Git host, identified from a checkout's remote. {@code pushUrl} is where a branch is pushed. */
public record RemoteRepo(String hostType, String owner, String name, String pushUrl) {
    public String fullName() { return owner + "/" + name; }
}
