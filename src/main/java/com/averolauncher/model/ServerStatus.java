package com.averolauncher.model;

public class ServerStatus {
    public boolean online;
    public String motd;
    public int onlinePlayers;
    public int maxPlayers;
    public String versionName;
    public int protocolVersion;
    public long pingMs;
    public String errorMessage; // set when online == false

    public static ServerStatus offline(String errorMessage) {
        ServerStatus s = new ServerStatus();
        s.online = false;
        s.errorMessage = errorMessage;
        return s;
    }
}
