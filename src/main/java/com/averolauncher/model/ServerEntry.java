package com.averolauncher.model;

public class ServerEntry {
    public String id;
    public String name;
    public String address; // host:port
    public String description;
    public String category = "General";
    public String iconBase64; // may be null

    public ServerEntry() {}

    public ServerEntry(String id, String name, String address) {
        this.id = id;
        this.name = name;
        this.address = address;
    }
}
