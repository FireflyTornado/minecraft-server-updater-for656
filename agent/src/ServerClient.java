import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HTTP client for the update server with multi-server fallback.
 *
 * Tracks the currently active server and reports log lines and server
 * switches through an {@link UpdateListener}. Contains no Swing dependency.
 */
class ServerClient {

    private final List<String> serverUrls;
    private UpdateListener listener;
    private int currentServerIndex = 0;

    ServerClient(List<String> serverUrls) {
        this.serverUrls = serverUrls;
    }

    void setListener(UpdateListener listener) {
        this.listener = listener;
    }

    List<String> getServerUrls() {
        return serverUrls;
    }

    /** Get the currently active server URL. */
    String getCurrentServer() {
        return serverUrls.get(currentServerIndex);
    }

    /** HTTP GET with fallback: try each server in order until one succeeds. */
    String httpGetWithFallback(String path) throws IOException {
        IOException lastException = null;
        int startIndex = currentServerIndex;
        // Try from current server through the end, then wrap around
        for (int i = 0; i < serverUrls.size(); i++) {
            int idx = (startIndex + i) % serverUrls.size();
            String url = serverUrls.get(idx) + path;
            try {
                if (idx != currentServerIndex) {
                    log("Trying server: " + serverUrls.get(idx));
                }
                String result = httpGet(url);
                // Success — switch to this server for subsequent requests
                if (idx != currentServerIndex) {
                    log("Switched to server: " + serverUrls.get(idx));
                    currentServerIndex = idx;
                    if (listener != null) listener.onServerChanged();
                }
                return result;
            } catch (IOException e) {
                lastException = e;
                log("  [WARN]  Server unreachable: " + serverUrls.get(idx));
            }
        }
        throw lastException != null ? lastException
                : new IOException("All servers unreachable");
    }

    private String httpGet(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Accept", "application/json");
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    /** HTTP download with fallback: try each server in order until one succeeds. */
    boolean httpDownloadWithFallback(String path, File dest, DownloadProgress progress) {
        int startIndex = currentServerIndex;
        for (int i = 0; i < serverUrls.size(); i++) {
            int idx = (startIndex + i) % serverUrls.size();
            String url = serverUrls.get(idx) + path;
            if (idx != currentServerIndex) {
                log("Trying server: " + serverUrls.get(idx));
            }
            if (httpDownload(url, dest, progress)) {
                // Success — switch to this server for subsequent requests
                if (idx != currentServerIndex) {
                    log("Switched to server: " + serverUrls.get(idx));
                    currentServerIndex = idx;
                    if (listener != null) listener.onServerChanged();
                }
                return true;
            }
            log("  [WARN]  Download failed from: " + serverUrls.get(idx));
        }
        return false;
    }

    private boolean httpDownload(String urlStr, File dest, DownloadProgress progress) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            // Use Content-Length from server if available (more accurate)
            int contentLength = conn.getContentLength();
            if (contentLength > 0) progress.totalBytes = contentLength;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    progress.downloadedBytes += n;
                }
            } finally {
                conn.disconnect();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** URL-encode each segment of a path (e.g. "mods/my mod.jar" -> "mods/my%20mod.jar") */
    static String encodePath(String relPath) {
        StringBuilder sb = new StringBuilder();
        for (String seg : relPath.split("/")) {
            if (sb.length() > 0) sb.append('/');
            sb.append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private void log(String msg) {
        if (listener != null) listener.onLog(msg);
    }
}
