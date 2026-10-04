package com.zack88604.autoupdater.config;

/** Immutable network settings for one updater launch. */
public final class DownloadSettings {

    public static final int DEFAULT_MAX_NO_PROGRESS_FAILURES = 3;
    public static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    public static final int DEFAULT_MANIFEST_READ_TIMEOUT_SECONDS = 30;
    public static final int DEFAULT_DOWNLOAD_READ_TIMEOUT_SECONDS = 60;
    public static final int DEFAULT_PROGRESS_RESET_THRESHOLD_PERCENT = 1;

    private final int maxNoProgressFailures;
    private final int connectTimeoutMillis;
    private final int manifestReadTimeoutMillis;
    private final int downloadReadTimeoutMillis;
    private final int progressResetThresholdPercent;

    public DownloadSettings(int maxNoProgressFailures, int connectTimeoutSeconds,
                            int manifestReadTimeoutSeconds, int downloadReadTimeoutSeconds,
                            int progressResetThresholdPercent) {
        if (maxNoProgressFailures < 1 || connectTimeoutSeconds < 1
                || manifestReadTimeoutSeconds < 1 || downloadReadTimeoutSeconds < 1
                || progressResetThresholdPercent < 1 || progressResetThresholdPercent > 100) {
            throw new IllegalArgumentException("Invalid download settings");
        }
        this.maxNoProgressFailures = maxNoProgressFailures;
        this.connectTimeoutMillis = Math.multiplyExact(connectTimeoutSeconds, 1000);
        this.manifestReadTimeoutMillis = Math.multiplyExact(manifestReadTimeoutSeconds, 1000);
        this.downloadReadTimeoutMillis = Math.multiplyExact(downloadReadTimeoutSeconds, 1000);
        this.progressResetThresholdPercent = progressResetThresholdPercent;
    }

    public static DownloadSettings defaults() {
        return new DownloadSettings(DEFAULT_MAX_NO_PROGRESS_FAILURES,
                DEFAULT_CONNECT_TIMEOUT_SECONDS, DEFAULT_MANIFEST_READ_TIMEOUT_SECONDS,
                DEFAULT_DOWNLOAD_READ_TIMEOUT_SECONDS, DEFAULT_PROGRESS_RESET_THRESHOLD_PERCENT);
    }

    public int getMaxNoProgressFailures() { return maxNoProgressFailures; }
    public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
    public int getManifestReadTimeoutMillis() { return manifestReadTimeoutMillis; }
    public int getDownloadReadTimeoutMillis() { return downloadReadTimeoutMillis; }
    public int getProgressResetThresholdPercent() { return progressResetThresholdPercent; }
}
