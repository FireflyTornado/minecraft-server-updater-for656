package com.zack88604.autoupdater.infrastructure.security;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.zip.ZipFile;

/** Authenticates executable updater artifacts independently of the resource manifest. */
public final class UpdaterRelease {
    public static final String LAUNCHER = "launcher";
    public static final String CORE = "core";
    private static final String KEY_ENTRY = "META-INF/mc-update-release-public-key.txt";
    private static final int MAX_PROOF_BYTES = 256;

    private UpdaterRelease() { }

    public static Path codeSource(Class<?> type) throws IOException {
        var source = type.getProtectionDomain().getCodeSource();
        if (source == null || !"file".equals(source.getLocation().getProtocol())) {
            throw new IOException("Missing local updater code source: " + type.getName());
        }
        try {
            return Path.of(source.getLocation().toURI()).toRealPath();
        } catch (URISyntaxException error) {
            throw new IOException("Invalid updater code source", error);
        }
    }

    public static PublicKey pinnedKey(Class<?> anchor) throws IOException {
        // Read from the anchor's own JAR without consulting its class loader.
        try (ZipFile jar = new ZipFile(codeSource(anchor).toFile())) {
            var entry = jar.getEntry(KEY_ENTRY);
            if (entry == null) {
                throw new IOException("Updater has no pinned release key. Install a signed updater release.");
            }
            try (InputStream input = jar.getInputStream(entry)) {
                String encoded = readBounded(input).strip();
                return KeyFactory.getInstance("Ed25519").generatePublic(
                        new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            }
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new IOException("Invalid pinned updater release key", error);
        }
    }

    public static Path proofPath(Path directory, String hash) throws IOException {
        if (!hash.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid updater SHA-256");
        }
        return directory.resolve(".mc-update").resolve("releases")
                .resolve(".mc-update-release-" + hash + ".sig");
    }

    public static void verify(Path jar, String role, PublicKey key) throws IOException {
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing or unsupported updater JAR: " + jar.getFileName());
        }
        long size = Files.size(jar);
        String hash = sha256(jar);
        Path proof = proofPath(jar.getParent(), hash);
        try (InputStream input = Files.newInputStream(proof)) {
            verifyProof(readBounded(input).strip(), role, size, hash, key);
        } catch (IOException error) {
            throw new IOException("Release authentication failed for " + jar.getFileName(), error);
        }
    }

    public static void verifyProof(String proof, String role, long size, String hash,
                                   PublicKey key) throws IOException {
        if (proof == null || proof.length() > MAX_PROOF_BYTES || size <= 0
                || !hash.matches("[0-9a-f]{64}")) {
            throw new IOException("Missing or invalid updater release metadata");
        }
        String[] parts = proof.split(":", -1);
        if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")) {
            throw new IOException("Invalid updater release proof format");
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            String payload = "mc-update-release-v1\n" + role + "\n" + parts[0]
                    + "\n" + size + "\n" + hash + "\n";
            verifier.update(payload.getBytes(StandardCharsets.US_ASCII));
            byte[] signature = Base64.getDecoder().decode(parts[1]);
            if (signature.length != 64 || !verifier.verify(signature)) {
                throw new IOException("Invalid updater release signature");
            }
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new IOException("Cannot verify updater release signature", error);
        }
    }

    private static String readBounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_PROOF_BYTES + 1);
        if (bytes.length > MAX_PROOF_BYTES) {
            throw new IOException("Updater release metadata is too large");
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static String sha256(Path jar) throws IOException {
        try (InputStream input = Files.newInputStream(jar)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (GeneralSecurityException error) {
            throw new IOException("Cannot hash updater JAR", error);
        }
    }
}
