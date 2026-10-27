package com.averolauncher.model;

import java.nio.file.Path;

public class InstalledMod {
    public String fileName;
    public Path filePath;
    public boolean enabled; // false if file ends in .disabled
    public long fileSizeBytes;
    public String modrinthProjectId; // null if not tracked/installed via Modrinth

    public InstalledMod(String fileName, Path filePath, boolean enabled, long fileSizeBytes) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.enabled = enabled;
        this.fileSizeBytes = fileSizeBytes;
    }

    public String displayName() {
        String name = enabled ? fileName : fileName.substring(0, fileName.length() - ".disabled".length());
        return name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
    }
}
