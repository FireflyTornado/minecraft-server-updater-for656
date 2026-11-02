package com.zack88604.autoupdater.gui.preset;

import java.util.Locale;
import java.util.Objects;

/** OS and architecture of the JVM used to launch the isolated GUI helper. */
public final class GuiPlatform {
    private final String os;
    private final String arch;

    private GuiPlatform(String os, String arch) {
        this.os = os;
        this.arch = arch;
    }

    /** Read the current Java environment, rather than guessing from the CPU brand. */
    public static GuiPlatform current() {
        return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static GuiPlatform detect(String osName, String architecture) {
        String name = osName.trim().toLowerCase(Locale.ROOT);
        String os = name.contains("mac") || name.equals("darwin") ? "macos"
                : name.startsWith("windows") ? "windows"
                : name.equals("linux") ? "linux" : "unknown";
        String value = architecture.trim().toLowerCase(Locale.ROOT);
        String arch = value.equals("amd64") || value.equals("x86_64") || value.equals("x64")
                ? "x64" : value.equals("aarch64") || value.equals("arm64") ? "arm64" : "unknown";
        return new GuiPlatform(os, arch);
    }

    /** Parse canonical metadata values. Unknown targets are rejected, not guessed. */
    static GuiPlatform declared(String os, String arch) {
        if (!("windows".equals(os) || "linux".equals(os) || "macos".equals(os))
                || !("x64".equals(arch) || "arm64".equals(arch))) {
            throw new IllegalArgumentException("Invalid GUI preset target platform");
        }
        return new GuiPlatform(os, arch);
    }

    public String getOs() { return os; }
    public String getArch() { return arch; }
    public String getId() { return os + "-" + arch; }

    public static int currentJavaVersion() {
        String value = System.getProperty("java.specification.version", "");
        if (value.startsWith("1.")) value = value.substring(2);
        int separator = value.indexOf('.');
        try {
            return Integer.parseInt(separator < 0 ? value : value.substring(0, separator));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof GuiPlatform)) return false;
        GuiPlatform platform = (GuiPlatform) other;
        return os.equals(platform.os) && arch.equals(platform.arch);
    }

    @Override public int hashCode() { return Objects.hash(os, arch); }
    @Override public String toString() { return getId(); }
}
