/**
 * Callback interface used by the update business layer to report status,
 * progress and log messages to the UI.
 *
 * No Swing types appear here — the UI layer is responsible for marshalling
 * these callbacks to its event dispatch thread.
 */
interface UpdateListener {

    /** Status text and whether the overall progress bar should be indeterminate. */
    void onStatus(String status, boolean indeterminate);

    /** Append a line to the update log. */
    void onLog(String message);

    /** Overall progress percentage (0-100). */
    void onOverallProgress(int percent);

    /** The active server changed (e.g. after a multi-server fallback). */
    void onServerChanged();

    /** A per-file / agent download has started. */
    void onDownloadStarted();

    /** A per-file / agent download has completed. */
    void onDownloadComplete();
}
