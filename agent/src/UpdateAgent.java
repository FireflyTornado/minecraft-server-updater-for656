/**
 * Compatibility entry point for {@code UpdateAgent_core.jar}.
 *
 * <p>{@link Launcher} is intentionally never self-updated and loads this class
 * by the literal name {@code "UpdateAgent"}. Keep this default-package facade
 * even as the implementation moves into named packages; otherwise an already
 * deployed launcher could not load a newer core JAR.</p>
 */

import com.zack88604.autoupdater.bootstrap.AgentBootstrap;
import com.zack88604.autoupdater.infrastructure.security.UpdaterRelease;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;

public final class UpdateAgent {

    private UpdateAgent() {
        // Entry-point class; do not instantiate.
    }

    /** Delegate the Java agent entry point to the packaged implementation. */
    public static void premain(String args, Instrumentation inst) {
        try {
            verifyLauncher();
        } catch (Exception | LinkageError error) {
            System.err.println("[UpdateAgent] Updater integrity check failed: " + error.getMessage());
            // This must terminate even when an outer premain catches exceptions.
            Runtime.getRuntime().halt(1);
            return;
        }
        AgentBootstrap.premain(args, inst);
    }

    private static void verifyLauncher() throws ReflectiveOperationException, IOException {
        Class<?> launcher = Class.forName("Launcher", false, ClassLoader.getSystemClassLoader());
        Path source = UpdaterRelease.codeSource(launcher);
        String prefix = "-javaagent:";
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!argument.startsWith(prefix)) {
                continue;
            }
            String path = argument.substring(prefix.length()).split("=", 2)[0];
            if (source.equals(Path.of(path).toRealPath())) {
                UpdaterRelease.verify(source, UpdaterRelease.LAUNCHER,
                        UpdaterRelease.pinnedKey(UpdateAgent.class));
                return;
            }
        }
        throw new IOException("Launcher code source does not match a JVM agent argument");
    }
}
