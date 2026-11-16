package dev.t1m3.qplayer.sync;

import dev.t1m3.qplayer.util.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Device identity and the pending-request handshake shared with QML. */
public final class DeviceSyncState {

    private final Path idFile;
    private final String deviceId;
    private volatile String deviceName = "QPlayer";
    private volatile String platform = "";
    private volatile PlaylistSyncServer server;
    private volatile boolean serverRunning;

    /** A request awaiting this device's approval; null when none. */
    private volatile PlaylistSyncProtocol.Plan pending;
    private volatile String pendingFrom = "";
    private final Object decisionLock = new Object();
    private Boolean decision;
    /** A plan approval also covers the payload that follows within this window. */
    private volatile long approvedUntilMs;

    public DeviceSyncState(Path idFile) {
        this.idFile = idFile;
        this.deviceId = loadOrCreateId(idFile);
    }

    private static String loadOrCreateId(Path file) {
        try {
            if (file != null && Files.exists(file)) {
                String existing = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
                if (!existing.isEmpty()) return existing;
            }
        } catch (IOException error) {
            Logger.warn("device id read failed: {}", error.getMessage());
        }
        String id = UUID.randomUUID().toString();
        try {
            if (file != null) {
                Path parent = file.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.write(file, id.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException error) {
            Logger.warn("device id write failed: {}", error.getMessage());
        }
        return id;
    }

    public String deviceId() {
        return deviceId;
    }

    public String deviceName() {
        return deviceName;
    }

    public void setDeviceName(String name) {
        if (name != null && !name.trim().isEmpty()) deviceName = name.trim();
    }

    public String platform() {
        return platform;
    }

    public void setPlatform(String value) {
        platform = value == null ? "" : value;
    }

    public boolean isServerRunning() {
        return serverRunning;
    }

    public int port() {
        PlaylistSyncServer current = server;
        return current == null ? 0 : current.port();
    }

    /** The receiver's confirm callback: park the request until QML decides. */
    public final PlaylistSyncServer.ConfirmCallback confirmCallback = (action, remote, plan) -> {
        synchronized (decisionLock) {
            long now = System.currentTimeMillis();
            // The payload arrives seconds after the plan is approved. Do not
            // ask the user twice for what is visibly one transfer.
            if ("apply".equals(action) && now < approvedUntilMs) return true;
            // "import" is the plan stage; "apply" is the payload stage.
            pending = plan;
            pendingFrom = remote;
            decision = null;
            decisionLock.notifyAll();
            long deadline = now + 120_000L;
            while (decision == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    pending = null;
                    return false;
                }
                try {
                    decisionLock.wait(remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    pending = null;
                    return false;
                }
            }
            pending = null;
            pendingFrom = "";
            boolean allowed = Boolean.TRUE.equals(decision);
            if (allowed) approvedUntilMs = System.currentTimeMillis() + 60_000L;
            return allowed;
        }
    };

    public PlaylistSyncProtocol.Plan pending() {
        return pending;
    }

    public String pendingFrom() {
        return pendingFrom;
    }

    /** Called from QML. */
    public void approve() {
        synchronized (decisionLock) {
            decision = Boolean.TRUE;
            decisionLock.notifyAll();
        }
    }

    /** Called from QML. */
    public void reject() {
        synchronized (decisionLock) {
            decision = Boolean.FALSE;
            decisionLock.notifyAll();
        }
    }

    public synchronized boolean startServer(PlaylistStoreAccess store) {
        if (serverRunning) return true;
        PlaylistSyncServer created = new PlaylistSyncServer(
                store, deviceId, deviceName, platform, confirmCallback);
        if (!created.start()) return false;
        server = created;
        serverRunning = true;
        return true;
    }

    public synchronized void stopServer() {
        PlaylistSyncServer current = server;
        server = null;
        serverRunning = false;
        if (current != null) current.stop();
        reject();
    }
}
