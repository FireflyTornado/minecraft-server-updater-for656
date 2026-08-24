import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Application flow control for the update check.
 *
 * Owns the {@link CountDownLatch} that gates the Minecraft launch and decides
 * everything that happens after the update finishes — whether to release the
 * latch, when (immediately or after a short delay), whether to close the view,
 * and when to terminate the JVM after a failure. Also owns window visibility:
 * it opens the view via {@link UpdateView#open()} and closes it via
 * {@link UpdateView#close()}. Wires the
 * {@link UpdateService}, the {@link UpdateView} and the {@link UpdateController}
 * together and receives completion callbacks from the controller. Implements
 * the {@link UpdateViewListener} user-action callback so the view forwards
 * window-close / close-button operations without ever referencing this class.
 * Contains no Swing dependency: the {@link UpdateController} marshals events
 * onto the UI thread through a {@link UiDispatcher}, delayed actions run on
 * background threads, and view log / close calls are marshalled the same way.
 */
class UpdateApplication implements UpdateViewListener {

    private final String gameDir;
    private final List<String> serverUrls;
    private final boolean debug;
    private final CountDownLatch latch;

    private UpdateView view;
    private UiDispatcher dispatcher;

    UpdateApplication(String gameDir, String serverConfig, boolean debug, CountDownLatch latch) {
        this.gameDir = gameDir;
        this.serverUrls = parseServerList(serverConfig);
        this.debug = debug;
        this.latch = latch;
    }

    /** Create the service, view and controller and start the update. Must run on the EDT. */
    void start() {
        UpdateService service = new UpdateService(gameDir, serverUrls);
        view = new UpdateGUI(this, new UiModel(gameDir, debug));
        dispatcher = new SwingUiDispatcher();
        new UpdateController(service, view, dispatcher, this).start();
        // The application flow decides when to display the view.
        view.open();
    }

    /** The user closed the window — release the latch so Minecraft can start. */
    @Override
    public void onWindowClosed() {
        latch.countDown();
    }

    /** The user pressed the debug Close button — release the latch and close the window. */
    @Override
    public void onCloseRequested() {
        latch.countDown();
        // Closing the window is a flow decision; the view only forwards the press.
        view.close();
    }

    /**
     * The update completed. Failed files kill the process; otherwise release the
     * latch so Minecraft can start. All delays and window management live here,
     * not in the view. Runs on the UI thread.
     */
    void onUpdateFinished(UpdateResult result) {
        if (result.failed > 0) {
            view.showLog("[FATAL] " + result.failed
                    + " file(s) failed to update, killing Minecraft process...");
            delayThen(2000, () -> System.exit(1));
        } else if (debug) {
            // Release now; the window stays open for inspection.
            latch.countDown();
        } else {
            long delay = result.updated > 0 ? 2000 : 1000;
            delayThen(delay, () -> {
                latch.countDown();
                dispatcher.invoke(view::close);
            });
        }
    }

    /**
     * The update threw an exception — print the stack trace and terminate the
     * JVM after a short grace period so the error stays visible. Runs on the
     * UI thread.
     */
    void onUpdateError(Throwable cause) {
        cause.printStackTrace();
        view.showLog("[FATAL] Killing Minecraft process...");
        delayThen(1000, () -> System.exit(1));
    }

    /** Run an action once after a delay on a daemon thread. No Swing involved. */
    private static void delayThen(long delayMs, Runnable action) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            action.run();
        }, "update-flow");
        thread.setDaemon(true);
        thread.start();
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
