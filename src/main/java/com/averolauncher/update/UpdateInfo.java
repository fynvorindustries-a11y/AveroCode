package com.averolauncher.update;

/**
 * A single GitHub release, trimmed to what the UI needs. {@code assetUrl}
 * and {@code assetName} are {@code null} when the release has no asset that
 * looks like a build for the machine we're running on — the UI falls back
 * to just linking the release page in that case.
 */
public final class UpdateInfo {

    public final String tagName;
    public final String displayName;
    public final String htmlUrl;
    public final String releaseNotes;
    public final String assetUrl;
    public final String assetName;
    public final long assetSize;
    /** Signed incremental-update manifest + detached signature (release assets), or null for older releases. */
    public final String manifestUrl;
    public final String manifestSigUrl;

    public UpdateInfo(String tagName, String displayName, String htmlUrl, String releaseNotes,
                       String assetUrl, String assetName, long assetSize) {
        this(tagName, displayName, htmlUrl, releaseNotes, assetUrl, assetName, assetSize, null, null);
    }

    public UpdateInfo(String tagName, String displayName, String htmlUrl, String releaseNotes,
                       String assetUrl, String assetName, long assetSize, String manifestUrl, String manifestSigUrl) {
        this.tagName = tagName;
        this.displayName = displayName;
        this.htmlUrl = htmlUrl;
        this.releaseNotes = releaseNotes;
        this.assetUrl = assetUrl;
        this.assetName = assetName;
        this.assetSize = assetSize;
        this.manifestUrl = manifestUrl;
        this.manifestSigUrl = manifestSigUrl;
    }

    public boolean hasIncrementalManifest() {
        return manifestUrl != null && manifestSigUrl != null;
    }

    public boolean hasDownloadableAsset() {
        return assetUrl != null && !assetUrl.isBlank() && assetName != null && !assetName.isBlank();
    }

    /** First line of the release notes, cleaned up for a one-line summary in the banner. */
    public String shortSummary() {
        if (releaseNotes == null || releaseNotes.isBlank()) {
            return "A new version of AveroLauncher is available.";
        }
        String firstLine = releaseNotes.strip().split("\\R", 2)[0].strip();
        // Release notes commonly lead with a markdown heading and/or an emoji banner
        // line wrapped in bold (e.g. "🛠️ **AveroLauncher 1.1.0 is now available!**") —
        // strip that decoration so a plain JavaFX Label (no markdown rendering) shows
        // readable text instead of literal "#"/"**" characters.
        firstLine = firstLine.replaceFirst("^#+\\s*", "");
        firstLine = firstLine.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
        firstLine = firstLine.replaceFirst("^[^\\p{L}\\p{N}]+", "").strip();
        return firstLine.isBlank() ? "A new version of AveroLauncher is available." : firstLine;
    }
}
