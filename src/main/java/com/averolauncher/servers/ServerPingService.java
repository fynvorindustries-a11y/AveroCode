package com.averolauncher.servers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.ServerStatus;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Implements Minecraft's real Server List Ping protocol (the same one the
 * vanilla client and server browser use), as documented at
 * https://minecraft.wiki/w/Java_Edition_protocol/Server_List_Ping - this is
 * a genuine binary handshake over a raw TCP socket, not a REST API:
 *
 *   1. Open a TCP socket to host:port.
 *   2. Send a Handshake packet (packet id 0x00) with next_state=1 (status).
 *   3. Send an empty Status Request packet (packet id 0x00).
 *   4. Read back a Status Response packet containing a JSON payload with
 *      version, players (online/max), and description (MOTD).
 *
 * All multi-byte fields use Minecraft's VarInt encoding, not fixed-width
 * integers - implemented here directly against the spec.
 */
public class ServerPingService {

    private static final String TAG = "ServerPingService";
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 4000;
    private static final int PROTOCOL_VERSION = 767; // 1.21.x; only affects what the handshake reports, servers respond regardless

    private final Gson gson = new Gson();
    private final Executor backgroundExecutor;

    public ServerPingService(Executor backgroundExecutor) {
        this.backgroundExecutor = backgroundExecutor;
    }

    public CompletableFuture<ServerStatus> ping(String address) {
        return CompletableFuture.supplyAsync(() -> pingBlocking(address), backgroundExecutor);
    }

    private ServerStatus pingBlocking(String address) {
        String host = address;
        int port = 25565;
        int colon = address.lastIndexOf(':');
        if (colon > 0 && colon < address.length() - 1) {
            host = address.substring(0, colon);
            try {
                port = Integer.parseInt(address.substring(colon + 1));
            } catch (NumberFormatException ignored) {
                // address had a colon that wasn't a port (unlikely); fall back to default port
            }
        }

        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            writeHandshakePacket(out, host, port);
            writeStatusRequestPacket(out);

            String json = readStatusResponsePacket(in);
            long pingMs = System.currentTimeMillis() - start;

            return parseStatusJson(json, pingMs);
        } catch (java.net.UnknownHostException e) {
            return ServerStatus.offline("Unknown host: " + host);
        } catch (java.net.SocketTimeoutException e) {
            return ServerStatus.offline("Server did not respond in time");
        } catch (java.net.ConnectException e) {
            return ServerStatus.offline("Connection refused");
        } catch (Exception e) {
            AppLogger.get().debug(TAG, "Ping failed for " + address + ": " + e.getMessage());
            return ServerStatus.offline(e.getMessage() != null ? e.getMessage() : "Could not reach server");
        }
    }

    private void writeHandshakePacket(DataOutputStream out, String host, int port) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(payload);
        writeVarInt(p, 0x00);               // packet id: handshake
        writeVarInt(p, PROTOCOL_VERSION);   // protocol version
        writeString(p, host);               // server address
        p.writeShort(port);                 // server port (fixed-width, unlike most fields)
        writeVarInt(p, 1);                  // next state: 1 = status

        writeVarInt(out, payload.size());
        out.write(payload.toByteArray());
    }

    private void writeStatusRequestPacket(DataOutputStream out) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(payload);
        writeVarInt(p, 0x00); // packet id: status request, empty body

        writeVarInt(out, payload.size());
        out.write(payload.toByteArray());
    }

    private String readStatusResponsePacket(DataInputStream in) throws IOException {
        readVarInt(in);           // total packet length (unused, we just read the fields we need)
        int packetId = readVarInt(in);
        if (packetId != 0x00) {
            throw new IOException("Unexpected packet id in status response: " + packetId);
        }
        int jsonLength = readVarInt(in);
        byte[] jsonBytes = new byte[jsonLength];
        in.readFully(jsonBytes);
        return new String(jsonBytes, StandardCharsets.UTF_8);
    }

    private ServerStatus parseStatusJson(String json, long pingMs) {
        ServerStatus status = new ServerStatus();
        status.online = true;
        status.pingMs = pingMs;
        try {
            JsonObject root = gson.fromJson(json, JsonObject.class);

            if (root.has("version") && root.get("version").isJsonObject()) {
                JsonObject version = root.getAsJsonObject("version");
                status.versionName = version.has("name") ? version.get("name").getAsString() : "unknown";
                status.protocolVersion = version.has("protocol") ? version.get("protocol").getAsInt() : -1;
            }

            if (root.has("players") && root.get("players").isJsonObject()) {
                JsonObject players = root.getAsJsonObject("players");
                status.onlinePlayers = players.has("online") ? players.get("online").getAsInt() : 0;
                status.maxPlayers = players.has("max") ? players.get("max").getAsInt() : 0;
            }

            if (root.has("description")) {
                // description can be a plain string or a chat-component object;
                // handle both rather than assuming the simpler shape.
                var desc = root.get("description");
                if (desc.isJsonPrimitive()) {
                    status.motd = desc.getAsString();
                } else if (desc.isJsonObject() && desc.getAsJsonObject().has("text")) {
                    status.motd = desc.getAsJsonObject().get("text").getAsString();
                } else {
                    status.motd = "";
                }
            }
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not fully parse server status JSON: " + e.getMessage());
        }
        return status;
    }

    // ---- VarInt encoding, per Minecraft's protocol spec ----

    private void writeVarInt(DataOutputStream out, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.writeByte(value);
                return;
            }
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    private int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int position = 0;
        byte currentByte;
        while (true) {
            currentByte = in.readByte();
            value |= (currentByte & 0x7F) << position;
            if ((currentByte & 0x80) == 0) break;
            position += 7;
            if (position >= 32) throw new IOException("VarInt is too big");
        }
        return value;
    }

    private void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }
}
