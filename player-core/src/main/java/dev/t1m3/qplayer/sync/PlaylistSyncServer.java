package dev.t1m3.qplayer.sync;

import dev.t1m3.qplayer.playlist.LocalPlaylist;
import dev.t1m3.qplayer.playlist.PlaylistMerge;
import dev.t1m3.qplayer.playlist.PlaylistSyncSnapshot;
import dev.t1m3.qplayer.playlist.PlaylistStoreAccess;
import dev.t1m3.qplayer.playlist.PlaylistTombstone;
import dev.t1m3.qplayer.util.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Receiving side of device sync: a small HTTP server over a raw ServerSocket.
 *
 * <p>Android has no {@code com.sun.net.httpserver}, so this lives on plain Java
 * sockets and works unchanged on both hosts. Only three endpoints exist, no
 * CORS headers are ever sent (a web page on the LAN must not be able to read
 * the playlist data), and every data request has to be approved on this device
 * before anything is applied.
 */
public final class PlaylistSyncServer {

    /** A playlist snapshot with thousands of songs is still under a few MB. */
    private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 30_000;

    /** Called on a worker thread. Return false to refuse the request. */
    public interface ConfirmCallback {
        boolean confirm(String action, String remoteAddress,
                        PlaylistSyncProtocol.Plan plan);
    }

    private final PlaylistStoreAccess store;
    private final String deviceId;
    private final String deviceName;
    private final String platform;
    private final ConfirmCallback confirm;
    private final ExecutorService workers =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "qplayer-sync-server");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private int port;

    public PlaylistSyncServer(PlaylistStoreAccess store, String deviceId, String deviceName,
                              String platform, ConfirmCallback confirm) {
        this.store = store;
        this.deviceId = deviceId == null ? "" : deviceId;
        this.deviceName = deviceName == null ? "QPlayer" : deviceName;
        this.platform = platform == null ? "" : platform;
        this.confirm = confirm;
    }

    public synchronized boolean start() {
        if (running.get()) return true;
        IOException last = null;
        for (int attempt = 0; attempt < PlaylistSyncProtocol.PORT_PROBES; attempt++) {
            int candidate = PlaylistSyncProtocol.DEFAULT_PORT + attempt;
            try {
                ServerSocket socket = new ServerSocket();
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(candidate));
                serverSocket = socket;
                port = candidate;
                break;
            } catch (IOException error) {
                last = error;
            }
        }
        if (serverSocket == null) {
            Logger.warn("playlist sync server could not bind a port: {}",
                    last == null ? "unknown" : last.getMessage());
            return false;
        }
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "qplayer-sync-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Logger.info("playlist sync server listening on {}", port);
        return true;
    }

    public synchronized void stop() {
        if (!running.get()) return;
        running.set(false);
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        workers.shutdownNow();
        Logger.info("playlist sync server stopped");
    }

    public boolean isRunning() {
        return running.get();
    }

    public int port() {
        return port;
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                final Socket client = serverSocket.accept();
                client.setSoTimeout(SOCKET_TIMEOUT_MS);
                workers.submit(() -> handle(client));
            } catch (IOException error) {
                if (running.get()) Logger.warn("sync accept failed: {}", error.getMessage());
            }
        }
    }

    void handle(Socket client) {
        try (Socket socket = client) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                write(socket, 400, "{\"error\":\"bad request\"}");
                return;
            }
            String method = parts[0];
            String path = parts[1];
            int length = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    try {
                        length = Integer.parseInt(line.substring(15).trim());
                    } catch (NumberFormatException ignored) {
                        length = -1;
                    }
                }
            }
            if (length < 0 || length > MAX_BODY_BYTES) {
                write(socket, 413, "{\"error\":\"payload too large\"}");
                return;
            }
            String body = readBody(reader, length);
            String remote = socket.getInetAddress().getHostAddress();

            if (PlaylistSyncProtocol.PATH_STATUS.equals(path)) {
                if (!"GET".equals(method)) {
                    write(socket, 405, "{\"error\":\"method not allowed\"}");
                    return;
                }
                PlaylistSyncProtocol.Status status = new PlaylistSyncProtocol.Status();
                status.deviceId = deviceId;
                status.deviceName = deviceName;
                status.platform = platform;
                status.port = port;
                write(socket, 200, PlaylistSyncProtocol.toJson(status));
                return;
            }
            if (PlaylistSyncProtocol.PATH_PLAN.equals(path)) {
                handlePlan(socket, method, remote, body);
                return;
            }
            if (PlaylistSyncProtocol.PATH_APPLY.equals(path)) {
                handleApply(socket, method, remote, body);
                return;
            }
            write(socket, 404, "{\"error\":\"not found\"}");
        } catch (SocketTimeoutException timeout) {
            Logger.warn("sync request timed out");
        } catch (Exception error) {
            Logger.warn("sync request failed: {}", error.getMessage());
        }
    }

    private void handlePlan(Socket socket, String method, String remote, String body)
            throws IOException {
        if (!"POST".equals(method)) {
            write(socket, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        PlaylistSyncProtocol.Plan plan;
        try {
            plan = PlaylistSyncProtocol.fromJson(body, PlaylistSyncProtocol.Plan.class);
            PlaylistSyncProtocol.validateFormat(plan == null ? null : plan.format,
                    plan == null ? 0 : plan.formatVersion);
        } catch (RuntimeException | SyncException error) {
            write(socket, 400, "{\"error\":\"invalid plan\"}");
            return;
        }
        PlaylistSyncProtocol.PlanResult result = new PlaylistSyncProtocol.PlanResult();
        result.approved = confirm != null && confirm.confirm("import", remote, plan);
        if (!result.approved) result.reason = "rejected on this device";
        write(socket, result.approved ? 200 : 403, PlaylistSyncProtocol.toJson(result));
    }

    private void handleApply(Socket socket, String method, String remote, String body)
            throws IOException {
        if (!"POST".equals(method)) {
            write(socket, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        PlaylistSyncProtocol.Payload payload;
        try {
            payload = PlaylistSyncProtocol.fromJson(body, PlaylistSyncProtocol.Payload.class);
            PlaylistSyncProtocol.validateFormat(payload == null ? null : payload.format,
                    payload == null ? 0 : payload.formatVersion);
        } catch (RuntimeException | SyncException error) {
            write(socket, 400, "{\"error\":\"invalid payload\"}");
            return;
        }
        if (payload.playlists == null) payload.playlists = new ArrayList<LocalPlaylist>();
        if (payload.deletedPlaylists == null) {
            payload.deletedPlaylists = new ArrayList<PlaylistTombstone>();
        }
        PlaylistSyncProtocol.Plan plan = new PlaylistSyncProtocol.Plan();
        plan.deviceId = payload.deviceId;
        plan.deviceName = payload.deviceName;
        plan.playlistCount = payload.playlists.size();
        for (LocalPlaylist playlist : payload.playlists) {
            plan.trackCount += playlist.tracks == null ? 0 : playlist.tracks.size();
        }
        plan.deletionCount = payload.deletedPlaylists.size();
        if (confirm == null || !confirm.confirm("apply", remote, plan)) {
            write(socket, 403, "{\"error\":\"rejected on this device\"}");
            return;
        }
        try {
            PlaylistSyncSnapshot snapshot = new PlaylistSyncSnapshot(
                    payload.playlists, payload.deletedPlaylists);
            PlaylistMerge.Result merged = store.applySyncSnapshotAndSave(snapshot);
            write(socket, 200, PlaylistSyncProtocol.toJson(
                    PlaylistSyncProtocol.resultOf(merged)));
        } catch (Exception error) {
            PlaylistSyncProtocol.ApplyResult result = new PlaylistSyncProtocol.ApplyResult();
            result.error = error.getMessage() == null ? "apply failed" : error.getMessage();
            write(socket, 500, PlaylistSyncProtocol.toJson(result));
        }
    }

    private static String readBody(BufferedReader reader, int length) throws IOException {
        if (length == 0) return "";
        char[] buffer = new char[length];
        int read = 0;
        while (read < length) {
            int count = reader.read(buffer, read, length - read);
            if (count < 0) break;
            read += count;
        }
        return new String(buffer, 0, read);
    }

    private static void write(Socket socket, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 403 ? "Forbidden"
                : status == 404 ? "Not Found" : status == 405 ? "Method Not Allowed"
                : status == 413 ? "Payload Too Large" : status == 400 ? "Bad Request"
                : status == 500 ? "Internal Server Error" : "Error";
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
                .append("Content-Type: application/json; charset=utf-8\r\n")
                .append("Content-Length: ").append(bytes.length).append("\r\n")
                // No CORS headers on purpose: a browser page must not read this port.
                .append("Connection: close\r\n\r\n");
        OutputStream out = socket.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }
}
