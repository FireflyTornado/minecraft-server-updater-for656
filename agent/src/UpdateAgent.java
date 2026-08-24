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
 *   -Dmc-update.ui=javafx       (optional: "swing" default, or "javafx")
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
    private static final String PROP_UI       = "mc-update.ui";
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
        String ui;

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
            ui = coalesce(
                agentArgs.get("ui"),
                System.getProperty(PROP_UI),
                fileConfig.getProperty("ui"),
                "swing"
            );
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
            ui = coalesce(
                fileConfig.getProperty("ui"),
                agentArgs.get("ui"),
                System.getProperty(PROP_UI),
                "swing"
            );
        }

        System.setProperty(PROP_SERVER, server);
        if (debug) {
            System.setProperty(PROP_DEBUG, "true");
        }

        // Block premain until update check finishes, then allow Minecraft to start.
        // UI toolkit is chosen by mc-update.ui: "javafx" uses the JavaFX view,
        // anything else (default) uses the Swing view.
        CountDownLatch latch = new CountDownLatch(1);
        if ("javafx".equalsIgnoreCase(ui)) {
            startJavaFxFlow(gameDir, server, debug, latch);
        } else {
            UpdateApplication app = new UpdateApplication(gameDir, server, debug, latch);
            SwingUtilities.invokeLater(app::start);
        }

        try {
            latch.await();  // block until update check completes
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Start the update flow with the JavaFX view. The JavaFX composition root
     * ({@code JavaFxEntryPoint}) is loaded by name so the core JAR still
     * compiles and runs without the JavaFX runtime on the classpath; if the
     * JavaFX implementation is missing or the runtime cannot be started, fall
     * back to the Swing view.
     */
    private static void startJavaFxFlow(String gameDir, String server,
                                        boolean debug, CountDownLatch latch) {
        try {
            Class<?> entry = Class.forName("JavaFxEntryPoint");
            entry.getMethod("launch", String.class, String.class,
                            boolean.class, CountDownLatch.class)
                 .invoke(null, gameDir, server, debug, latch);
        } catch (Exception | LinkageError e) {
            e.printStackTrace();
            System.err.println("[UpdateAgent] JavaFX view unavailable, falling back to Swing.");
            UpdateApplication app = new UpdateApplication(gameDir, server, debug, latch);
            SwingUtilities.invokeLater(app::start);
        }
    }

    // ── Agent args parser ─────────────────────────────────────

    /** Parse comma-separated key=value pairs from -javaagent args. Never returns null. */
    private static Map<String, String> parseAgentArgs(String args) {
        Map<String, String> map = new LinkedHashMap<>();
        if (args != null && !args.isEmpty()) {
            String lastKey = null;
            for (String token : args.split(",")) {
                String[] kv = token.split("=", 2);
                if (kv.length == 2) {
                    lastKey = kv[0].trim();
                    map.put(lastKey, kv[1].trim());
                } else if (lastKey != null) {
                    // A bare token (no '=') continues the previous key's value —
                    // e.g. "server=url1,url2" must parse as server="url1,url2",
                    // not as two tokens (the documented multi-server fallback).
                    map.put(lastKey, map.get(lastKey) + "," + token.trim());
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
