import com.zack88604.autoupdater.infrastructure.security.UpdaterRelease;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;

/** JVM startup entry point for the independently signed updater core. */
public class Launcher {
    public static void premain(String args, Instrumentation inst) {
        try {
            PublicKey key = UpdaterRelease.pinnedKey(Launcher.class);
            Path directory = UpdaterRelease.codeSource(Launcher.class).getParent();
            Path core = directory.resolve("UpdateAgent_core.jar");
            Path pending = directory.resolve("UpdateAgent_core.jar.new");

            if (!Files.notExists(pending, LinkOption.NOFOLLOW_LINKS)) {
                UpdaterRelease.verify(pending, UpdaterRelease.CORE, key);
                Files.move(pending, core, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            UpdaterRelease.verify(core, UpdaterRelease.CORE, key);

            // Resolve updater classes from the authenticated JAR.
            try (URLClassLoader loader = new URLClassLoader(new URL[]{core.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
                Class<?> entry = Class.forName("UpdateAgent", false, loader);
                if (entry.getClassLoader() != loader || !core.equals(UpdaterRelease.codeSource(entry))) {
                    throw new IOException("Unexpected updater core entry point source");
                }
                entry.getMethod("premain", String.class, Instrumentation.class).invoke(null, args, inst);
            }
        } catch (Exception | LinkageError error) {
            System.err.println("[Launcher] Minecraft startup stopped: " + error.getMessage());
            error.printStackTrace();
            System.exit(1);
        }
    }
}
