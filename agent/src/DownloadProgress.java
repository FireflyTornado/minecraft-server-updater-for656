/**
 * Mutable per-file download progress snapshot.
 *
 * Written by the update service (worker thread) while a download is in
 * progress and read by the UI refresh timer on the EDT.
 */
class DownloadProgress {
    volatile long totalBytes;
    volatile long downloadedBytes;
    volatile boolean active;
}
