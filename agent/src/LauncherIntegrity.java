import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

final class LauncherIntegrity {
    // SHA-256 of the legacy UpdateAgent.jar shipped in the server pack.
    private static final String ORIGINAL_LAUNCHER_SHA256 =
            "61ed6283f36973441e60c465637a480dff3de90d248b1f9924ecc35ab0d52bea";
    private static final String BUILT_LAUNCHER_HASH = "Built-Launcher-SHA-256";
    private static final String JAVA_AGENT_PREFIX = "-javaagent:";

    private LauncherIntegrity() { }

    static void verify(Class<?> caller) throws IOException {
        if (!"Launcher".equals(caller.getName())) {
            throw new IOException("Cannot identify the updater launcher");
        }
        Path launcher = jarPath(caller);
        if (!isStartupAgent(launcher)) {
            throw new IOException("The updater launcher is not a JVM startup agent");
        }

        String hash = sha256(launcher);
        if (!ORIGINAL_LAUNCHER_SHA256.equals(hash) && !hash.equals(builtLauncherHash())) {
            throw new IOException("The updater launcher does not match a trusted release");
        }
    }

    private static Path jarPath(Class<?> type) throws IOException {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IOException("Cannot locate the JAR for " + type.getSimpleName());
        }
        try {
            Path path = Path.of(source.getLocation().toURI()).toRealPath();
            if (!Files.isRegularFile(path)) {
                throw new IOException("Expected a JAR file for " + type.getSimpleName());
            }
            return path;
        } catch (URISyntaxException error) {
            throw new IOException("Invalid JAR location for " + type.getSimpleName(), error);
        }
    }

    private static boolean isStartupAgent(Path launcher) throws IOException {
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!argument.startsWith(JAVA_AGENT_PREFIX)) {
                continue;
            }
            int options = argument.indexOf('=', JAVA_AGENT_PREFIX.length());
            String path = argument.substring(JAVA_AGENT_PREFIX.length(),
                    options < 0 ? argument.length() : options);
            Path agent = Path.of(path);
            if (Files.isRegularFile(agent) && Files.isSameFile(launcher, agent)) {
                return true;
            }
        }
        return false;
    }

    private static String sha256(Path jar) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
        try (DigestInputStream input = new DigestInputStream(Files.newInputStream(jar), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String builtLauncherHash() throws IOException {
        // Read the manifest from this core's own JAR, never from a classpath search.
        try (JarFile core = new JarFile(jarPath(UpdateAgent.class).toFile(), false)) {
            Manifest manifest = core.getManifest();
            return manifest == null ? null : manifest.getMainAttributes().getValue(BUILT_LAUNCHER_HASH);
        }
    }
}
