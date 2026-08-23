import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/**
 * Swing UI for the update check. Only responsible for UI presentation and
 * user interaction: it displays status, overall and per-file download
 * progress and the update log, and forwards user actions to the
 * {@link UpdateApplication}.
 *
 * Implements {@link UpdateListener} so the update service can report events
 * without depending on Swing; this class marshals those callbacks onto the
 * EDT through the background {@link SwingWorker}.
 */
class UpdateGUI extends JFrame implements UpdateListener {

    private final JLabel     lblStatus    = new JLabel("Checking for updates...");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JTextArea  logArea      = new JTextArea(8, 50);
    private final JButton    btnClose     = new JButton("Close");

    // Per-file download progress bar (below overall bar)
    private final JProgressBar dlProgressBar = new JProgressBar(0, 100);
    private final JLabel       lblDlSpeed    = new JLabel(" ");

    // Per-file download UI refresh timer (500ms) — reads service download progress
    private final javax.swing.Timer dlRefreshTimer = new javax.swing.Timer(500, e -> refreshDownloadUI());
    private long dlLastBytes = 0;
    private long dlLastTime  = 0;

    private final UpdateApplication app;
    private final UpdateService service;
    private final String gameDir;
    private final boolean debug;
    private JLabel serverLabel;
    private UpdateWorker updateWorker;

    private enum UiEventType {
        STATUS, LOG, OVERALL_PROGRESS, RESET_DOWNLOAD_PROGRESS, SERVER_LABEL
    }

    /** A background-to-EDT message. UI components are only changed in process(). */
    private static final class UiEvent {
        final UiEventType type;
        final String text;
        final boolean indeterminate;
        final int progress;

        private UiEvent(UiEventType type, String text, boolean indeterminate, int progress) {
            this.type = type;
            this.text = text;
            this.indeterminate = indeterminate;
            this.progress = progress;
        }

        static UiEvent status(String text, boolean indeterminate) {
            return new UiEvent(UiEventType.STATUS, text, indeterminate, 0);
        }

        static UiEvent log(String text) {
            return new UiEvent(UiEventType.LOG, text, false, 0);
        }

        static UiEvent overallProgress(int progress) {
            return new UiEvent(UiEventType.OVERALL_PROGRESS, null, false, progress);
        }

        static UiEvent serverLabel(String text) {
            return new UiEvent(UiEventType.SERVER_LABEL, text, false, 0);
        }

        static UiEvent resetDownloadProgress() {
            return new UiEvent(UiEventType.RESET_DOWNLOAD_PROGRESS, null, false, 0);
        }
    }

    UpdateGUI(UpdateApplication app, UpdateService service, String gameDir, boolean debug) {
        this.app = app;
        this.service = service;
        this.gameDir = gameDir;
        this.debug = debug;
        initUI();
        setVisible(true);
    }

    /** Start the background update worker. Must run on the EDT. */
    void start() {
        dlRefreshTimer.start();
        updateWorker = new UpdateWorker();
        updateWorker.execute();
    }

    // ── UpdateListener (called on the worker thread) ───────────────

    @Override
    public void onStatus(String status, boolean indeterminate) {
        setStatus(status, indeterminate);
    }

    @Override
    public void onLog(String message) {
        appendLog(message);
    }

    @Override
    public void onOverallProgress(int percent) {
        setOverallProgress(percent);
    }

    @Override
    public void onServerChanged() {
        refreshServerLabel();
    }

    @Override
    public void onDownloadStarted() {
        // Reset the per-file download speed baseline at the start of a download
        dlLastBytes = 0;
        dlLastTime = System.currentTimeMillis();
    }

    @Override
    public void onDownloadComplete() {
        resetDownloadProgressBar();
    }

    // ── Public UI operations used by the application flow layer ─────

    /** Set the status text and whether the overall bar is indeterminate. */
    void setStatus(String text, boolean indeterminate) {
        dispatchUiEvent(UiEvent.status(text, indeterminate));
    }

    /** Append a line to the log area. */
    void appendLog(String msg) {
        dispatchUiEvent(UiEvent.log(msg));
    }

    /** Set the overall progress bar to a determinate percentage. */
    void setOverallProgress(int value) {
        dispatchUiEvent(UiEvent.overallProgress(value));
    }

    /** Stop the per-file download refresh timer. Must run on the EDT. */
    void stopRefreshTimer() {
        dlRefreshTimer.stop();
    }

    /** Reset the per-file download progress bar and speed label. */
    void resetDownloadProgressBar() {
        dispatchUiEvent(UiEvent.resetDownloadProgress());
    }

    /** Set the overall progress bar value (determinate). Must run on the EDT. */
    void setProgress(int value) {
        progressBar.setIndeterminate(false);
        progressBar.setValue(value);
    }

    /** Show a modal error dialog. Must run on the EDT. */
    void showErrorDialog(String message) {
        JOptionPane.showMessageDialog(this, message, "Update Error", JOptionPane.ERROR_MESSAGE);
    }

    /** Enable/disable the debug Close button. Must run on the EDT. */
    void setCloseButtonEnabled(boolean enabled) {
        btnClose.setEnabled(enabled);
    }

    /** Close the window. Must run on the EDT. */
    void disposeWindow() {
        dispose();
    }

    /** Run an action on the EDT after a delay (e.g. delayed exit). Must run on the EDT. */
    void scheduleOnEdt(Runnable action, int delayMs) {
        new javax.swing.Timer(delayMs, e -> action.run()).start();
    }

    // ── EDT event plumbing ─────────────────────────────────────────

    /** Run a UI mutation on Swing's Event Dispatch Thread. */
    private void runOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    /** Deliver an event through SwingWorker when called from its worker thread. */
    private void dispatchUiEvent(UiEvent event) {
        UpdateWorker worker = updateWorker;
        if (worker != null && !SwingUtilities.isEventDispatchThread() && !worker.isDone()) {
            worker.emit(event);
        } else {
            runOnEdt(() -> applyUiEvent(event));
        }
    }

    /** Apply a UI event. This method must run on the EDT. */
    private void applyUiEvent(UiEvent event) {
        switch (event.type) {
            case STATUS:
                lblStatus.setText(event.text);
                progressBar.setIndeterminate(event.indeterminate);
                break;
            case LOG:
                logArea.append(event.text + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
                break;
            case OVERALL_PROGRESS:
                progressBar.setIndeterminate(false);
                progressBar.setValue(Math.max(0, Math.min(100, event.progress)));
                break;
            case RESET_DOWNLOAD_PROGRESS:
                dlProgressBar.setValue(0);
                dlProgressBar.setString("");
                lblDlSpeed.setText(" ");
                break;
            case SERVER_LABEL:
                serverLabel.setText(event.text);
                break;
        }
    }

    private void initUI() {
        setTitle("Minecraft Update Check");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(520, 420);
        setLocationRelativeTo(null);
        setResizable(false);

        // Release the latch via the application so Minecraft can start
        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosed(java.awt.event.WindowEvent e) {
                app.onWindowClosed();
            }
        });

        // Root panel
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        setContentPane(root);

        // Top info
        JPanel topPanel = new JPanel(new GridLayout(2, 1, 4, 4));
        serverLabel = new JLabel();
        refreshServerLabel();
        topPanel.add(serverLabel);
        topPanel.add(new JLabel("Game dir: " + gameDir));
        root.add(topPanel, BorderLayout.NORTH);

        // Center: progress area + log
        JPanel center = new JPanel(new BorderLayout(6, 6));

        // Progress panel: status + overall bar + per-file bar + speed
        JPanel progressPanel = new JPanel();
        progressPanel.setLayout(new BoxLayout(progressPanel, BoxLayout.Y_AXIS));

        progressBar.setIndeterminate(true);
        progressBar.setStringPainted(true);
        progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);

        dlProgressBar.setStringPainted(true);
        dlProgressBar.setValue(0);
        dlProgressBar.setString("");
        dlProgressBar.setAlignmentX(Component.LEFT_ALIGNMENT);

        lblDlSpeed.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        lblDlSpeed.setForeground(new Color(120, 120, 120));
        lblDlSpeed.setAlignmentX(Component.LEFT_ALIGNMENT);

        progressPanel.add(lblStatus);
        progressPanel.add(Box.createVerticalStrut(4));
        progressPanel.add(progressBar);
        progressPanel.add(Box.createVerticalStrut(2));
        progressPanel.add(dlProgressBar);
        progressPanel.add(Box.createVerticalStrut(2));
        progressPanel.add(lblDlSpeed);

        center.add(progressPanel, BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logArea.setBackground(new Color(30, 30, 30));
        logArea.setForeground(new Color(200, 200, 200));
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createTitledBorder("Update log"));
        center.add(scroll, BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);

        // Close button (only shown in debug mode; otherwise window auto-closes)
        if (debug) {
            JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            btnClose.setEnabled(false);
            btnClose.addActionListener(e -> app.onCloseRequested());
            bottom.add(btnClose);
            root.add(bottom, BorderLayout.SOUTH);
        }
    }

    /** Refresh the server label from the service's current server. */
    private void refreshServerLabel() {
        String display = service.getServerUrls().size() <= 1
                ? "Server: " + service.getCurrentServer()
                : "Servers (" + service.getServerUrls().size() + "): " + service.getCurrentServer();
        dispatchUiEvent(UiEvent.serverLabel(display));
    }

    // ── Per-file download UI refresh ────────────────────────────────

    private void refreshDownloadUI() {
        DownloadProgress p = service.getDownloadProgress();
        if (!p.active) {
            dlProgressBar.setValue(0);
            dlProgressBar.setString("");
            lblDlSpeed.setText(" ");
            return;
        }
        long total = p.totalBytes;
        long done  = p.downloadedBytes;
        if (total > 0) {
            int pct = (int) (done * 100 / total);
            if (pct > 100) pct = 100;
            dlProgressBar.setValue(pct);
            dlProgressBar.setString(pct + "%");
            dlProgressBar.setIndeterminate(false);
        } else {
            dlProgressBar.setIndeterminate(true);
            dlProgressBar.setString("");
        }
        long now = System.currentTimeMillis();
        long elapsed = now - dlLastTime;
        if (elapsed >= 400) {
            long bytesDelta = done - dlLastBytes;
            double speed = elapsed > 0 ? bytesDelta * 1000.0 / elapsed : 0;
            lblDlSpeed.setText(FormatUtil.formatSpeed(speed));
            dlLastBytes = done;
            dlLastTime  = now;
        }
    }

    /** Performs the blocking update work; UI events are published to the EDT. */
    private final class UpdateWorker extends SwingWorker<UpdateResult, UiEvent> {
        void emit(UiEvent event) {
            publish(event);
        }

        @Override
        protected UpdateResult doInBackground() throws Exception {
            return service.run(UpdateGUI.this);
        }

        @Override
        protected void process(List<UiEvent> events) {
            for (UiEvent event : events) applyUiEvent(event);
        }

        @Override
        protected void done() {
            stopRefreshTimer();
            try {
                UpdateResult result = get();
                app.onUpdateFinished(result);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                app.onUpdateError("Update error: " + cause.getMessage(), cause);
            }
        }
    }
}
