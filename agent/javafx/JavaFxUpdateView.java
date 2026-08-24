import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * The six main visual phases (see {@link UpdatePhase}):
 * <ul>
 *   <li>{@code PREPARING} — fetching the manifest and running the self-update
 *       check (indeterminate bar). The updater download is a sub-state of this
 *       phase, distinguished by {@link DownloadProgress.Kind#UPDATER} and shown
 *       through the current-file area.</li>
 *   <li>{@code CHECKING}   — hashing managed files against the manifest</li>
 *   <li>{@code DOWNLOADING}— downloading a regular managed file</li>
 *   <li>{@code CLEANING}   — removing stale files (indeterminate bar)</li>
 *   <li>{@code SUCCESS}    — flow completed with no failed files (bar at 100%)</li>
 *   <li>{@code ERROR}      — flow failed — exception or partial failure (bar
 *                            hidden, error summary)</li>
 * </ul>
 *
 * The phase is carried explicitly by {@link UpdateEvent.StatusChanged}, so the
 * view never infers it from status text.
 *
 * While an update is in progress (PREPARING/CHECKING/DOWNLOADING/CLEANING) the
 * window close request is intercepted and the user must confirm quitting; in
 * the terminal SUCCESS/ERROR phases the close request is honoured directly.
 *
 * The view is styled entirely from {@code /ui.css}; it
 * adds no icons, animations or gradients of its own. Terminal state classes
 * ({@code success-state} / {@code error-state}) are maintained on the root by
 * {@link #setPhase}, and the Quit-update confirmation shares the same
 * stylesheet.
 */
class JavaFxUpdateView implements UpdateView {

    private final Stage stage;
    private final UpdateViewListener listener;
    private final boolean debug;

    // Window sizing: normal mode is deliberately short (Details collapsed);
    // expanding Details (debug mode or error state) grows the window.
    private static final double WINDOW_WIDTH = 520;
    private static final double WINDOW_HEIGHT_COLLAPSED = 300;
    private static final double WINDOW_HEIGHT_EXPANDED = 460;

    // Overall progress area
    private final Label lblStatus = new Label("Preparing update…");
    private final Label lblDescription = new Label("");
    private final HBox overallArea = new HBox(6);
    private final ProgressBar overallBar = new ProgressBar(0);
    private final Label lblOverallPct = new Label("");

    // Current-file / per-download area
    private final VBox dlArea = new VBox(4);
    private final Label lblDlFile = new Label();
    private final ProgressBar dlBar = new ProgressBar(0);
    private final Label lblDlSpeed = new Label("");

    // Counters backing the informational subtitles (see showStatus).
    private int filesSeen;   // per-file downloads started in this run
    private int filesTotal;  // managed file count, captured from CHECKING text

    // Details area (Server URL, Game Directory, full log)
    private final TitledPane detailsPane = new TitledPane("Details", null);
    private final Label lblServer = new Label("Server: -");
    private final Label lblGameDir = new Label();
    private final TextArea logArea = new TextArea();

    // Debug close button
    private final Button btnClose = new Button("Close");

    // Root layout — carries the .success-state / .error-state state classes.
    private final VBox root = new VBox(8);

    /** External form of /ui.css, or null if the stylesheet is missing. */
    private final String stylesheet;

    /** The scene backing the window; resized when Details expands/collapses. */
    private Scene scene;

    /** The destructive "Skip update" action, created per Quit-alert instance. */
    private ButtonType quitSkipType;

    private UpdatePhase phase = UpdatePhase.PREPARING;

    JavaFxUpdateView(UpdateViewListener listener, UiModel model) {
        this.listener = listener;
        this.debug = model.debug;
        this.stage = new Stage();
        java.net.URL css = getClass().getResource("/ui.css");
        this.stylesheet = css == null ? null : css.toExternalForm();
        initUI(model);
    }

    // ── UpdateView ────────────────────────────────────────────────

    /**
     * Rebuild the status hierarchy from the business event. The raw business
     * status string is never shown verbatim as the visual title: each phase
     * maps to a stable main title, and the count / detail is re-worded into the
     * subtitle below it. The file path stays in the current-file area, never in
     * the title.
     */
    @Override
    public void showStatus(UpdatePhase phase, String status, String description, boolean indeterminate) {
        if (indeterminate) {
            overallBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        }
        setPhase(phase);
        switch (phase) {
            case PREPARING:
                // A new run starts here — reset the file counters.
                filesSeen = 0;
                filesTotal = 0;
                lblStatus.setText("Preparing update…");
                lblDescription.setText("Connecting to update server");
                break;
            case CHECKING: {
                int[] counts = extractFileCounts(status);
                if (counts != null) {
                    filesTotal = counts[1];
                }
                lblStatus.setText("Checking files…");
                lblDescription.setText(counts != null
                        ? formatCount(counts[0]) + " of " + formatCount(counts[1]) + " files checked"
                        : "Checking files…");
                break;
            }
            case DOWNLOADING:
                filesSeen++;
                lblStatus.setText("Downloading update…");
                lblDescription.setText(filesTotal > 0
                        ? formatCount(filesSeen) + " of " + formatCount(filesTotal) + " files"
                        : formatCount(filesSeen) + " file(s)");
                break;
            case CLEANING:
                lblStatus.setText("Cleaning up…");
                lblDescription.setText(description == null || description.isEmpty()
                        ? "Removing files that are no longer needed"
                        : description);
                break;
            default:
                // Terminal phases are rendered by showCompleted / showError.
                lblStatus.setText(status);
                lblDescription.setText(description == null ? "" : description);
                break;
        }
    }

    /** Append one log line to the Details log. */
    @Override
    public void showLog(String message) {
        logArea.appendText(message + "\n");
    }

    /**
     * Set the overall progress percentage (0-100). PREPARING and CLEANING keep
     * an indeterminate bar with the percentage hidden; only the determinate
     * CHECKING / DOWNLOADING phases display a percentage.
     */
    @Override
    public void showOverallProgress(int percent) {
        if (phase == UpdatePhase.PREPARING || phase == UpdatePhase.CLEANING) {
            return;
        }
        int p = clamp(percent);
        overallBar.setProgress(p / 100.0);
        lblOverallPct.setText(p + "%");
    }

    /**
     * Present a per-file / agent download snapshot. An inactive snapshot hides
     * and clears the current-file area; an active one switches the phase to
     * DOWNLOADING for a regular managed file, or stays in PREPARING for the
     * updater self-update (a sub-state of PREPARING), and shows the
     * current-file area with the object name, progress and speed. The file or
     * JAR name lives only in this area — never in the status title.
     */
    @Override
    public void showDownloadProgress(DownloadProgress progress) {
        if (!progress.active) {
            hideDownloadArea();
            return;
        }
        boolean updater = progress.kind == DownloadProgress.Kind.UPDATER;
        setPhase(updater ? UpdatePhase.PREPARING : UpdatePhase.DOWNLOADING);
        if (updater) {
            // The updater self-update never exposes the "agent" jargon in the
            // normal UI — the title and subtitle stay user-facing.
            lblStatus.setText("Updating updater…");
            lblDescription.setText("Preparing update components");
        }
        lblDlFile.setText(progress.path == null ? "" : progress.path);
        if (progress.totalBytes > 0) {
            int pct = clamp((int) (progress.downloadedBytes * 100 / progress.totalBytes));
            dlBar.setProgress(pct / 100.0);
        } else {
            // Unknown content-length: indeterminate per-file bar.
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
     * The update completed. A fully successful run ({@code failed == 0}) renders
     * the SUCCESS state with a full bar and a success summary; a partial failure
     * ({@code failed > 0}) renders the ERROR state — the same visual base state
     * as an exception failure, with the overall bar hidden, Details expanded and
     * the failure count shown as the error summary. Flow control after
     * completion is the application's job.
     */
    @Override
    public void showCompleted(UpdateResult result) {
        if (result.failed > 0) {
            // Partial failure — reuse the ERROR visual base state shared with
            // exception failures: setPhase(ERROR) hides the overall bar, resets
            // any residue, expands Details and hides the current-file area. The
            // failure count becomes the error summary, mirroring showError().
            setPhase(UpdatePhase.ERROR);
            lblStatus.setText("Update failed");
            lblDescription.setText(result.failed + " file(s) failed to update.");
            showLog("[ERROR] " + result.failed + " file(s) failed to update.");
        } else {
            // Success is split into a main title and a subtitle so the green
            // accent marks only the headline, not the whole sentence.
            setPhase(UpdatePhase.SUCCESS);
            overallBar.setProgress(1.0);
            lblOverallPct.setText("100%");
            if (result.updated > 0) {
                lblStatus.setText("Update complete");
                lblDescription.setText(formatFiles(result.updated) + " updated · Launching Minecraft…");
            } else {
                lblStatus.setText("You're up to date");
                lblDescription.setText("Launching Minecraft…");
            }
        }
        if (debug) {
            setCloseEnabled(true);
            showLog("[DEBUG] Update check done. Window stays open for inspection.");
        }
    }

    /**
     * The update failed — render the ERROR state: overall bar reset and hidden,
     * an error summary as the description, and the Details area expanded so the
     * log is reachable. v1 implements no Retry.
     */
    @Override
    public void showError(String message, Throwable cause) {
        String msg = message == null ? "Unknown error" : message;
        setPhase(UpdatePhase.ERROR);
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

    /**
     * Track the current phase and apply the phase-specific rendering. The root
     * carries the terminal state classes ({@code success-state} /
     * {@code error-state}); ui.css derives the title colour and related state
     * visuals from them via {@code .root.success-state ...} /
     * {@code .root.error-state ...}. The mid-flow phases carry neither class.
     */
    private void setPhase(UpdatePhase p) {
        if (phase == p) {
            return;
        }
        phase = p;
        root.getStyleClass().removeAll("success-state", "error-state");
        switch (p) {
            case PREPARING:
            case CLEANING:
                // Indeterminate phases hide the percentage entirely, so a stale
                // value (e.g. the previous 55%) never lingers beside the bar.
                hideDownloadArea();
                clearOverallPercent();
                logArea.setPrefRowCount(6);
                break;
            case CHECKING:
            case DOWNLOADING:
                // Determinate phases show the percentage. The current-file area
                // is (re)shown by showDownloadProgress for DOWNLOADING.
                hideDownloadArea();
                showOverallPercent();
                logArea.setPrefRowCount(6);
                break;
            case SUCCESS:
                hideDownloadArea();
                showOverallPercent();
                logArea.setPrefRowCount(6);
                root.getStyleClass().add("success-state");
                break;
            case ERROR:
                hideDownloadArea();
                // Error hides the overall progress bar, resets any residue
                // (e.g. a previous 100%), expands Details and shows a few more
                // log rows for the failure context.
                overallBar.setProgress(0);
                lblOverallPct.setText("");
                overallArea.setVisible(false);
                overallArea.setManaged(false);
                detailsPane.setExpanded(true);
                logArea.setPrefRowCount(10);
                root.getStyleClass().add("error-state");
                break;
        }
    }

    /** Hide the overall percentage label, clearing any stale text. */
    private void clearOverallPercent() {
        lblOverallPct.setText("");
        lblOverallPct.setVisible(false);
        lblOverallPct.setManaged(false);
    }

    /** Show the overall percentage label next to the bar. */
    private void showOverallPercent() {
        lblOverallPct.setVisible(true);
        lblOverallPct.setManaged(true);
    }

    private void showDownloadArea() {
        dlArea.setVisible(true);
        dlArea.setManaged(true);
    }

    /** Hide and clear the current-file area. */
    private void hideDownloadArea() {
        dlArea.setVisible(false);
        dlArea.setManaged(false);
        lblDlFile.setText("");
        dlBar.setProgress(0);
        lblDlSpeed.setText("");
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    /** Business CHECKING status carries "{checked}/{total}", e.g. "Checked: 247/1247". */
    private static final Pattern FILE_COUNT_PATTERN = Pattern.compile("(\\d+)/(\\d+)");

    /** Parse "{checked}/{total}" out of a business CHECKING status string. */
    private static int[] extractFileCounts(String status) {
        if (status == null) {
            return null;
        }
        Matcher m = FILE_COUNT_PATTERN.matcher(status);
        if (m.find()) {
            try {
                return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Render a count with thousands grouping, e.g. 1247 → "1,247". */
    private static String formatCount(int n) {
        return String.format("%,d", n);
    }

    /** Natural plural for a file count, e.g. 1 → "1 file", 3 → "3 files". */
    private static String formatFiles(int n) {
        return formatCount(n) + (n == 1 ? " file" : " files");
    }

    // ── Window close handling ─────────────────────────────────────

    /** True while the update flow is still running (non-terminal phases). */
    private boolean isUpdateInProgress() {
        return phase == UpdatePhase.PREPARING
                || phase == UpdatePhase.CHECKING
                || phase == UpdatePhase.DOWNLOADING
                || phase == UpdatePhase.CLEANING;
    }

    /**
     * Intercept the window close request. While an update is running the close
     * is consumed and the user is asked to confirm; the terminal SUCCESS/ERROR
     * phases close directly without a second prompt.
     */
    private void onCloseRequestedByUser(javafx.event.Event event) {
        if (isUpdateInProgress()) {
            event.consume();
            confirmQuit();
        } else {
            listener.onWindowClosed();
        }
    }

    /**
     * Ask whether to abandon the running update. The default action stays with
     * the update; only an explicit "Skip update" invokes the existing
     * {@link UpdateViewListener} close flow. Closing the dialog also counts as
     * staying. The dialog shares the main window's stylesheet, and the skip
     * button gets the {@code danger-button} class so it renders as the
     * destructive action.
     */
    private void confirmQuit() {
        Alert alert = createQuitAlert();
        alert.showAndWait().ifPresent(choice -> {
            if (choice == quitSkipType) {
                listener.onWindowClosed();
                stage.close();
            }
        });
    }

    /**
     * Build the "Quit update?" confirmation. Exposed as a factory (rather than
     * constructed inline) so the screenshot harness can render it. Uses
     * {@link Alert.AlertType#NONE} so no default Question icon appears — the
     * dialog is deliberately icon-free to match the flat visual system.
     */
    Alert createQuitAlert() {
        Alert alert = new Alert(Alert.AlertType.NONE);
        alert.setTitle("Quit update?");
        alert.setHeaderText(null);
        alert.setContentText("The update is still in progress. Skipping it may leave Minecraft out of date.");
        ButtonType stay = new ButtonType("Keep updating", ButtonBar.ButtonData.OK_DONE);
        quitSkipType = new ButtonType("Skip update", ButtonBar.ButtonData.OTHER);
        alert.getButtonTypes().setAll(stay, quitSkipType);
        alert.initOwner(stage);
        // Apply the same visual system as the main window.
        if (stylesheet != null) {
            alert.getDialogPane().getStylesheets().add(stylesheet);
        }
        alert.getDialogPane().getStyleClass().add("root");
        // "Skip update" is the destructive action — style it red.
        Button skipButton = (Button) alert.getDialogPane().lookupButton(quitSkipType);
        skipButton.getStyleClass().add("danger-button");
        // Enter / the default stays with the update.
        ((Button) alert.getDialogPane().lookupButton(stay)).setDefaultButton(true);
        return alert;
    }

    // ── Construction ──────────────────────────────────────────────

    private void initUI(UiModel model) {
        stage.setTitle("Minecraft Update Check");
        // Forward the user closing the window to the flow controller, gated by
        // the in-progress confirmation.
        stage.setOnCloseRequest(e -> onCloseRequestedByUser(e));

        // Style classes (mapped in ui.css). The root also carries the terminal
        // state classes maintained by setPhase.
        root.getStyleClass().add("root");

        // Overall progress area: bar + percent label.
        lblStatus.getStyleClass().add("status-title");
        lblDescription.getStyleClass().add("status-description");
        overallArea.getStyleClass().add("overall-progress");
        lblOverallPct.getStyleClass().add("pct");
        lblOverallPct.setPrefWidth(44);
        HBox.setHgrow(overallBar, Priority.ALWAYS);
        overallArea.getChildren().addAll(overallBar, lblOverallPct);
        overallArea.setAlignment(Pos.CENTER_LEFT);
        overallBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

        // Current-file area: path, bar, speed. Hidden until a download becomes
        // active; the phase title above already names the action.
        dlArea.getStyleClass().add("file-area");
        lblDlFile.getStyleClass().add("file-path");
        dlBar.getStyleClass().add("file-progress");
        lblDlSpeed.getStyleClass().add("download-speed");
        dlArea.getChildren().addAll(lblDlFile, dlBar, lblDlSpeed);
        dlArea.setVisible(false);
        dlArea.setManaged(false);

        // Details area: Server URL, Game Directory and the full log. Collapsed
        // in normal mode, expanded in debug mode (and on error).
        detailsPane.getStyleClass().add("details-pane");
        lblGameDir.setText("Game dir: " + model.gameDir);
        logArea.getStyleClass().add("log");
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.setPrefRowCount(6);
        VBox detailsContent = new VBox(6, lblServer, lblGameDir, logArea);
        detailsPane.setContent(detailsContent);
        detailsPane.setExpanded(debug);

        // Root layout.
        lblDescription.setWrapText(true);
        // Roomier canvas: wider horizontal padding and a little more vertical
        // breathing room, inside a shorter normal-mode window.
        root.setPadding(new Insets(18, 22, 18, 22));
        root.getChildren().addAll(lblStatus, lblDescription, overallArea, dlArea, detailsPane);

        // Debug close button — only present in debug mode, enabled by the
        // controller once the flow allows the user to close.
        if (debug) {
            btnClose.getStyleClass().add("debug-close-button");
            btnClose.setDisable(true);
            btnClose.setOnAction(e -> listener.onCloseRequested());
            HBox bottom = new HBox(btnClose);
            bottom.setAlignment(Pos.CENTER_RIGHT);
            root.getChildren().add(bottom);
        }

        // Apply the shared visual system (ui.css) — normal and debug alike.
        // Short by default; the window grows when Details expands (debug mode
        // and the error state) so collapsed layouts never sit in a tall window
        // full of dead space.
        scene = new Scene(root, WINDOW_WIDTH,
                debug ? WINDOW_HEIGHT_EXPANDED : WINDOW_HEIGHT_COLLAPSED);
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet);
        }
        stage.setScene(scene);
        stage.setMinWidth(WINDOW_WIDTH - 40);
        stage.setMinHeight(WINDOW_HEIGHT_COLLAPSED);
        detailsPane.expandedProperty().addListener((obs, wasExpanded, expanded) ->
                applyWindowHeight(expanded));
    }

    /**
     * Resize the window so the client area (the scene) matches the target
     * height for the Details state. The stage height includes window chrome,
     * so the chrome is measured from the live window — Details toggles always
     * happen after the window is shown.
     */
    private void applyWindowHeight(boolean expanded) {
        if (stage.getScene() == null || !stage.isShowing()) {
            return;
        }
        double sceneHeight = stage.getScene().getHeight();
        if (sceneHeight <= 0) {
            return;
        }
        double chrome = stage.getHeight() - sceneHeight;
        double target = (expanded ? WINDOW_HEIGHT_EXPANDED : WINDOW_HEIGHT_COLLAPSED) + chrome;
        if (Math.abs(stage.getHeight() - target) > 1.0) {
            stage.setHeight(target);
        }
    }
}
