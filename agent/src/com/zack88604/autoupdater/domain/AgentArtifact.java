package com.zack88604.autoupdater.domain;

import java.util.Objects;

/** Immutable manifest metadata for a self-updatable agent artifact. */
public final class AgentArtifact {

    private final String path;
    private final String sha256;
    private final long size;
    private final String releaseProof;

    public AgentArtifact(String path, String sha256, long size) {
        this(path, sha256, size, null);
    }

    public AgentArtifact(String path, String sha256, long size, String releaseProof) {
        this.path = Objects.requireNonNull(path, "path");
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
        if (size < 0) {
            throw new IllegalArgumentException("size must be >= 0");
        }
        this.size = size;
        this.releaseProof = releaseProof;
    }

    public String getPath() {
        return path;
    }

    public String getSha256() {
        return sha256;
    }

    public long getSize() {
        return size;
    }

    public String getReleaseProof() {
        return releaseProof;
    }
}
