package com.zack88604.autoupdater.gui.preset;

import com.zack88604.autoupdater.infrastructure.json.StrictJson;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Metadata for one GUI preset published by a configured update server.
 *
 * <p>The descriptor supplies transfer integrity metadata only. The operator's
 * update server is the trust boundary; this value object never loads the
 * archive.</p>
 */
public final class ServerGuiPresetOffer {

    private static final String DOWNLOAD_PREFIX = "/api/v2/gui-presets/";
    private static final Pattern SAFE_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern SAFE_ARCHIVE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.jar");
    private static final Pattern SHA256 = Pattern.compile("[0-9A-Fa-f]{64}");

    private final String id;
    private final String version;
    private final String downloadPath;
    private final String sha256;
    private final long size;
    private final GuiPlatform platform;
    private final int minimumJavaVersion;

    private ServerGuiPresetOffer(String id, String version, String downloadPath,
                                 String sha256, long size, GuiPlatform platform,
                                 int minimumJavaVersion) {
        this.id = id;
        this.version = version;
        this.downloadPath = downloadPath;
        this.sha256 = sha256.toLowerCase(Locale.ROOT);
        this.size = size;
        this.platform = platform;
        this.minimumJavaVersion = minimumJavaVersion;
    }

    /** Parse and validate one server descriptor JSON document. */
    public static ServerGuiPresetOffer parse(String json) {
        Objects.requireNonNull(json, "json");
        Map<String, Object> values = StrictJson.parseObject(json);
        return parseOffer(string(values, "id"), string(values, "version"), values, null, 8);
    }

    /** Validate every variant and select only the current JVM's exact platform. */
    public static ServerGuiPresetOffer parseCatalog(String json, GuiPlatform current) {
        Map<String, Object> root = StrictJson.parseObject(json);
        String id = string(root, "id");
        String version = string(root, "version");
        Map<String, Object> variants = StrictJson.object(root.get("variants"));
        if (variants.isEmpty() || variants.size() > 64) {
            throw new IllegalArgumentException("Invalid GUI preset variant count");
        }
        ServerGuiPresetOffer selected = null;
        for (Map.Entry<String, Object> entry : variants.entrySet()) {
            Map<String, Object> values = StrictJson.object(entry.getValue());
            GuiPlatform platform = GuiPlatform.declared(string(values, "os"), string(values, "arch"));
            if (!entry.getKey().equals(platform.getId())) {
                throw new IllegalArgumentException("GUI preset platform key does not match metadata");
            }
            long minimum = integer(values, "minimum_java_version");
            if (minimum < 8 || minimum > 99) {
                throw new IllegalArgumentException("Invalid GUI preset minimum Java version");
            }
            ServerGuiPresetOffer offer = parseOffer(id, version, values, platform, (int) minimum);
            if (platform.equals(current) && GuiPlatform.currentJavaVersion() >= minimum) selected = offer;
        }
        return selected;
    }

    private static ServerGuiPresetOffer parseOffer(String id, String version,
                                                    Map<String, Object> values,
                                                    GuiPlatform platform, int minimum) {
        String path = string(values, "path");
        String hash = string(values, "sha256");
        long size = integer(values, "size");

        if (!SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid server GUI preset identifier");
        }
        if (version.length() > 128 || !version.equals(version.trim()) || containsControlCharacter(version)) {
            throw new IllegalArgumentException("Invalid server GUI preset version");
        }
        if (!isValidDownloadPath(path)) {
            throw new IllegalArgumentException("Invalid server GUI preset download path");
        }
        if (!SHA256.matcher(hash).matches() || size < 0) {
            throw new IllegalArgumentException("Invalid server GUI preset hash or size");
        }
        return new ServerGuiPresetOffer(id, version, path, hash, size, platform, minimum);
    }

    /** Return the stable server-side preset identifier. */
    public String getId() {
        return id;
    }

    /** Return the human-readable preset version. */
    public String getVersion() {
        return version;
    }

    /** Return the relative HTTP download path. */
    public String getDownloadPath() {
        return downloadPath;
    }

    /** Return the expected SHA-256 hash in lowercase hexadecimal form. */
    public String getSha256() {
        return sha256;
    }

    /** Return the expected archive size in bytes. */
    public long getSize() {
        return size;
    }

    /** Return the reserved local archive name used for this server preset. */
    public String getArchiveName() {
        return "server-" + id + (platform == null ? "" : "-" + platform.getId()) + ".jar";
    }

    public GuiPlatform getPlatform() { return platform; }
    public int getMinimumJavaVersion() { return minimumJavaVersion; }

    private static String string(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IllegalArgumentException("Missing server GUI preset " + name);
        }
        return (String) value;
    }

    private static long integer(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (!(value instanceof Long)) {
            throw new IllegalArgumentException("Invalid server GUI preset " + name);
        }
        return (Long) value;
    }

    private static boolean isValidDownloadPath(String path) {
        if (!path.startsWith(DOWNLOAD_PREFIX)) {
            return false;
        }
        String archive = path.substring(DOWNLOAD_PREFIX.length());
        return SAFE_ARCHIVE.matcher(archive).matches();
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}
