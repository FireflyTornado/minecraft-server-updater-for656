package com.zack88604.autoupdater.gui.preset;

import com.zack88604.autoupdater.infrastructure.http.ServerGuiPresetTransport;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Retrieves and installs a server GUI preset before UI creation.
 *
 * <p>The configured update server is trusted to publish the descriptor and
 * archive. The descriptor hash and size still detect interrupted or mismatched
 * downloads; loading classes remains the bootstrap's responsibility after the
 * user has approved the server and preset identity.</p>
 */
public final class ServerGuiPresetManager {

    private static final String DESCRIPTOR_PATH = "/api/v3/gui-preset";
    private static final String LEGACY_DESCRIPTOR_PATH = "/api/v2/gui-preset";

    private final ServerGuiPresetTransport transport;

    public ServerGuiPresetManager() {
        this(new ServerGuiPresetTransport());
    }

    ServerGuiPresetManager(ServerGuiPresetTransport transport) {
        this.transport = transport;
    }

    /**
     * Return an installed offer or {@code null} when the optional server offer
     * is unavailable, invalid, or cannot be installed.
     */
    public InstalledPreset install(List<String> serverUrls, GuiPresetStore store) {
        return resolve(serverUrls, store).getInstalled();
    }

    public Resolution resolve(List<String> serverUrls, GuiPresetStore store) {
        boolean offerPresent = false;
        if (serverUrls == null) {
            return new Resolution(null, false);
        }
        for (String serverUrl : serverUrls) {
            ServerGuiPresetTransport.Response response = transport.fetchDescriptorAttempt(
                    serverUrl, DESCRIPTOR_PATH);
            if (response == null) continue;
            boolean legacy = response.getStatusCode() == 404 || response.getStatusCode() == 405;
            if (legacy) {
                response = transport.fetchDescriptorAttempt(serverUrl, LEGACY_DESCRIPTOR_PATH);
            }
            if (response == null || response.getStatusCode() != 200) continue;
            offerPresent = true;

            ServerGuiPresetOffer offer;
            try {
                offer = legacy ? ServerGuiPresetOffer.parse(response.getBody())
                        : ServerGuiPresetOffer.parseCatalog(response.getBody(), GuiPlatform.current());
            } catch (IllegalArgumentException error) {
                System.err.println("[GUI preset] Invalid descriptor: " + error.getMessage());
                continue;
            }
            if (offer == null) {
                System.err.println("[GUI preset] No compatible variant for " + GuiPlatform.current()
                        + " / Java " + GuiPlatform.currentJavaVersion());
                continue;
            }

            InstalledPreset installed = installFromResponse(response, offer, store);
            if (installed != null) {
                return new Resolution(installed, true);
            }
        }
        return new Resolution(null, offerPresent);
    }

    private InstalledPreset installFromResponse(ServerGuiPresetTransport.Response response,
                                                ServerGuiPresetOffer offer,
                                                GuiPresetStore store) {
        File temporary = null;
        try {
            File destination = store.getServerPresetArchive(offer);
            if (!matchesExpectedArchive(destination, offer)) {
                temporary = File.createTempFile("server-gui-", ".jar", destination.getParentFile());
                if (!transport.download(response, offer.getDownloadPath(), temporary,
                        offer.getSize()) || !matchesExpectedArchive(temporary, offer)
                        || compatiblePreset(temporary, offer) == null) {
                    return null;
                }
                store.replaceServerPresetArchive(temporary, destination);
                temporary = null;
            }

            GuiPreset preset = compatiblePreset(destination, offer);
            if (preset == null) {
                return null;
            }
            return new InstalledPreset(offer, preset, response.getServerUrl());
        } catch (IOException error) {
            return null;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary.toPath());
                } catch (IOException ignored) {
                    // A later launch will clean its own fresh temporary file.
                }
            }
        }
    }

    private static GuiPreset compatiblePreset(File archive, ServerGuiPresetOffer offer) {
        GuiPreset preset = GuiPresetStore.readMetadata(archive);
        if (preset == null || !preset.isCompatible()) return null;
        if (offer.getPlatform() != null
                && (!offer.getPlatform().equals(preset.getTargetPlatform())
                    || offer.getMinimumJavaVersion() != preset.getMinimumJavaVersion()
                    || !offer.getVersion().equals(preset.getVersion()))) {
            return null;
        }
        return preset;
    }

    /** Distinguish an unusable published offer from an absent optional offer. */
    public static final class Resolution {
        private final InstalledPreset installed;
        private final boolean offerPresent;

        private Resolution(InstalledPreset installed, boolean offerPresent) {
            this.installed = installed;
            this.offerPresent = offerPresent;
        }

        public InstalledPreset getInstalled() { return installed; }
        public boolean hasOffer() { return offerPresent; }
    }

    private static boolean matchesExpectedArchive(File archive, ServerGuiPresetOffer offer)
            throws IOException {
        return archive.isFile() && archive.length() == offer.getSize()
                && offer.getSha256().equals(sha256(archive));
    }

    private static String sha256(File archive) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(archive)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder hash = new StringBuilder(64);
            for (byte value : digest.digest()) {
                hash.append(Character.forDigit((value >>> 4) & 0x0F, 16));
                hash.append(Character.forDigit(value & 0x0F, 16));
            }
            return hash.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    /** An installed archive plus the server identity that supplied it. */
    public static final class InstalledPreset {
        private final ServerGuiPresetOffer offer;
        private final GuiPreset preset;
        private final String serverUrl;

        private InstalledPreset(ServerGuiPresetOffer offer, GuiPreset preset,
                                String serverUrl) {
            this.offer = offer;
            this.preset = preset;
            this.serverUrl = serverUrl;
        }

        public ServerGuiPresetOffer getOffer() {
            return offer;
        }

        public GuiPreset getPreset() {
            return preset;
        }

        public String getServerUrl() {
            return serverUrl;
        }

        public ServerGuiPresetTrust toTrustRecord() {
            return new ServerGuiPresetTrust(serverUrl, offer.getId(), offer.getArchiveName());
        }
    }
}
