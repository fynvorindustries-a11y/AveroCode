package com.averolauncher.servers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Implements the vanilla Minecraft "Server List Ping" protocol (handshake + status request)
 * over a raw TCP socket. Works against any modern Java Edition server (1.7+) without needing
 * a full protocol library — this is the same handshake the vanilla multiplayer server list uses.
 *
 * Protocol: https://minecraft.wiki/w/Java_Edition_protocol/Server_List_Ping
 */
public final class ServerPinger {

    private static final int DEFAULT_PORT = 25565;
    private static final int SOCKET_TIMEOUT_MS = 4000;
    private static final int PROTOCOL_VERSION = 767; // 1.21.x — servers reply with their own version regardless

    private ServerPinger() {}

    /** Parses "host", "host:port", or "[ipv6]:port" into a host/port pair. */
    public static HostPort parseAddress(String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("Empty server address");
        }
        String addr = address.trim();
        if (addr.startsWith("[")) {
            int close = addr.indexOf(']');
            if (close > 0) {
                String host = addr.substring(1, close);
                String rest = addr.substring(close + 1);
                int port = rest.startsWith(":") ? parsePort(rest.substring(1)) : DEFAULT_PORT;
                return new HostPort(host, port);
            }
        }
        int lastColon = addr.lastIndexOf(':');
        if (lastColon > 0 && addr.indexOf(':') == lastColon) { // exactly one colon → host:port
            return new HostPort(addr.substring(0, lastColon), parsePort(addr.substring(lastColon + 1)));
        }
        return new HostPort(addr, DEFAULT_PORT);
    }

    private static int parsePort(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    /** Performs a full ping: connect, handshake, status request, status response, then a timed ping/pong for latency. */
    public static PingResult ping(String address) {
        HostPort hp = parseAddress(address);
        long connectStart = System.currentTimeMillis();

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(hp.host, hp.port), SOCKET_TIMEOUT_MS);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            OutputStream rawOut = socket.getOutputStream();
            InputStream rawIn = socket.getInputStream();
            DataOutputStream out = new DataOutputStream(rawOut);
            DataInputStream in = new DataInputStream(rawIn);

            // ── Handshake packet (state = 1: status) ──
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream hs = new DataOutputStream(handshake);
            writeVarInt(hs, 0x00);                 // packet id
            writeVarInt(hs, PROTOCOL_VERSION);
            writeVarInt(hs, hp.host.length());
            hs.writeBytes(hp.host);
            hs.writeShort(hp.port);
            writeVarInt(hs, 1);                    // next state: status
            writePacket(out, handshake.toByteArray());

            // ── Status request (empty packet id 0x00) ──
            writePacket(out, new byte[]{0x00});

            // ── Read status response ──
            readVarInt(in);                        // total packet length (unused)
            int packetId = readVarInt(in);
            if (packetId != 0x00) {
                return PingResult.offline(address, "Unexpected response packet");
            }
            int jsonLength = readVarInt(in);
            byte[] jsonBytes = new byte[jsonLength];
            in.readFully(jsonBytes);
            String json = new String(jsonBytes, StandardCharsets.UTF_8);

            // ── Ping/pong for latency measurement ──
            long pingPayload = System.currentTimeMillis();
            ByteArrayOutputStream pingPacket = new ByteArrayOutputStream();
            DataOutputStream pp = new DataOutputStream(pingPacket);
            writeVarInt(pp, 0x01);
            pp.writeLong(pingPayload);
            writePacket(out, pingPacket.toByteArray());

            readVarInt(in);          // pong packet length
            readVarInt(in);          // pong packet id (0x01)
            in.readLong();           // echoed payload (unused, we time it ourselves)
            long latencyMs = System.currentTimeMillis() - connectStart;

            return parseStatusJson(address, json, latencyMs);

        } catch (IOException e) {
            return PingResult.offline(address, e.getMessage() != null ? e.getMessage() : "Connection failed");
        }
    }

    private static PingResult parseStatusJson(String address, String json, long latencyMs) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            String motd = "";
            if (root.has("description")) {
                JsonElement desc = root.get("description");
                motd = desc.isJsonObject() && desc.getAsJsonObject().has("text")
                        ? desc.getAsJsonObject().get("text").getAsString()
                        : stripFormatting(desc.toString());
            }

            int online = 0, max = 0;
            if (root.has("players")) {
                JsonObject players = root.getAsJsonObject("players");
                online = players.has("online") ? players.get("online").getAsInt() : 0;
                max = players.has("max") ? players.get("max").getAsInt() : 0;
            }

            String versionName = "";
            if (root.has("version") && root.getAsJsonObject("version").has("name")) {
                versionName = root.getAsJsonObject("version").get("name").getAsString();
            }

            String favicon = null;
            if (root.has("favicon")) {
                String raw = root.get("favicon").getAsString(); // "data:image/png;base64,...."
                int comma = raw.indexOf(',');
                favicon = comma >= 0 ? raw.substring(comma + 1) : raw;
            }

            return new PingResult(address, true, motd, online, max, versionName, (int) latencyMs, favicon, null);
        } catch (Exception e) {
            return PingResult.offline(address, "Malformed status response");
        }
    }

    private static String stripFormatting(String raw) {
        return raw.replaceAll("\\{|\\}|\"text\":|\"", "").trim();
    }

    // ── VarInt helpers (protocol uses LEB128-style VarInts for lengths/ids) ──

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.writeByte(value);
                return;
            }
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int numRead = 0, result = 0;
        byte read;
        do {
            read = in.readByte();
            result |= (read & 0x7F) << (7 * numRead);
            numRead++;
            if (numRead > 5) throw new IOException("VarInt too long");
        } while ((read & 0x80) != 0);
        return result;
    }

    private static void writePacket(DataOutputStream out, byte[] data) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeVarInt(new DataOutputStream(buf), data.length);
        out.write(buf.toByteArray());
        out.write(data);
        out.flush();
    }

    public record HostPort(String host, int port) {}

    /** Result of a status ping. If online is false, only address and errorMessage are meaningful. */
    public static final class PingResult {
        public final String address;
        public final boolean online;
        public final String motd;
        public final int onlinePlayers;
        public final int maxPlayers;
        public final String versionName;
        public final int pingMs;
        public final String faviconBase64; // raw base64 PNG data, no data-uri prefix; may be null
        public final String errorMessage;

        PingResult(String address, boolean online, String motd, int onlinePlayers, int maxPlayers,
                   String versionName, int pingMs, String faviconBase64, String errorMessage) {
            this.address = address;
            this.online = online;
            this.motd = motd;
            this.onlinePlayers = onlinePlayers;
            this.maxPlayers = maxPlayers;
            this.versionName = versionName;
            this.pingMs = pingMs;
            this.faviconBase64 = faviconBase64;
            this.errorMessage = errorMessage;
        }

        static PingResult offline(String address, String errorMessage) {
            return new PingResult(address, false, null, 0, 0, null, -1, null, errorMessage);
        }
    }
}
