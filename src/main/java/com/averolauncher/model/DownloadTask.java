package com.averolauncher.model;

import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * Mutable, UI-bindable representation of a single file download.
 * Backed by JavaFX properties so progress bars/labels can bind directly
 * without manual polling.
 */
public class DownloadTask {

    public enum Status { QUEUED, DOWNLOADING, VERIFYING, COMPLETE, FAILED, CANCELLED }

    private final String displayName;
    private final String url;
    private final StringProperty statusText = new SimpleStringProperty("Queued");
    private final LongProperty bytesDownloaded = new SimpleLongProperty(0);
    private final LongProperty totalBytes = new SimpleLongProperty(0);
    private volatile Status status = Status.QUEUED;
    private volatile String errorMessage;

    public DownloadTask(String displayName, String url, long totalBytes) {
        this.displayName = displayName;
        this.url = url;
        this.totalBytes.set(totalBytes);
    }

    public String getDisplayName() { return displayName; }
    public String getUrl() { return url; }
    public Status getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }

    public void setStatus(Status status) {
        this.status = status;
        statusText.set(switch (status) {
            case QUEUED -> "Queued";
            case DOWNLOADING -> "Downloading";
            case VERIFYING -> "Verifying";
            case COMPLETE -> "Complete";
            case FAILED -> "Failed";
            case CANCELLED -> "Cancelled";
        });
    }

    public void setError(String message) {
        this.errorMessage = message;
        setStatus(Status.FAILED);
    }

    public void addBytes(long delta) {
        bytesDownloaded.set(bytesDownloaded.get() + delta);
    }

    public StringProperty statusTextProperty() { return statusText; }
    public LongProperty bytesDownloadedProperty() { return bytesDownloaded; }
    public LongProperty totalBytesProperty() { return totalBytes; }

    public double progressFraction() {
        long total = totalBytes.get();
        if (total <= 0) return 0.0;
        return Math.min(1.0, bytesDownloaded.get() / (double) total);
    }
}
