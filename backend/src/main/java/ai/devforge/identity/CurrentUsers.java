package ai.devforge.identity;

/** The authenticated caller of the current request. */
public interface CurrentUsers {
    /** @throws ai.devforge.platform.DomainException 401 if unauthenticated */
    CurrentUser require();
}
