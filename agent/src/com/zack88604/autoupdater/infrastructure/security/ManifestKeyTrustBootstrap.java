package com.zack88604.autoupdater.infrastructure.security;

import com.zack88604.autoupdater.infrastructure.http.ServerClient;
import com.zack88604.autoupdater.infrastructure.json.JsonParser;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/** Performs one explicit, user-approved first trust of a server Ed25519 key. */
public final class ManifestKeyTrustBootstrap {
    private static final String TRUST_DIRECTORY = ".mc-update";
    private static final String TRUST_FILE_NAME = "manifest-key-trust.properties";

    private ManifestKeyTrustBootstrap() { }

    public static ManifestSignatureVerifier resolve(File gameDirectory, ServerClient serverClient,
                                                    String configuredKey, String configuredKeyId)
            throws IOException {
        ManifestSignatureVerifier localVerifier = resolveOfflineOrNull(gameDirectory, configuredKey, configuredKeyId);
        if (localVerifier != null) {
            return localVerifier;
        }
        String descriptor = serverClient.getWithFallback("/api/v3/manifest-public-key");
        String algorithm = JsonParser.getString(descriptor, "algorithm");
        String keyId = JsonParser.getString(descriptor, "key_id");
        String publicKey = JsonParser.getString(descriptor, "public_key");
        if (!"Ed25519".equals(algorithm) || keyId == null || publicKey == null) {
            throw new IOException("Update server returned an invalid Ed25519 public-key descriptor");
        }
        String fingerprint = fingerprint(publicKey);
        if (!confirm(serverClient.getCurrentServer(), keyId, fingerprint)) {
            throw new IOException("The server Ed25519 public key was not accepted; Minecraft will not start");
        }
        saveTrust(gameDirectory, publicKey, keyId);
        return new ManifestSignatureVerifier(publicKey, keyId);
    }

    /** Load the persisted key trust needed for offline cache verification. */
    public static ManifestSignatureVerifier resolveOffline(File gameDirectory, String configuredKey,
                                                          String configuredKeyId) throws IOException {
        ManifestSignatureVerifier verifier = resolveOfflineOrNull(gameDirectory, configuredKey, configuredKeyId);
        if (verifier == null) {
            throw new IOException("No local manifest key trust is available");
        }
        return verifier;
    }

    private static ManifestSignatureVerifier resolveOfflineOrNull(File gameDirectory, String configuredKey,
                                                                   String configuredKeyId) throws IOException {
        TrustedKey trustedKey = loadTrust(gameDirectory);
        if (trustedKey != null) {
            ensureConfiguredTrustMatches(trustedKey, configuredKey, configuredKeyId);
            return new ManifestSignatureVerifier(trustedKey.publicKey, trustedKey.keyId);
        }
        if (trim(configuredKey) == null) {
            return null;
        }
        String keyId = trim(configuredKeyId);
        if (keyId == null) {
            throw new IOException("A configured manifest public key requires a key id");
        }
        saveTrust(gameDirectory, configuredKey, keyId);
        clearLegacyTrust(gameDirectory, configuredKey);
        return new ManifestSignatureVerifier(configuredKey, keyId);
    }

    private static String fingerprint(String encodedKey) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    Base64.getDecoder().decode(encodedKey));
            StringBuilder result = new StringBuilder(digest.length * 3 - 1);
            for (int index = 0; index < digest.length; index++) {
                if (index > 0) result.append(':');
                result.append(String.format("%02X", digest[index] & 0xff));
            }
            return result.toString();
        } catch (Exception error) {
            throw new IOException("Cannot calculate server public-key fingerprint", error);
        }
    }

    private static boolean confirm(String server, String keyId, String fingerprint)
            throws IOException {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IOException("Cannot confirm a first-use public key in a headless environment");
        }
        String message = "This is the first connection to this update server.\n\n"
                + "Server: " + server + "\n"
                + "Key ID: " + keyId + "\n"
                + "Ed25519 SHA-256 fingerprint:\n" + fingerprint + "\n\n"
                + "Verify this fingerprint with the server administrator before accepting.\n"
                + "Accept this key and continue updating?";
        AtomicReference<Integer> result = new AtomicReference<Integer>();
        Runnable prompt = new Runnable() {
            @Override public void run() {
                result.set(JOptionPane.showConfirmDialog(null, message, "Trust update server key",
                        JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE));
            }
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) prompt.run();
            else SwingUtilities.invokeAndWait(prompt);
        } catch (Exception error) {
            throw new IOException("Unable to show server-key confirmation", error);
        }
        return Integer.valueOf(JOptionPane.YES_OPTION).equals(result.get());
    }

    private static TrustedKey loadTrust(File gameDirectory) throws IOException {
        File trustFile = new File(new File(gameDirectory, TRUST_DIRECTORY), TRUST_FILE_NAME);
        if (!trustFile.isFile()) {
            return null;
        }
        Properties values = new Properties();
        try (FileInputStream input = new FileInputStream(trustFile)) {
            values.load(input);
        }
        String publicKey = trim(values.getProperty("public-key"));
        String keyId = trim(values.getProperty("key-id"));
        if (publicKey == null || keyId == null) {
            throw new IOException("Stored manifest key trust is incomplete");
        }
        return new TrustedKey(publicKey, keyId);
    }

    private static void ensureConfiguredTrustMatches(TrustedKey trustedKey, String configuredKey,
                                                      String configuredKeyId) throws IOException {
        String publicKey = trim(configuredKey);
        if (publicKey == null) {
            return;
        }
        String keyId = trim(configuredKeyId);
        if (!trustedKey.publicKey.equals(publicKey)
                || (keyId != null && !trustedKey.keyId.equals(keyId))) {
            throw new IOException("Configured manifest key conflicts with local trusted key");
        }
    }

    private static void saveTrust(File gameDirectory, String publicKey, String keyId)
            throws IOException {
        TrustedKey existing = loadTrust(gameDirectory);
        if (existing != null) {
            if (!existing.publicKey.equals(publicKey) || !existing.keyId.equals(keyId)) {
                throw new IOException("A manifest public key already exists; refusing to replace it");
            }
            return;
        }
        Properties values = new Properties();
        values.setProperty("public-key", publicKey);
        values.setProperty("key-id", keyId);
        File directory = new File(gameDirectory, TRUST_DIRECTORY);
        writeProperties(new File(directory, TRUST_FILE_NAME), values,
                "Minecraft Update Agent Manifest Key Trust");
    }

    private static void clearLegacyTrust(File gameDirectory, String publicKey) throws IOException {
        File configuration = new File(gameDirectory, "mc-update.properties");
        if (!configuration.isFile()) {
            return;
        }
        Properties values = new Properties();
        try (FileInputStream input = new FileInputStream(configuration)) {
            values.load(input);
        }
        if (!publicKey.equals(trim(values.getProperty("manifest-public-key")))) {
            return;
        }
        values.remove("manifest-public-key");
        values.remove("manifest-key-id");
        writeProperties(configuration, values, "Minecraft Update Agent Configuration");
    }

    private static void writeProperties(File destination, Properties values, String comment)
            throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Unable to create updater configuration directory");
        }
        File temporary = File.createTempFile("mc-update-", ".tmp", parent);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                values.store(output, comment);
                output.flush();
                output.getFD().sync();
            }
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    private static final class TrustedKey {
        private final String publicKey;
        private final String keyId;

        private TrustedKey(String publicKey, String keyId) {
            this.publicKey = publicKey;
            this.keyId = keyId;
        }
    }
}
