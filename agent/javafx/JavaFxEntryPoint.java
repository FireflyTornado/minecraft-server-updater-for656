import javafx.application.Platform;

import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * JavaFX composition root, parallel to the Swing {@link UpdateApplication}.
 *
 * Reached reflectively from {@link UpdateAgent} when the UI mode is
 * {@code javafx}. It initializes the JavaFX runtime with
 * {@link Platform#startup} and wires the business layer, the controller, the
 * JavaFX dispatcher and the JavaFX view together on the JavaFX Application
 * Thread — mirroring exactly what {@code UpdateApplication.start()} does for
 * Swing. Holds no flow decisions; those belong to {@link UpdateController}.
 *
 * This class references {@code javafx.*}, so it is only compiled when the
 * build is run with the JavaFX runtime on the classpath (build.sh/build.bat
 * --javafx). If it is absent from the core JAR, {@link UpdateAgent} falls back
 * to the Swing UI.
 */
public final class JavaFxEntryPoint {

    private JavaFxEntryPoint() {}

    /**
     * Boot the JavaFX runtime and start the update flow on the FX thread.
     * Must be called from a non-FX thread (the agent's premain thread).
     */
    public static void launch(String gameDir, String serverConfig,
                              boolean debug, CountDownLatch latch) {
        Platform.startup(() -> {
            List<String> serverUrls = UpdateApplication.parseServerList(serverConfig);
            UpdateService service = new UpdateService(gameDir, serverUrls);
            UiDispatcher dispatcher = new JavaFxUiDispatcher();
            UpdateController controller = new UpdateController(service, dispatcher, latch, debug);
            UpdateView view = new JavaFxUpdateView(controller, new UiModel(gameDir, debug));
            controller.attach(view);
            controller.start();
        });
    }
}
