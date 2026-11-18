/**
 * Compatibility entry point for {@code UpdateAgent_core.jar}.
 *
 * <p>{@link Launcher} is intentionally never self-updated and loads this class
 * by the literal name {@code "UpdateAgent"}. Keep this default-package facade
 * even as the implementation moves into named packages; otherwise an already
 * deployed launcher could not load a newer core JAR.</p>
 */

import com.zack88604.autoupdater.bootstrap.AgentBootstrap;
import java.io.IOException;
import java.lang.instrument.Instrumentation;

public final class UpdateAgent {

    private UpdateAgent() {
        // Entry-point class; do not instantiate.
    }

    /** Delegate the Java agent entry point to the packaged implementation. */
    public static void premain(String args, Instrumentation inst) {
        try {
            // Reflection frames are skipped. The caller is the Launcher that invoked us.
            Class<?> launcher = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                    .getCallerClass();
            LauncherIntegrity.verify(launcher);
        } catch (IOException | RuntimeException error) {
            System.err.println("[UpdateAgent] Launcher integrity check failed: " + error.getMessage());
            // Returning or throwing here would let an enclosing agent continue startup.
            Runtime.getRuntime().halt(1);
            return;
        }
        AgentBootstrap.premain(args, inst);
    }
}
