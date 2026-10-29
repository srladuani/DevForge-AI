package ai.devforge.platform;

import org.springframework.http.HttpStatus;

/** A business-rule failure with a stable machine-readable code, rendered as RFC 7807 problem+json. */
public class DomainException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public DomainException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }

    public static DomainException notFound(String code, String message) { return new DomainException(code, HttpStatus.NOT_FOUND, message); }
    public static DomainException badRequest(String code, String message) { return new DomainException(code, HttpStatus.BAD_REQUEST, message); }
    public static DomainException conflict(String code, String message) { return new DomainException(code, HttpStatus.CONFLICT, message); }
    public static DomainException forbidden(String code, String message) { return new DomainException(code, HttpStatus.FORBIDDEN, message); }
    public static DomainException unprocessable(String code, String message) { return new DomainException(code, HttpStatus.UNPROCESSABLE_ENTITY, message); }
}
