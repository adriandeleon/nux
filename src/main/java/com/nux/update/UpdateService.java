package com.nux.update;

import static com.nux.i18n.Messages.tr;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.nux.AppInfo;

/**
 * Asks GitHub whether a newer release exists (the {@code DiscoveryService} idiom: one
 * daemon thread, results marshalled to the FX thread). Sends no data about the user —
 * it is an ordinary HTTPS GET of a public endpoint.
 */
public final class UpdateService {

    private static final System.Logger LOG = System.getLogger(UpdateService.class.getName());
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    /** The payload lists every asset; cap the read so a surprise can't exhaust memory. */
    private static final long MAX_BYTES = 1_000_000;

    /**
     * {@code available} = a newer release exists; {@code latest} is non-null on success
     * even when it isn't newer, so a manual check can say "you're up to date";
     * {@code error} is a short reason, null on success.
     */
    public record Outcome(boolean available, ReleaseInfo latest, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nux-update");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String endpoint;
    private final Consumer<Runnable> ui;

    public UpdateService() {
        this(AppInfo.LATEST_RELEASE_API, Platform::runLater);
    }

    /** Test seam: a local endpoint and a direct marshaller, so no network or toolkit is needed. */
    UpdateService(String endpoint, Consumer<Runnable> ui) {
        this.endpoint = endpoint;
        this.ui = ui;
    }

    /** Checks off-thread; {@code onResult} runs on the FX thread. */
    public void check(String currentVersion, Consumer<Outcome> onResult) {
        exec.execute(() -> {
            Outcome outcome = fetch(currentVersion);
            ui.accept(() -> onResult.accept(outcome));
        });
    }

    private Outcome fetch(String current) {
        if (!endpoint.toLowerCase(Locale.ROOT).startsWith("https://")
                && !endpoint.toLowerCase(Locale.ROOT).startsWith("http://127.0.0.1")) {
            return new Outcome(false, null, "update endpoint must be https");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    // GitHub answers 403 to a request without a User-Agent.
                    .header("User-Agent", AppInfo.NAME + "/" + AppInfo.VERSION)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                response.body().close();
                // GitHub's unauthenticated API allows 60 requests an hour per address.
                return new Outcome(false, null, response.statusCode() == 403 || response.statusCode() == 429
                        ? tr("update.rateLimited")
                        : "HTTP " + response.statusCode());
            }
            String body;
            try (InputStream in = response.body()) {
                body = new String(readCapped(in, MAX_BYTES), StandardCharsets.UTF_8);
            }
            ReleaseInfo latest = UpdateCheck.parseLatest(body);
            if (latest == null) {
                return new Outcome(false, null, "no usable release in the response");
            }
            return new Outcome(UpdateCheck.isNewer(current, latest.version()), latest, null);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "update check failed", e);
            if (e instanceof java.net.ConnectException
                    || e instanceof java.net.UnknownHostException
                    || e instanceof java.net.http.HttpTimeoutException) {
                return new Outcome(false, null, tr("update.offline"));
            }
            String message = e.getMessage();
            return new Outcome(
                    false, null, e.getClass().getSimpleName() + (message == null ? "" : ": " + message));
        }
    }

    /** Reads at most {@code max} bytes, then stops — an unbounded read of a remote body is a DoS. */
    static byte[] readCapped(InputStream in, long max) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > max) {
                throw new java.io.IOException("response exceeds " + max + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    public void shutdown() {
        exec.shutdownNow();
    }
}
