import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Application flow control for the update check.
 *
 * Owns the {@link CountDownLatch} that gates the Minecraft launch and decides
 * what happens after the update finishes — release the latch, close the window
 * or terminate the JVM. Wires the {@link UpdateService} and the
 * {@link UpdateGUI} together. Contains no Swing dependency.
 */
class UpdateApplication {

    private final String gameDir;
    private final List<String> serverUrls;
    private final boolean debug;
    private final CountDownLatch latch;

    private UpdateGUI gui;
    private UpdateService service;

    UpdateApplication(String gameDir, String serverConfig, boolean debug, CountDownLatch latch) {
        this.gameDir = gameDir;
        this.serverUrls = parseServerList(serverConfig);
        this.debug = debug;
        this.latch = latch;
    }

    /** Create the service and the GUI and start the update. Must run on the EDT. */
    void start() {
        service = new UpdateService(gameDir, serverUrls);
        gui = new UpdateGUI(this, service, gameDir, debug);
        gui.start();
    }

    /** The user closed the window (e.g. in debug mode) — release the latch. */
    void onWindowClosed() {
        latch.countDown();
    }

    /** The user pressed the debug Close button — release the latch and close the window. */
    void onCloseRequested() {
        latch.countDown();
        gui.disposeWindow();
    }

    /** The update completed without an exception. */
    void onUpdateFinished(UpdateResult result) {
        gui.setOverallProgress(100);
        if (result.failed > 0) {
            gui.setStatus("Update finished with " + result.failed + " error(s)", false);
            gui.appendLog("[FATAL] " + result.failed
                    + " file(s) failed to update, killing Minecraft process...");
            gui.scheduleOnEdt(() -> System.exit(1), 2000);
        } else if (result.updated > 0) {
            gui.setStatus("Updated " + result.updated + " file(s), launching Minecraft...", false);
            finishSuccess(2000);
        } else {
            gui.setStatus("Already up to date, launching Minecraft...", false);
            finishSuccess(1000);
        }
    }

    /** The update threw an exception — show the error and terminate the JVM. */
    void onUpdateError(String message, Throwable cause) {
        cause.printStackTrace();
        gui.stopRefreshTimer();
        gui.resetDownloadProgressBar();
        gui.appendLog("[ERROR] " + message);
        gui.setStatus("Update failed", false);
        gui.setProgress(0);
        gui.showErrorDialog(message);
        gui.appendLog("[FATAL] Killing Minecraft process...");
        gui.scheduleOnEdt(() -> System.exit(1), 1000);
    }

    /** Successful completion: release the latch so Minecraft can start. */
    private void finishSuccess(int delayMs) {
        if (debug) {
            // Debug mode: release latch so Minecraft starts, but keep window open
            latch.countDown();
            gui.setCloseButtonEnabled(true);
            gui.appendLog("[DEBUG] Update check done. Window stays open for inspection.");
        } else {
            gui.scheduleOnEdt(() -> {
                latch.countDown();
                gui.disposeWindow();
            }, delayMs);
        }
    }

    /** Parse comma-separated server URLs, trimming whitespace from each. */
    private static List<String> parseServerList(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return list;
        for (String token : raw.split(",")) {
            String url = token.trim();
            if (!url.isEmpty()) {
                // Remove trailing slash for consistency
                while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
                list.add(url);
            }
        }
        return list;
    }
}
