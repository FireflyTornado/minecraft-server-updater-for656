/**
 * Immutable per-file download progress snapshot.
 *
 * Computed by the business layer while a download is in progress — including
 * the download speed — and delivered to the UI through
 * {@link UpdateEvent.DownloadProgressChanged} events. The UI never reads this
 * from the business layer; it only receives it.
 */
final class DownloadProgress {

    final boolean active;
    final long downloadedBytes;
    final long totalBytes;
    final double bytesPerSecond;

    DownloadProgress(boolean active, long downloadedBytes, long totalBytes, double bytesPerSecond) {
        this.active = active;
        this.downloadedBytes = downloadedBytes;
        this.totalBytes = totalBytes;
        this.bytesPerSecond = bytesPerSecond;
    }

    /** Snapshot for an active download. */
    static DownloadProgress active(long downloaded, long total, double speed) {
        return new DownloadProgress(true, downloaded, total, speed);
    }

    /** Snapshot meaning "no download in progress". */
    static DownloadProgress inactive() {
        return new DownloadProgress(false, 0, 0, 0);
    }
}
