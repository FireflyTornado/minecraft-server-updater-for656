import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
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
 * The view is styled entirely from {@code /ui.css}; it adds no animations or
 * gradients of its own. A status-illustration slot ({@code statusImage}) is
 * reserved in the header: it loads a transparent PNG from the JAR by
 * {@link UpdatePhase} (and a separate updater PNG under PREPARING), and any
 * missing or corrupt resource degrades to a hidden slot without affecting the
 * layout or the update flow. Terminal state classes
 * ({@code success-state} / {@code error-state}) are maintained on the root by
 * {@link #setPhase}, and the Quit-update confirmation shares the same
 * stylesheet.
 */
class JavaFxUpdateView implements UpdateView {

    private final Stage stage;
    private final UpdateViewListener listener;
    private final boolean debug;

    // Window sizing: normal mode is deliberately short (Details collapsed).
    // Expanding Details (debug mode or error state) grows the window to its
    // content's preferred height — never a fixed expanded height, so Error /
    // Debug states don't leave dead space at the bottom (see applyWindowHeight).
    private static final double WINDOW_WIDTH = 520;
    private static final double WINDOW_HEIGHT_COLLAPSED = 300;

    // Reserved status-illustration slot: a transparent PNG fitted to this size.
    private static final double STATUS_IMAGE_SIZE = 64;

    // Status-illustration resources (JAR-relative). These are placeholder paths
    // — the art is not bundled yet, so every load falls back to hiding the
    // ImageView. When real PNGs are added at these paths they appear on their
    // own, with no further code changes.
    private static final String IMG_PREPARING = "/images/preparing.png";
    private static final String IMG_UPDATER = "/images/updater.png";
    private static final String IMG_CHECKING = "/images/checking.png";
    private static final String IMG_DOWNLOADING = "/images/downloading.png";
    private static final String IMG_CLEANING = "/images/cleaning.png";
    private static final String IMG_SUCCESS = "/images/success.png";
    private static final String IMG_ERROR = "/images/error.png";

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

    // Reserved status-illustration slot in the header. Hidden until a bundled
    // PNG loads; a missing or corrupt resource degrades to hidden without
    // affecting the layout (see showStatusImage / hideStatusImage).
    private final ImageView statusImage = new ImageView();

    // Root layout — carries the .success-state / .error-state state classes.
    private final VBox root = new VBox(8);

    /** External form of /ui.css, or null if the stylesheet is missing. */
    private final String stylesheet;

    /** The scene backing the window; resized when Details expands/collapses. */
    private Scene scene;

    /** Vertical window chrome (title bar + borders), measured once, see {@link #windowChrome()}. */
    private double chrome;

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
            // Revert any updater art to the current phase's illustration, and
            // shrink the window back (the download area just disappeared).
            updateStatusImage(phase);
            applyWindowHeight();
            return;
        }
        boolean updater = progress.kind == DownloadProgress.Kind.UPDATER;
        setPhase(updater ? UpdatePhase.PREPARING : UpdatePhase.DOWNLOADING);
        if (updater) {
            // The updater self-update never exposes the "agent" jargon in the
            // normal UI — the title and subtitle stay user-facing.
            lblStatus.setText("Updating updater…");
            lblDescription.setText("Preparing update components");
            // The updater stays a sub-state of PREPARING but may use its own
            // illustration (reserved; hidden until the art is bundled).
            showStatusImage(IMG_UPDATER);
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

    /**
     * Show the window. Must be called on the JavaFX Application Thread. The
     * debug window opens with Details already expanded, so after the stage is
     * realised it is sized to its content — a fixed expanded height would leave
     * dead space at the bottom.
     */
    @Override
    public void open() {
        stage.show();
        // The debug window opens with Details already expanded; size the window
        // to its content so a fixed expanded height never leaves dead space.
        // applyWindowHeight defers the measurement to the next pulse.
        applyWindowHeight();
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
        if (phase != p) {
            phase = p;
            root.getStyleClass().removeAll("success-state", "error-state");
            switch (p) {
            case PREPARING:
            case CLEANING:
                // Indeterminate phases hide the percentage entirely, so a
                // stale value (e.g. the previous 55%) never lingers beside
                // the bar.
                hideDownloadArea();
                clearOverallPercent();
                logArea.setPrefRowCount(6);
                break;
            case CHECKING:
            case DOWNLOADING:
                // Determinate phases show the percentage. The current-file
                // area is (re)shown by showDownloadProgress for DOWNLOADING.
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
                // (e.g. a previous 100%), expands Details and shows a few
                // more log rows for the failure context. The row count is
                // raised before expanding so the expansion-driven resize
                // sees the final content height.
                overallBar.setProgress(0);
                lblOverallPct.setText("");
                overallArea.setVisible(false);
                overallArea.setManaged(false);
                logArea.setPrefRowCount(10);
                detailsPane.setExpanded(true);
                root.getStyleClass().add("error-state");
                break;
            }
        }
        // Update the status illustration for the phase, then keep the window
        // sized to its content (Debug starts expanded; Error raises the log
        // height). This runs even when the phase is re-asserted unchanged: the
        // view starts in PREPARING, so the first PREPARING event would
        // otherwise short-circuit and its illustration would never appear.
        // No-op while the window is not showing.
        updateStatusImage(p);
        applyWindowHeight();
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

    /** Reveal the current-file area; grows the window only on the show/hide flip. */
    private void showDownloadArea() {
        boolean wasVisible = dlArea.isVisible();
        dlArea.setVisible(true);
        dlArea.setManaged(true);
        if (!wasVisible) {
            applyWindowHeight();
        }
    }

    /** Hide and clear the current-file area. */
    private void hideDownloadArea() {
        dlArea.setVisible(false);
        dlArea.setManaged(false);
        lblDlFile.setText("");
        dlBar.setProgress(0);
        lblDlSpeed.setText("");
    }

    // ── Status illustration ────────────────────────────────────────

    /** Map a phase to its status-illustration resource, or null for none. */
    private static String statusImageResource(UpdatePhase phase) {
        switch (phase) {
            case PREPARING: return IMG_PREPARING;
            case CHECKING: return IMG_CHECKING;
            case DOWNLOADING: return IMG_DOWNLOADING;
            case CLEANING: return IMG_CLEANING;
            case SUCCESS: return IMG_SUCCESS;
            case ERROR: return IMG_ERROR;
        }
        return null;
    }

    /** Point the status illustration at the given phase's art. */
    private void updateStatusImage(UpdatePhase phase) {
        showStatusImage(statusImageResource(phase));
    }

    /**
     * Try to load a status illustration from a JAR resource. Status art is
     * optional: a missing resource, an unresolvable path or a corrupt file just
     * hides the ImageView — the layout and the update flow are never affected.
     * The current placeholder paths resolve to nothing until the real PNGs are
     * bundled.
     */
    private void showStatusImage(String resource) {
        if (resource == null) {
            hideStatusImage();
            return;
        }
        java.net.URL url = getClass().getResource(resource);
        if (url == null) {
            hideStatusImage();
            return;
        }
        // preserveRatio with a requested fit so art never stretches; decode is
        // asynchronous, so a corrupt file also lands in the error listener.
        Image image = new Image(url.toExternalForm(), STATUS_IMAGE_SIZE, STATUS_IMAGE_SIZE, true, true);
        if (image.isError()) {
            hideStatusImage();
            return;
        }
        image.errorProperty().addListener((obs, wasError, isError) -> {
            // Only hide if this image is still the one on show — a later phase
            // may have already replaced it.
            if (isError && statusImage.getImage() == image) {
                hideStatusImage();
            }
        });
        statusImage.setImage(image);
        statusImage.setVisible(true);
        statusImage.setManaged(true);
    }

    /** Hide the status illustration (the default state). */
    private void hideStatusImage() {
        statusImage.setImage(null);
        statusImage.setVisible(false);
        statusImage.setManaged(false);
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

        // Status header: reserved status-illustration slot + title/subtitle.
        // The image stays hidden until a bundled PNG actually loads (see
        // showStatusImage), so today it reserves the layout slot invisibly.
        lblStatus.getStyleClass().add("status-title");
        lblDescription.getStyleClass().add("status-description");
        lblDescription.setWrapText(true);
        statusImage.getStyleClass().add("status-image");
        statusImage.setPreserveRatio(true);
        statusImage.setFitWidth(STATUS_IMAGE_SIZE);
        statusImage.setFitHeight(STATUS_IMAGE_SIZE);
        hideStatusImage();
        VBox statusText = new VBox(4, lblStatus, lblDescription);
        HBox statusHeader = new HBox(12, statusImage, statusText);
        statusHeader.getStyleClass().add("status-header");
        statusHeader.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(statusText, Priority.ALWAYS);

        // Overall progress area: bar + percent label.
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
        // No expand/collapse animation: the window height is sized to the
        // Details content, and an animated pane would interpolate its preferred
        // height over ~350ms and make that measurement unreliable. Round-3
        // explicitly defers animations until the status art is final.
        detailsPane.setAnimated(false);
        detailsPane.setExpanded(debug);

        // Root layout — the status header (illustration + text) is the first
        // row; lblDescription no longer needs a wrap flag set here.
        // Roomier canvas: wider horizontal padding and a little more vertical
        // breathing room, inside a shorter normal-mode window.
        root.setPadding(new Insets(18, 22, 18, 22));
        root.getChildren().addAll(statusHeader, overallArea, dlArea, detailsPane);

        // Debug footer — only present in debug mode, enabled by the controller
        // once the flow allows the user to close. A separator hairline above
        // the right-aligned button anchors it as a footer row rather than a
        // control left floating in the corner.
        if (debug) {
            btnClose.getStyleClass().add("debug-close-button");
            btnClose.setDisable(true);
            btnClose.setOnAction(e -> listener.onCloseRequested());
            Separator footerLine = new Separator();
            footerLine.getStyleClass().add("debug-footer-separator");
            HBox bottom = new HBox(btnClose);
            bottom.getStyleClass().add("debug-footer");
            bottom.setAlignment(Pos.CENTER_RIGHT);
            root.getChildren().addAll(footerLine, bottom);
        }

        // Apply the shared visual system (ui.css) — normal and debug alike.
        // Short by default; the window grows when Details expands (debug mode
        // and the error state) so collapsed layouts never sit in a tall window
        // full of dead space.
        scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT_COLLAPSED);
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet);
        }
        stage.setScene(scene);
        stage.setMinWidth(WINDOW_WIDTH - 40);
        stage.setMinHeight(WINDOW_HEIGHT_COLLAPSED);
        detailsPane.expandedProperty().addListener((obs, wasExpanded, expanded) ->
                applyWindowHeight());
    }

    /**
     * Schedule a content-driven window resize. The measurement is deferred to
     * the next pulse so the window's insets and the current layout are known —
     * measuring immediately after, e.g., setExpanded(true) can see the stage
     * before it has fully realised its size.
     */
    private void applyWindowHeight() {
        if (stage.getScene() == null || !stage.isShowing()) {
            return;
        }
        Platform.runLater(this::resizeToContent);
    }

    /**
     * Resize the window so the client area (the scene) matches the content's
     * preferred height. The height is content-driven — never a fixed expanded
     * height — so Error / Debug states take exactly the space they need instead
     * of leaving dead space at the bottom; the collapsed window keeps its
     * deliberate short height as a floor. The Details pane animates nothing
     * (setAnimated(false)), so its expanded content height is exact at measure
     * time.
     */
    private void resizeToContent() {
        if (stage.getScene() == null || !stage.isShowing()) {
            return;
        }
        double chrome = windowChrome();
        if (chrome <= 0) {
            // Window not fully realised yet — a later scheduled resize retries.
            return;
        }
        Scene scene = stage.getScene();
        scene.getRoot().applyCss();
        double width = scene.getWidth();
        double pref = width > 0 ? scene.getRoot().prefHeight(width) : scene.getRoot().prefHeight(-1);
        double target = Math.max(pref, WINDOW_HEIGHT_COLLAPSED) + chrome;
        if (Math.abs(stage.getHeight() - target) > 1.0) {
            stage.setHeight(target);
        }
    }

    /**
     * Vertical window chrome (title bar + borders) between the stage and the
     * scene. Measured once while the window is still at its initial size — the
     * scene lags the stage during resizes, so re-measuring {@code
     * stage.getHeight() - scene.getHeight()} mid-resize would inflate the
     * chrome and oversize the window.
     */
    private double windowChrome() {
        if (chrome > 0) {
            return chrome;
        }
        Scene scene = stage.getScene();
        if (scene == null || scene.getHeight() <= 0 || !stage.isShowing()) {
            return 0;
        }
        chrome = stage.getHeight() - scene.getHeight();
        return chrome;
    }
}
