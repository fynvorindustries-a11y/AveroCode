package com.averolauncher.model;

/**
 * A server listed in the Avero backend's public server directory (see
 * GET /api/v1/servers/directory). Distinct from {@link ServerEntry}, which is a
 * server the player has personally saved — the directory is maintained centrally
 * (via the admin API) and fetched fresh each time the Servers page loads.
 *
 * {@link #requiresPremium} marks networks (like Hypixel) that only accept
 * authenticated Microsoft/premium accounts; the Servers page hides these from
 * players using an offline account.
 */
public class DirectoryServerEntry {
    public String id;
    public String name;
    public String address;
    public String description;
    public String category = "Community";
    public boolean requiresPremium;
}
