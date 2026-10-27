package com.averolauncher.repository;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.ServerEntry;
import com.averolauncher.util.PathResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class ServerRepository {

    private static final String TAG = "ServerRepository";
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path serversFile;

    public ServerRepository() {
        this.serversFile = PathResolver.launcherDataDir().resolve("servers.json");
    }

    public List<ServerEntry> load() {
        if (!Files.exists(serversFile)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(serversFile);
            List<ServerEntry> servers = gson.fromJson(json, new TypeToken<List<ServerEntry>>() {}.getType());
            return servers != null ? servers : new ArrayList<>();
        } catch (Exception e) {
            AppLogger.get().error(TAG, "Servers file corrupted, backing up and starting fresh", e);
            try {
                Path backup = serversFile.resolveSibling("servers.corrupted." + System.currentTimeMillis() + ".json");
                Files.move(serversFile, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {}
            return new ArrayList<>();
        }
    }

    public void save(List<ServerEntry> servers) {
        try {
            Files.createDirectories(serversFile.getParent());
            String json = gson.toJson(servers);
            Path tmp = serversFile.resolveSibling(serversFile.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            Files.move(tmp, serversFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            AppLogger.get().error(TAG, "Failed to save servers", e);
        }
    }
}
