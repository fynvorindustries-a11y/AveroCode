package com.averolauncher.model;

public class JavaInstallation {
    public String path;       // absolute path to the java executable
    public String version;    // e.g. "21.0.10"
    public int majorVersion;  // e.g. 21
    public String vendor;
    public String architecture;

    public JavaInstallation() {}

    public JavaInstallation(String path, String version, int majorVersion, String vendor, String architecture) {
        this.path = path;
        this.version = version;
        this.majorVersion = majorVersion;
        this.vendor = vendor;
        this.architecture = architecture;
    }
}
