import javafx.application.Platform;

/**
 * {@link UiDispatcher} that schedules actions on the JavaFX Application
 * Thread via {@link Platform#runLater}.
 *
 * Part of the JavaFX UI implementation (parallel to the Swing
 * {@link SwingUiDispatcher}). Compiled only when the build is run with the
 * JavaFX runtime on the classpath — see build.sh/build.bat --javafx.
 */
final class JavaFxUiDispatcher implements UiDispatcher {

    @Override
    public void invoke(Runnable action) {
        Platform.runLater(action);
    }
}
