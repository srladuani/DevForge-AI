package ai.devforge.agent.internal.anthropic;

import ai.devforge.agent.LlmException;
import ai.devforge.agent.LlmException.Kind;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;

/** Maps what the vendor library throws to the provider-neutral failure kinds. Messages are generic: they never echo request or credential text. */
final class AnthropicErrors {
    private AnthropicErrors() {}

    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    /** The provider's own error type and message (what Anthropic returns is about the request, not secrets), scrubbed and shortened, so a failure can be diagnosed. */
    static String detail(Throwable root, String secret) {
        String body = root instanceof HttpException || root instanceof InvalidRequestException || root instanceof AuthenticationException || root instanceof RateLimitException ? root.getMessage() : null;
        if (body == null || body.isBlank()) return "";
        String type = "", message = body;
        try {
            var err = JSON.readTree(body).path("error");
            if (err.isObject()) {
                type = err.path("type").asString("");
                message = err.path("message").asString(body);
            }
        } catch (RuntimeException notJson) {
            // keep the raw text
        }
        String text = (type.isEmpty() ? "" : type + ": ") + message.replaceAll("\\s+", " ").strip();
        if (secret != null && !secret.isBlank()) text = text.replace(secret, "***");
        text = ai.devforge.platform.Redactor.redact(text);
        return text.length() > 220 ? text.substring(0, 220) + "…" : text;
    }

    private static LlmException withDetail(LlmException base, Throwable root, String secret) {
        String d = detail(root, secret);
        return d.isEmpty() ? base : new LlmException(base.kind(), base.getMessage() + " Provider said: " + d);
    }

    static LlmException map(Throwable t, String secret) {
        Throwable root = rootOf(t);
        return withDetail(map(t), root, secret);
    }

    private static Throwable rootOf(Throwable t) {
        Throwable root = t;
        for (int i = 0; i < 6 && root.getCause() != null && root.getCause() != root; i++) {
            if (isKnown(root)) break;
            root = root.getCause();
        }
        return root;
    }

    static LlmException map(Throwable t) {
        Throwable root = t;
        for (int i = 0; i < 6 && root.getCause() != null && root.getCause() != root; i++) {
            if (isKnown(root)) break;
            root = root.getCause();
        }
        if (root instanceof AuthenticationException) return new LlmException(Kind.AUTH_INVALID, "The provider rejected the API key");
        if (root instanceof RateLimitException) return new LlmException(Kind.RATE_LIMITED, "The provider is rate limiting requests");
        if (root instanceof TimeoutException || root instanceof SocketTimeoutException || root instanceof HttpTimeoutException || root instanceof InterruptedIOException) {
            return new LlmException(Kind.TIMEOUT, "The provider did not answer in time");
        }
        if (root instanceof ContentFilteredException) return new LlmException(Kind.CONTENT_FILTERED, "The provider blocked the request");
        if (root instanceof InternalServerException) return new LlmException(Kind.UNAVAILABLE, "The provider had an internal error");
        String msg = root.getMessage() == null ? "" : root.getMessage().toLowerCase(Locale.ROOT);
        if (root instanceof InvalidRequestException || root instanceof HttpException) {
            int status = root instanceof HttpException h ? h.statusCode() : 400;
            if (msg.contains("credit balance") || msg.contains("billing") || msg.contains("quota") || msg.contains("insufficient")) {
                return new LlmException(Kind.QUOTA_EXHAUSTED, "The provider reports the account has no credit or quota left");
            }
            return switch (status) {
                case 401, 403 -> new LlmException(Kind.AUTH_INVALID, "The provider rejected the API key");
                case 402 -> new LlmException(Kind.QUOTA_EXHAUSTED, "The provider reports the account has no credit or quota left");
                case 408, 504 -> new LlmException(Kind.TIMEOUT, "The provider did not answer in time");
                case 429 -> new LlmException(Kind.RATE_LIMITED, "The provider is rate limiting requests");
                case 500, 502, 503, 529 -> new LlmException(Kind.UNAVAILABLE, "The provider is temporarily unavailable");
                default -> new LlmException(Kind.BAD_REQUEST, "The provider rejected the request (HTTP " + status + ")");
            };
        }
        if (msg.contains("timed out") || msg.contains("timeout")) return new LlmException(Kind.TIMEOUT, "The provider did not answer in time");
        if (root instanceof java.io.IOException || msg.contains("connect")) return new LlmException(Kind.UNAVAILABLE, "The provider could not be reached");
        return new LlmException(Kind.UNAVAILABLE, "The provider call failed unexpectedly");
    }

    private static boolean isKnown(Throwable t) {
        return t instanceof AuthenticationException || t instanceof RateLimitException || t instanceof TimeoutException || t instanceof ContentFilteredException
            || t instanceof InternalServerException || t instanceof InvalidRequestException || t instanceof HttpException;
    }
}
