package ai.devforge.integration.internal;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

/**
 * Sends a Git-host API request, trying again when the network drops it. A reset or timeout on an office or VPN network is common and usually gone on
 * the next attempt, so one blip should not fail a whole publication. A request that may already have reached the host (anything but a read) is only
 * repeated when it failed before it was sent, so a pull request is never created twice by a retry.
 */
final class HostHttp {
    private static final int ATTEMPTS = 3;
    private static final long[] BACKOFF_MILLIS = {300, 900};

    private HostHttp() {}

    static HttpResponse<String> send(HttpClient http, HttpRequest request) throws IOException, InterruptedException {
        boolean read = request.method().equals("GET") || request.method().equals("HEAD");
        IOException last = null;
        int attempt = 1;
        for (; attempt <= ATTEMPTS; attempt++) {
            try {
                return http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                last = e;
                if (attempt == ATTEMPTS || !(read || neverSent(e))) break;
                Thread.sleep(BACKOFF_MILLIS[attempt - 1]);
            }
        }
        throw new IOException(describe(last) + "; tried " + Math.min(attempt, ATTEMPTS) + (attempt == 1 ? " time" : " times"), last);
    }

    /** Failures that happen while connecting, before any part of the request reaches the host. */
    private static boolean neverSent(IOException e) {
        return e instanceof ConnectException || e instanceof HttpConnectTimeoutException || e instanceof UnknownHostException || e instanceof SSLHandshakeException;
    }

    /** A short reason a person can act on, without the stack or any URL. */
    static String describe(IOException e) {
        Throwable t = e;
        while (t != null) {
            String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (t instanceof UnknownHostException) return "the host name could not be resolved";
            if (t instanceof HttpTimeoutException) return "the request timed out";
            if (m.contains("connection reset")) return "the connection was reset";
            if (t instanceof SSLException) return "the secure connection could not be set up";
            if (t instanceof ConnectException) return "the connection was refused or blocked";
            t = t.getCause();
        }
        return "the connection failed";
    }
}
