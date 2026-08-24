import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;

/**
 * JavaFX implementation of the toolkit-agnostic {@link UpdateView} contract,
 * parallel to the Swing {@link UpdateGUI}.
 *
 * Pure View: it creates the Stage/Scene, renders the six update phases and
 * forwards user actions (window close, debug close button) to a
 * {@link UpdateViewListener}. It holds no reference to the
 * {@link UpdateService}, owns no threads and never queries business state —
 * everything displayed arrives through the view callbacks. All methods must be
 * invoked on the JavaFX Application Thread; the {@link UpdateController}
 * guarantees this by marshalling every call through a {@link UiDispatcher}.
 *
 * The six visual phases:
 * <ul>
 *   <li>{@code CHECKING}  — fetching the manifest (indeterminate bar)</li>
 *   <li>{@code DOWNLOADING} — downloading a regular managed file</li>
 *   <li>{@code UPDATER}   — downloading the agent self-update</li>
 *   <li>{@code CLEANING}  — removing stale files (indeterminate bar)</li>
 *   <li>{@code SUCCESS}   — flow completed (bar at 100%)</li>
 *   <li>{@code ERROR}     — flow failed (bar hidden, error summary)</li>
 * </ul>
 *
 * This is a functionally-correct skeleton: default JavaFX look, no CSS,
 * animations or icons.
 */
class JavaFxUpdateView implements UpdateView {

    /** The six visual phases the update flow can be in. */
    enum Phase {
        CHECKING, DOWNLOADING, UPDATER, CLEANING, SUCCESS, ERROR
    }

    private final Stage stage;
    private final UpdateViewListener listener;
    private final boolean debug;

    // Overall progress area
    private final Label lblStatus = new Label("Checking for updates...");
    private final Label lblDescription = new Label("");
    private final HBox overallArea = new HBox(6);
    private final ProgressBar overallBar = new ProgressBar(0);
    private final Label lblOverallPct = new Label("");

    // Current-file / per-download area
    private final VBox dlArea = new VBox(4);
    private final Label lblDlKind = new Label();
    private final Label lblDlFile = new Label();
    private final ProgressBar dlBar = new ProgressBar(0);
    private final Label lblDlSpeed = new Label("");

    // Details area (Server URL, Game Directory, full log)
    private final TitledPane detailsPane = new TitledPane("Details", null);
    private final Label lblServer = new Label("Server: -");
    private final Label lblGameDir = new Label();
    private final TextArea logArea = new TextArea();

    // Debug close button
    private final Button btnClose = new Button("Close");

    private Phase phase = Phase.CHECKING;

    JavaFxUpdateView(UpdateViewListener listener, UiModel model) {
        this.listener = listener;
        this.debug = model.debug;
        this.stage = new Stage();
        initUI(model);
    }

    // ── UpdateView ────────────────────────────────────────────────

    /**
     * Update the status text, optional description and whether the overall bar
     * is indeterminate. Also classifies the flow phase: the cleaning status
     * (sent explicitly by the business layer) and the indeterminate checking
     * status are the two phases that arrive here.
     */
    @Override
    public void showStatus(String status, String description, boolean indeterminate) {
        lblStatus.setText(status);
        lblDescription.setText(description == null ? "" : description);
        if (indeterminate) {
            overallBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        }
        if (status != null && status.contains("Cleaning")) {
            setPhase(Phase.CLEANING);
        } else if (indeterminate) {
            setPhase(Phase.CHECKING);
        }
    }

    /** Append one log line to the Details log. */
    @Override
    public void showLog(String message) {
        logArea.appendText(message + "\n");
    }

    /** Set the overall progress percentage (0-100). */
    @Override
    public void showOverallProgress(int percent) {
        int p = clamp(percent);
        overallBar.setProgress(p / 100.0);
        lblOverallPct.setText(p + "%");
    }

    /**
     * Present a per-file / agent download snapshot. An inactive snapshot hides
     * and clears the current-file area; an active one switches the phase to
     * DOWNLOADING or UPDATER based on the download kind.
     */
    @Override
    public void showDownloadProgress(DownloadProgress progress) {
        if (!progress.active) {
            hideDownloadArea();
            return;
        }
        setPhase(progress.kind == DownloadProgress.Kind.UPDATER
                ? Phase.UPDATER : Phase.DOWNLOADING);
        lblDlKind.setText(progress.kind == DownloadProgress.Kind.UPDATER
                ? "Updating updater…"
                : "Downloading update…");
        lblDlFile.setText(progress.path == null ? "" : progress.path);
        if (progress.totalBytes > 0) {
            int pct = clamp((int) (progress.downloadedBytes * 100 / progress.totalBytes));
            dlBar.setProgress(pct / 100.0);
        } else {
            dlBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        }
        lblDlSpeed.setText(FormatUtil.formatSpeed(progress.bytesPerSecond));
        showDownloadArea();
    }

    /** Present the server state carried by the event (inside Details). */
    @Override
    public void showServer(List<String> serverUrls, String currentServer) {
        lblServer.setText(serverUrls.size() <= 1
                ? "Server: " + currentServer
                : "Servers (" + serverUrls.size() + "): " + currentServer);
    }

    /**
     * The update completed — render the SUCCESS state with a full bar and a
     * success summary. Flow control after completion is the application's job.
     */
    @Override
    public void showCompleted(UpdateResult result) {
        setPhase(Phase.SUCCESS);
        overallBar.setProgress(1.0);
        lblOverallPct.setText("100%");
        if (result.failed > 0) {
            lblStatus.setText("Update finished with " + result.failed + " error(s)");
            lblDescription.setText("");
        } else if (result.updated > 0) {
            lblStatus.setText("Updated " + result.updated + " file(s), launching Minecraft...");
            lblDescription.setText("");
        } else {
            lblStatus.setText("Already up to date, launching Minecraft...");
            lblDescription.setText("");
        }
        if (debug) {
            setCloseEnabled(true);
            showLog("[DEBUG] Update check done. Window stays open for inspection.");
        }
    }

    /**
     * The update failed — render the ERROR state: overall bar hidden, an error
     * summary as the description, and the Details area expanded so the log is
     * reachable. v1 implements no Retry.
     */
    @Override
    public void showError(String message, Throwable cause) {
        String msg = message == null ? "Unknown error" : message;
        setPhase(Phase.ERROR);
        lblStatus.setText("Update failed");
        lblDescription.setText(msg);
        showLog("[ERROR] " + msg);
    }

    /** Enable or disable the debug close button. */
    @Override
    public void setCloseEnabled(boolean enabled) {
        btnClose.setDisable(!enabled);
    }

    /** Show the window. Must be called on the JavaFX Application Thread. */
    @Override
    public void open() {
        stage.show();
    }

    /** Close the window. Must be called on the JavaFX Application Thread. */
    @Override
    public void close() {
        stage.close();
    }

    // ── Phase rendering ───────────────────────────────────────────

    /** Track the current phase and apply the phase-specific rendering. */
    private void setPhase(Phase p) {
        if (phase == p) {
            return;
        }
        phase = p;
        switch (p) {
            case CHECKING:
            case CLEANING:
            case SUCCESS:
                hideDownloadArea();
                break;
            case ERROR:
                hideDownloadArea();
                // Error hides the overall progress bar and points at Details.
                overallArea.setVisible(false);
                overallArea.setManaged(false);
                detailsPane.setExpanded(true);
                break;
            case DOWNLOADING:
            case UPDATER:
                // Current-file area is shown by showDownloadProgress.
                break;
        }
    }

    private void showDownloadArea() {
        dlArea.setVisible(true);
        dlArea.setManaged(true);
    }

    /** Hide and clear the current-file area. */
    private void hideDownloadArea() {
        dlArea.setVisible(false);
        dlArea.setManaged(false);
        lblDlKind.setText("");
        lblDlFile.setText("");
        dlBar.setProgress(0);
        lblDlSpeed.setText("");
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    // ── Construction ──────────────────────────────────────────────

    private void initUI(UiModel model) {
        stage.setTitle("Minecraft Update Check");
        // Forward the user closing the window to the flow controller.
        stage.setOnCloseRequest(e -> listener.onWindowClosed());

        // Overall progress area: bar + percent label.
        lblOverallPct.setPrefWidth(44);
        HBox.setHgrow(overallBar, Priority.ALWAYS);
        overallArea.getChildren().addAll(overallBar, lblOverallPct);
        overallArea.setAlignment(Pos.CENTER_LEFT);
        overallBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

        // Current-file area: kind, path, bar, speed. Hidden until a download
        // becomes active.
        dlArea.getChildren().addAll(lblDlKind, lblDlFile, dlBar, lblDlSpeed);
        dlArea.setVisible(false);
        dlArea.setManaged(false);

        // Details area: Server URL, Game Directory and the full log. Collapsed
        // in normal mode, expanded in debug mode (and on error).
        lblGameDir.setText("Game dir: " + model.gameDir);
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.setPrefRowCount(6);
        VBox detailsContent = new VBox(6, lblServer, lblGameDir, logArea);
        detailsPane.setContent(detailsContent);
        detailsPane.setExpanded(debug);

        // Root layout.
        VBox root = new VBox(8, lblStatus, lblDescription, overallArea, dlArea, detailsPane);
        root.setPadding(new Insets(10));

        // Debug close button — only present in debug mode, enabled by the
        // controller once the flow allows the user to close.
        if (debug) {
            btnClose.setDisable(true);
            btnClose.setOnAction(e -> listener.onCloseRequested());
            HBox bottom = new HBox(btnClose);
            bottom.setAlignment(Pos.CENTER_RIGHT);
            root.getChildren().add(bottom);
        }

        stage.setScene(new Scene(root, 520, 420));
    }
}
