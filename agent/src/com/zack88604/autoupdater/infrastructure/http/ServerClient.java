package com.zack88604.autoupdater.infrastructure.http;

import com.zack88604.autoupdater.infrastructure.json.JsonParser;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP client with bounded retries, validated byte-range recovery, and download feedback.
 */
public final class ServerClient {

    public enum DownloadState { RECEIVING, WAITING, RETRYING, RESUMING, RESTARTING, VERIFYING }
    public enum FailureReason { TIMEOUT, NETWORK, HTTP, INTEGRITY, LOCAL_IO }

    /** Only emitted when every configured server explicitly reports maintenance. */
    public static final class MaintenanceException extends IOException {
        public MaintenanceException(String message) { super(message); }
    }

    private static final class ServerMaintenanceException extends IOException {
        ServerMaintenanceException(String message) { super(message); }
    }

    public static final class HttpStatusException extends IOException {
        HttpStatusException(int status) { super("HTTP " + status); }
    }

    /** Retains the original cause after bounded attempts. */
    public static final class DownloadFailedException extends IOException {
        private final FailureReason reason;
        private final int attempts;
        public DownloadFailedException(String path, FailureReason reason, int attempts, IOException cause) {
            super("Download failed after " + attempts + " attempt(s): " + path + "; " + cause.getMessage(), cause);
            this.reason = reason;
            this.attempts = attempts;
        }
        public FailureReason getReason() { return reason; }
        public int getAttempts() { return attempts; }
    }

    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private static final ScheduledExecutorService WAIT_MONITOR = Executors.newScheduledThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "download-wait-monitor");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Receives infrastructure events without coupling this client to a GUI toolkit.
     */
    public interface Listener {
        void onLog(String message);

        void onServerChanged(List<String> serverUrls, String currentServer);

        void onDownloadProgress(long totalBytes, long downloadedBytes);

        default void onDownloadState(DownloadState state, int attempt, int maximum, long value) { }

        default boolean isPaused() { return false; }

        /** Pause or cancel an in-flight transfer at a safe checkpoint. */
        default void checkpoint() {
            // Callers without lifecycle control keep the legacy behavior.
        }
    }

    private final List<String> serverUrls;
    private final Listener listener;
    private int currentServerIndex;
    private final int readTimeoutMillis;
    private final long waitingNoticeMillis;
    private final long retryDelayMillis;
    private final Object feedbackLock = new Object();

    public ServerClient(List<String> serverUrls, Listener listener) {
        this(serverUrls, listener, 60000, 5000, 1000);
    }

    // Short timing is available to package-local network regression tests only.
    ServerClient(List<String> serverUrls, Listener listener, int readTimeoutMillis,
                 long waitingNoticeMillis, long retryDelayMillis) {
        this.serverUrls = Collections.unmodifiableList(new ArrayList<>(serverUrls));
        this.listener = listener;
        this.readTimeoutMillis = readTimeoutMillis;
        this.waitingNoticeMillis = waitingNoticeMillis;
        this.retryDelayMillis = retryDelayMillis;
    }

    public List<String> getServerUrls() {
        return serverUrls;
    }

    public String getCurrentServer() {
        return serverUrls.get(currentServerIndex);
    }

    /** URL-encode each path segment while preserving path separators. */
    public static String encodePath(String relativePath) {
        StringBuilder encoded = new StringBuilder();
        for (String segment : relativePath.split("/")) {
            if (encoded.length() > 0) {
                encoded.append('/');
            }
            encoded.append(URLEncoder.encode(segment, StandardCharsets.UTF_8)
                    .replace("+", "%20"));
        }
        return encoded.toString();
    }

    /** Fetch a UTF-8 response, failing over through each configured server. */
    public String getWithFallback(String path) throws IOException {
        IOException lastException = null;
        String[] maintenanceMessages = new String[serverUrls.size()];
        int maintenanceCount = 0;
        int startIndex = currentServerIndex;
        for (int i = 0; i < serverUrls.size(); i++) {
            listener.checkpoint();
            int index = (startIndex + i) % serverUrls.size();
            String server = serverUrls.get(index);
            try {
                if (index != currentServerIndex) {
                    listener.onLog("Trying server: " + server);
                }
                String response = get(server + path);
                switchServerIfNeeded(index);
                return response;
            } catch (ServerMaintenanceException e) {
                maintenanceMessages[index] = e.getMessage();
                maintenanceCount++;
                listener.onLog("  [MAINTENANCE] Skipping server: " + server);
            } catch (IOException e) {
                lastException = e;
                listener.onLog("  [WARN]  Server unreachable: " + server);
            }
        }
        if (!serverUrls.isEmpty() && maintenanceCount == serverUrls.size()) {
            throw new MaintenanceException(maintenanceMessages[0]);
        }
        throw lastException != null ? lastException
                : new IOException("All servers unreachable");
    }

    /** Compatibility entry point for callers that only need success or failure. */
    public boolean downloadWithFallback(String path, File destination) {
        try {
            downloadWithFallback(path, destination, -1, null);
            return true;
        } catch (DownloadFailedException failure) {
            listener.onLog("  [FAIL]  " + failure.getMessage());
            return false;
        }
    }

    /** Recover interrupted transfers before accepting a complete, verified artifact. */
    public void downloadWithFallback(String path, File destination, long expectedSize,
                                     String expectedHash) throws DownloadFailedException {
        int startIndex = currentServerIndex;
        int maximum = Math.max(3, serverUrls.size());
        IOException lastFailure = new IOException("No update servers configured");
        FailureReason reason = FailureReason.NETWORK;
        String validator = null;
        boolean fresh = true;
        long total = expectedSize > 0 ? expectedSize : 0;
        for (int attempt = 1; attempt <= maximum && !serverUrls.isEmpty(); attempt++) {
            listener.checkpoint();
            int index = (startIndex + attempt - 1) % serverUrls.size();
            String server = serverUrls.get(index);
            long offset = !fresh && destination.isFile() ? destination.length() : 0;
            if (attempt > 1) {
                feedback(DownloadState.RETRYING, attempt, maximum, offset);
                retryDelay(attempt);
            }
            listener.onLog("  [GET]   " + server + path + " (attempt " + attempt + "/" + maximum
                    + (offset > 0 ? ", resume at " + offset + " bytes" : "") + ")");
            try {
                // Only resume bytes obtained during this invocation; discard an invalid artifact.
                if (fresh) {
                    total = expectedSize > 0 ? expectedSize : 0;
                    try (FileOutputStream ignored = openDestination(destination, false)) { }
                    if (attempt > 1) feedback(DownloadState.RESTARTING, attempt, maximum, 0);
                    listener.onDownloadProgress(total, 0);
                }
                TransferResult result = download(server + path, destination, offset, total, validator, attempt, maximum);
                total = result.total;
                validator = result.validator;
                fresh = false;
                feedback(DownloadState.VERIFYING, attempt, maximum, 0);
                if (expectedSize >= 0 && destination.length() != expectedSize) {
                    throw new IntegrityException("Expected " + expectedSize + " bytes, received " + destination.length());
                }
                if (expectedHash != null && !expectedHash.equalsIgnoreCase(sha256(destination))) {
                    throw new IntegrityException("SHA-256 mismatch after download");
                }
                switchServerIfNeeded(index);
                return;
            } catch (TransferException failure) {
                lastFailure = failure.original;
                reason = failure.reason;
                total = failure.total;
                validator = failure.validator;
                fresh = reason == FailureReason.INTEGRITY;
                if (fresh) validator = null;
            } catch (IntegrityException failure) {
                lastFailure = failure;
                reason = FailureReason.INTEGRITY;
                fresh = true;
                validator = null;
            } catch (IOException failure) {
                lastFailure = failure;
                reason = FailureReason.LOCAL_IO;
            }
            listener.onLog("  [WARN]  " + server + path + ": " + lastFailure.getClass().getSimpleName()
                    + ": " + lastFailure.getMessage());
            if (reason == FailureReason.LOCAL_IO) {
                throw new DownloadFailedException(path, reason, attempt, lastFailure);
            }
        }
        throw new DownloadFailedException(path, reason, serverUrls.isEmpty() ? 0 : maximum, lastFailure);
    }

    private void switchServerIfNeeded(int index) {
        if (index == currentServerIndex) {
            return;
        }
        currentServerIndex = index;
        String server = serverUrls.get(index);
        listener.onLog("Switched to server: " + server);
        listener.onServerChanged(serverUrls, server);
    }

    private String get(String url) throws IOException {
        listener.checkpoint();
        HttpURLConnection connection =
                (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                if (status == 503 && (url.endsWith("/api/v3/manifest") || url.endsWith("/api/v2/manifest"))) {
                    String error = readErrorBody(connection);
                    if ("MAINTENANCE".equals(JsonParser.getDecodedString(error, "code"))) {
                        String message = JsonParser.getDecodedString(error, "message");
                        if (message == null || message.isBlank()) {
                            message = "Update server is under maintenance. Please try again later.";
                        }
                        throw new ServerMaintenanceException(message);
                    }
                }
                throw new HttpStatusException(status);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    listener.checkpoint();
                    response.append(line);
                }
                return response.toString();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readErrorBody(HttpURLConnection connection) throws IOException {
        InputStream error = connection.getErrorStream();
        if (error == null) return "";
        try (InputStream input = error) {
            byte[] bytes = input.readNBytes(65537);
            return bytes.length > 65536 ? "" : new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private void feedback(DownloadState state, int attempt, int maximum, long value) {
        synchronized (feedbackLock) { listener.onDownloadState(state, attempt, maximum, value); }
    }

    private void retryDelay(int attempt) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retryDelayMillis * (attempt - 1));
        while (System.nanoTime() < deadline) {
            listener.checkpoint();
            try { Thread.sleep(50); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); listener.checkpoint(); return; }
        }
    }

    private static final class IntegrityException extends IOException {
        IntegrityException(String message) { super(message); }
    }
    private static final class TransferResult {
        final long total;
        final String validator;
        TransferResult(long total, String validator) { this.total = total; this.validator = validator; }
    }
    private static final class TransferException extends IOException {
        final IOException original;
        final FailureReason reason;
        final long total;
        final String validator;
        TransferException(IOException original, FailureReason reason, long total, String validator) {
            this.original = original; this.reason = reason; this.total = total; this.validator = validator;
        }
    }

    private String sha256(File destination) throws IOException {
        try (InputStream input = new FileInputStream(destination)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) { listener.checkpoint(); digest.update(buffer, 0, count); }
            StringBuilder output = new StringBuilder();
            for (byte value : digest.digest()) output.append(String.format("%02x", value & 0xff));
            return output.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }

    private final class WaitNotice implements AutoCloseable {
        final int attempt, maximum;
        long lastData = System.nanoTime();
        boolean waiting, receivingStarted, closed;
        final ScheduledFuture<?> timer;
        WaitNotice(int attempt, int maximum) {
            this.attempt = attempt; this.maximum = maximum;
            timer = WAIT_MONITOR.scheduleAtFixedRate(() -> {
                synchronized (feedbackLock) {
                    if (closed || listener.isPaused()) { lastData = System.nanoTime(); return; }
                    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastData);
                    if (elapsed >= waitingNoticeMillis) {
                        waiting = true;
                        listener.onDownloadState(DownloadState.WAITING, attempt, maximum, elapsed / 1000);
                    }
                }
            }, 1, 1, TimeUnit.SECONDS);
        }
        void progress(long total, long received, boolean newData) {
            synchronized (feedbackLock) {
                if (newData) {
                    lastData = System.nanoTime();
                    if (!receivingStarted || waiting) {
                        receivingStarted = true;
                        waiting = false;
                        listener.onDownloadState(DownloadState.RECEIVING, attempt, maximum, received);
                    }
                }
                listener.onDownloadProgress(total, received);
            }
        }
        public void close() { synchronized (feedbackLock) { closed = true; } timer.cancel(false); }
    }

    private TransferResult download(String url, File destination, long offset, long expectedTotal,
                                    String validator, int attempt, int maximum) throws TransferException {
        HttpURLConnection connection = null;
        long total = expectedTotal;
        String nextValidator = validator;
        FailureReason reason = FailureReason.NETWORK;
        WaitNotice notice = new WaitNotice(attempt, maximum);
        try {
            listener.checkpoint();
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0) {
                connection.setRequestProperty("Range", "bytes=" + offset + "-");
                if (validator != null) connection.setRequestProperty("If-Range", validator);
            }
            feedback(offset > 0 ? DownloadState.RESUMING : attempt > 1 ? DownloadState.RESTARTING : DownloadState.RECEIVING,
                    attempt, maximum, offset);
            int response = connection.getResponseCode();
            long contentLength = connection.getContentLengthLong();
            long expectedResponseBytes = contentLength;
            boolean append = false;
            long responseBytes = 0;
            if (response == 206) {
                Matcher range = CONTENT_RANGE.matcher(String.valueOf(connection.getHeaderField("Content-Range")));
                if (!range.matches()) throw new IntegrityException("Invalid Content-Range");
                try {
                    long start = Long.parseLong(range.group(1)), end = Long.parseLong(range.group(2)), full = Long.parseLong(range.group(3));
                    if (start != offset || end < start || end >= full || (expectedTotal > 0 && full != expectedTotal)
                            || (contentLength >= 0 && contentLength != end - start + 1)) throw new IntegrityException("Unexpected Content-Range");
                    total = full;
                    expectedResponseBytes = end - start + 1;
                    append = offset > 0;
                } catch (NumberFormatException invalid) { throw new IntegrityException("Invalid Content-Range"); }
            } else if (response == 200) {
                total = expectedTotal > 0 ? expectedTotal : Math.max(0, contentLength);
                if (offset > 0) feedback(DownloadState.RESTARTING, attempt, maximum, 0);
                offset = 0;
            } else if (response == 416 && total > 0 && offset == total && destination.length() == total
                    && ("bytes */" + total).equals(connection.getHeaderField("Content-Range"))) {
                return new TransferResult(total, validator);
            } else {
                reason = FailureReason.HTTP;
                throw new IOException("HTTP " + response);
            }
            String tag = connection.getHeaderField("ETag");
            if (append && validator != null && tag != null && !validator.equals(tag)) {
                throw new IntegrityException("Resource validator changed during resume");
            }
            nextValidator = tag != null && tag.startsWith("\"") && tag.endsWith("\"") ? tag : append ? validator : null;
            String encoding = connection.getHeaderField("Content-Encoding");
            if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new IntegrityException("Unexpected Content-Encoding: " + encoding);
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = openDestination(destination, append)) {
                notice.progress(total, offset, false);
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    listener.checkpoint();
                    if (total > 0 && read > total - offset - responseBytes) throw new IntegrityException("Response exceeds expected file size");
                    if (expectedResponseBytes >= 0 && read > expectedResponseBytes - responseBytes) {
                        throw new IntegrityException("Response exceeds declared byte range");
                    }
                    try { output.write(buffer, 0, read); }
                    catch (IOException disk) { reason = FailureReason.LOCAL_IO; throw disk; }
                    responseBytes += read;
                    notice.progress(total, offset + responseBytes, read > 0);
                }
                if ((expectedResponseBytes >= 0 && responseBytes != expectedResponseBytes) || (total > 0 && offset + responseBytes < total)) {
                    throw new EOFException("Incomplete response: received " + (offset + responseBytes) + "/" + total + " bytes");
                }
            }
            return new TransferResult(total, nextValidator);
        } catch (IOException e) {
            if (e instanceof java.net.SocketTimeoutException) reason = FailureReason.TIMEOUT;
            if (e instanceof LocalWriteException) reason = FailureReason.LOCAL_IO;
            if (e instanceof IntegrityException) reason = FailureReason.INTEGRITY;
            throw new TransferException(e, reason, total, nextValidator);
        } finally {
            notice.close();
            if (connection != null) connection.disconnect();
        }
    }
    private static final class LocalWriteException extends IOException {
        LocalWriteException(IOException cause) { super(cause.getMessage(), cause); }
    }
    private FileOutputStream openDestination(File destination, boolean append) throws IOException {
        try { return new FileOutputStream(destination, append) {
            @Override public void close() throws IOException {
                try { super.close(); }
                catch (IOException failure) { throw new LocalWriteException(failure); }
            }
        }; }
        catch (IOException failure) { throw new LocalWriteException(failure); }
    }
}
