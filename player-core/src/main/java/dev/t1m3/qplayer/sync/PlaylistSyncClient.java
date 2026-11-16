package dev.t1m3.qplayer.sync;

import dev.t1m3.qplayer.playlist.PlaylistSyncSnapshot;
import dev.t1m3.qplayer.util.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Sending side: POSTs this device's playlist snapshot to a receiver. */
public final class PlaylistSyncClient {

    private static final int TIMEOUT_MS = 30_000;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final String deviceId;
    private final String deviceName;

    public PlaylistSyncClient(String deviceId, String deviceName) {
        this.deviceId = deviceId == null ? "" : deviceId;
        this.deviceName = deviceName == null ? "QPlayer" : deviceName;
    }

    public static final class Address {
        public final String host;
        public final int port;

        public Address(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /** Parses "192.168.1.5:39890" or a bare host (default port). */
    public static Address parseAddress(String value, int defaultPort) throws SyncException {
        if (value == null) throw new SyncException("address is empty");
        String text = value.trim();
        if (text.isEmpty()) throw new SyncException("address is empty");
        if (text.startsWith("http://")) text = text.substring(7);
        if (text.startsWith("https://")) throw new SyncException("https is not supported");
        int slash = text.indexOf('/');
        if (slash > 0) text = text.substring(0, slash);
        String host = text;
        int port = defaultPort;
        int colon = text.lastIndexOf(':');
        if (colon > 0) {
            host = text.substring(0, colon);
            try {
                port = Integer.parseInt(text.substring(colon + 1));
            } catch (NumberFormatException error) {
                throw new SyncException("invalid port");
            }
        }
        if (host.isEmpty() || port < 1 || port > 65535) throw new SyncException("invalid address");
        if (!host.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")) {
            throw new SyncException("invalid host");
        }
        return new Address(host, port);
    }

    public PlaylistSyncProtocol.Status status(Address address) throws SyncException {
        String body = request(address, PlaylistSyncProtocol.PATH_STATUS, "GET", null);
        PlaylistSyncProtocol.Status status;
        try {
            status = PlaylistSyncProtocol.fromJson(body, PlaylistSyncProtocol.Status.class);
        } catch (RuntimeException error) {
            throw new SyncException("invalid status response", error);
        }
        if (status == null) throw new SyncException("empty status response");
        try {
            PlaylistSyncProtocol.validateFormat(status.format, status.formatVersion);
        } catch (SyncException error) {
            throw error;
        }
        return status;
    }

    /** Ask the receiver to approve the transfer. Call this before send(). */
    public boolean requestApproval(Address address, PlaylistSyncSnapshot snapshot)
            throws SyncException {
        PlaylistSyncProtocol.Plan plan =
                PlaylistSyncProtocol.planFor(deviceId, deviceName, snapshot);
        String body;
        try {
            body = request(address, PlaylistSyncProtocol.PATH_PLAN, "POST",
                    PlaylistSyncProtocol.toJson(plan));
            PlaylistSyncProtocol.PlanResult result =
                    PlaylistSyncProtocol.fromJson(body, PlaylistSyncProtocol.PlanResult.class);
            return result != null && result.approved;
        } catch (Http403 error) {
            return false;
        } catch (RuntimeException error) {
            throw new SyncException("invalid approval response", error);
        }
    }

    public PlaylistSyncProtocol.ApplyResult send(Address address, PlaylistSyncSnapshot snapshot)
            throws SyncException {
        PlaylistSyncProtocol.Payload payload =
                PlaylistSyncProtocol.payloadFor(deviceId, deviceName, snapshot);
        String body = request(address, PlaylistSyncProtocol.PATH_APPLY, "POST",
                PlaylistSyncProtocol.toJson(payload));
        PlaylistSyncProtocol.ApplyResult result;
        try {
            result = PlaylistSyncProtocol.fromJson(body, PlaylistSyncProtocol.ApplyResult.class);
        } catch (RuntimeException error) {
            throw new SyncException("invalid result response", error);
        }
        if (result == null) throw new SyncException("empty result response");
        return result;
    }

    private String request(Address address, String path, String method, String body)
            throws SyncException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://" + address.host + ":" + address.port + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setDoOutput(body != null);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                OutputStream out = connection.getOutputStream();
                out.write(bytes);
                out.flush();
            }
            int status = connection.getResponseCode();
            if (status == 403) throw new Http403();
            InputStream stream = status < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = stream == null ? "" : read(stream);
            if (status < 200 || status >= 300) {
                throw new SyncException("receiver returned HTTP " + status
                        + (response.isEmpty() ? "" : ": " + truncate(response)));
            }
            return response;
        } catch (Http403 error) {
            throw error;
        } catch (IOException error) {
            throw new SyncException("cannot reach " + address.host + ":" + address.port
                    + " - " + safeMessage(error), error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String read(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int count;
        while ((count = stream.read(buffer)) >= 0) {
            total += count;
            if (total > MAX_RESPONSE_BYTES) throw new IOException("response too large");
            out.write(buffer, 0, count);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String truncate(String text) {
        return text.length() > 160 ? text.substring(0, 160) : text;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    /** 403 is the receiver declining; the caller turns it into a friendly result. */
    private static final class Http403 extends RuntimeException {
    }
}
