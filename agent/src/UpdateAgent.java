/**
 * Minecraft Client Auto-Update Java Agent
 *
 * Loaded via -javaagent JVM argument at Minecraft client startup:
 * 1. Resolves configuration (agent args, system properties, config file)
 * 2. Starts the update application, which shows the GUI and runs the update
 * 3. Blocks Minecraft launch until the update check completes
 *
 * System properties (or agent args):
 *   -Dmc-update.server=http://192.168.1.100:25565
 *   -Dmc-update.game-dir=C:\\path\\to\\.minecraft
 *
 * Compile:
 *   javac -d build src/*.java
 *   cd build && jar cfm ../UpdateAgent.jar ../META-INF/MANIFEST.MF Launcher.class
 */

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

public class UpdateAgent {

    private static final String PROP_SERVER  = "mc-update.server";
    private static final String PROP_GAME_DIR = "mc-update.game-dir";
    private static final String PROP_DEBUG    = "mc-update.debug";
    private static final String CONFIG_FILE   = "mc-update.properties";
    private static final String DEFAULT_SERVER = "http://localhost:25565";

    // ── Agent entry point ────────────────────────────────────────

    public static void premain(String args, Instrumentation inst) {
        // 1. Parse agent args into a map (don't set system properties yet)
        Map<String, String> agentArgs = parseAgentArgs(args);
        boolean admin = "true".equalsIgnoreCase(agentArgs.get("admin"));

        // 2. Resolve game directory: agent arg > -D system property > user.dir
        String gameDir = coalesce(
            agentArgs.get("game-dir"),
            System.getProperty(PROP_GAME_DIR),
            System.getProperty("user.dir", ".")
        );
        System.setProperty(PROP_GAME_DIR, gameDir);

        // 3. Load persistent config from game directory
        Properties fileConfig = loadConfigFile(new File(gameDir));

        // 4. Merge config with mode-dependent priority
        //    Normal:  file config > agent args > -D system props > defaults
        //    Admin:   agent args  > -D system props > file config  > defaults (original)
        String server;
        boolean debug;

        if (admin) {
            server = coalesce(
                agentArgs.get("server"),
                System.getProperty(PROP_SERVER),
                fileConfig.getProperty("server"),
                DEFAULT_SERVER
            );
            String debugStr = coalesce(
                agentArgs.get("debug"),
                System.getProperty(PROP_DEBUG),
                fileConfig.getProperty("debug"),
                "false"
            );
            debug = "true".equalsIgnoreCase(debugStr) || "1".equals(debugStr);
        } else {
            server = coalesce(
                fileConfig.getProperty("server"),
                agentArgs.get("server"),
                System.getProperty(PROP_SERVER),
                DEFAULT_SERVER
            );
            String debugStr = coalesce(
                fileConfig.getProperty("debug"),
                agentArgs.get("debug"),
                System.getProperty(PROP_DEBUG),
                "false"
            );
            debug = "true".equalsIgnoreCase(debugStr) || "1".equals(debugStr);
        }

        System.setProperty(PROP_SERVER, server);
        if (debug) {
            System.setProperty(PROP_DEBUG, "true");
        }

        // Block premain until update check finishes, then allow Minecraft to start
        CountDownLatch latch = new CountDownLatch(1);
        UpdateApplication app = new UpdateApplication(gameDir, server, debug, latch);
        SwingUtilities.invokeLater(app::start);

        try {
            latch.await();  // block until update check completes
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Agent args parser ─────────────────────────────────────

    /** Parse comma-separated key=value pairs from -javaagent args. Never returns null. */
    private static Map<String, String> parseAgentArgs(String args) {
        Map<String, String> map = new LinkedHashMap<>();
        if (args != null && !args.isEmpty()) {
            for (String token : args.split(",")) {
                String[] kv = token.split("=", 2);
                if (kv.length == 2) {
                    map.put(kv[0].trim(), kv[1].trim());
                }
            }
        }
        return map;
    }

    // ── Value coalescing ──────────────────────────────────────

    /** Return the first non-null, non-empty value from the given candidates. */
    private static String coalesce(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    // ── Persistent config file ─────────────────────────────────

    /** Load mc-update.properties from the given directory. Never returns null. */
    static Properties loadConfigFile(File dir) {
        Properties props = new Properties();
        File configFile = new File(dir, CONFIG_FILE);
        if (configFile.isFile()) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                props.load(fis);
            } catch (IOException ignored) {}
        }
        return props;
    }
}
