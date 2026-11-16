package dev.t1m3.qplayer.sync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.t1m3.qplayer.util.Logger;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tiny LAN discovery for local-playlist sync.
 *
 * <p>This is deliberately not mDNS: qml4j has no Bonsoir equivalent and Android
 * would need a MulticastLock. Plain UDP broadcast to 255.255.255.255 works on
 * both targets with just the INTERNET permission already present.</p>
 *
 * <p>Discovery is tied to the receiver service: while the service is on this
 * device announces its sync port and listens for other announcers. Peers that
 * stop announcing are pruned after {@link #EXPIRE_MS}.</p>
 */
public final class SyncDiscovery {

    public static final int DISCOVERY_PORT = 39891;
    private static final String FORMAT = "qplayer-sync-discovery";
    private static final int VERSION = 1;
    private static final int INTERVAL_MS = 2_000;
    private static final long EXPIRE_MS = 12_000L;
    private static final int PACKET_LIMIT = 1024;

    private final Object lock = new Object();
    private final Map<String, DeviceInfo> devices = new LinkedHashMap<>();
    private volatile boolean running;
    private volatile String selfId = "";
    private volatile String selfName = "";
    private volatile String selfPlatform = "";
    private volatile int selfPort;
    private volatile DatagramSocket sendSocket;
    private volatile DatagramSocket receiveSocket;
    private Thread sender;
    private Thread receiver;

    /** A peer discovered on the LAN. Fields are public for Gson/QML binding. */
    public static final class DeviceInfo {
        public final String deviceId;
        public final String deviceName;
        public final String platform;
        public final String host;
        public final int port;
        public final long lastSeenMs;

        DeviceInfo(String deviceId, String deviceName, String platform,
                   String host, int port, long lastSeenMs) {
            this.deviceId = deviceId;
            this.deviceName = deviceName;
            this.platform = platform;
            this.host = host;
            this.port = port;
            this.lastSeenMs = lastSeenMs;
        }
    }

    /**
     * The LAN address another device should dial. Prefers a real site-local
     * IPv4 address on a non-virtual interface; falls back to the default route.
     * Returns "" when the device has no routable network.
     */
    public static String localLanAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback() || isVirtual(nic.getName())) continue;
                for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                    InetAddress ip = address.getAddress();
                    if (ip != null && ip.getAddress().length == 4
                            && ip.isSiteLocalAddress() && !ip.isLoopbackAddress()) {
                        return ip.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 53);
            InetAddress local = socket.getLocalAddress();
            if (local != null && !local.isAnyLocalAddress() && !local.isLoopbackAddress()) {
                return local.getHostAddress();
            }
        } catch (Exception ignored) {
            // Offline / no default route: manual entry is still possible.
        }
        return "";
    }

    private static boolean isVirtual(String name) {
        String n = name == null ? "" : name.toLowerCase();
        return n.contains("docker") || n.contains("veth") || n.contains("vmnet")
                || n.contains("vethernet") || n.contains("virtual")
                || n.contains("tun") || n.contains("tap") || n.contains("utun")
                || n.contains("wsl") || n.contains("loopback");
    }

    /**
     * Starts announcing and listening. Returns false only when the receive
     * socket cannot be bound; broadcast failures are retried in the thread.
     */
    public synchronized boolean start(String deviceId, String deviceName,
                                      String platform, int syncPort) {
        if (running) return true;
        if (deviceId == null || deviceId.isEmpty()) return false;
        selfId = deviceId;
        selfName = deviceName == null ? "" : deviceName;
        selfPlatform = platform == null ? "" : platform;
        selfPort = syncPort;
        running = true;

        DatagramSocket listen;
        try {
            listen = new DatagramSocket(null);
            listen.setReuseAddress(true);
            // Force IPv4: some Android builds bind a wildcard DatagramSocket to
            // IPv6-only, which silently drops the IPv4 broadcasts the desktop
            // sends. The whole sync protocol is IPv4 on the LAN.
            listen.bind(new java.net.InetSocketAddress(InetAddress.getByName("0.0.0.0"),
                    DISCOVERY_PORT));
            listen.setSoTimeout(1_000);
        } catch (Exception error) {
            running = false;
            Logger.warn("sync discovery bind failed: {}", error.getMessage());
            return false;
        }
        receiveSocket = listen;

        receiver = new Thread(this::receiveLoop, "qplayer-sync-discovery-rx");
        receiver.setDaemon(true);
        receiver.start();

        sender = new Thread(this::sendLoop, "qplayer-sync-discovery-tx");
        sender.setDaemon(true);
        sender.start();
        return true;
    }

    public synchronized void stop() {
        running = false;
        DatagramSocket tx = sendSocket;
        DatagramSocket rx = receiveSocket;
        sendSocket = null;
        receiveSocket = null;
        if (tx != null) tx.close();
        if (rx != null) rx.close();
        if (sender != null) sender.interrupt();
        if (receiver != null) receiver.interrupt();
        sender = null;
        receiver = null;
        synchronized (lock) {
            devices.clear();
        }
    }

    /** Snapshot of currently visible peers. */
    public List<DeviceInfo> devices() {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            devices.values().removeIf(d -> now - d.lastSeenMs > EXPIRE_MS);
            return Collections.unmodifiableList(new ArrayList<>(devices.values()));
        }
    }

    private void sendLoop() {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(0, InetAddress.getByName("0.0.0.0"));
            socket.setBroadcast(true);
            sendSocket = socket;
            while (running) {
                byte[] payload = announcement(false);
                for (InetAddress target : broadcastTargets()) {
                    try {
                        socket.send(new DatagramPacket(payload, payload.length,
                                target, DISCOVERY_PORT));
                    } catch (Exception ignored) {
                        // One dead interface must not stop the others.
                    }
                }
                sleep(INTERVAL_MS);
            }
        } catch (Exception error) {
            if (running) Logger.warn("sync discovery send failed: {}", error.getMessage());
        } finally {
            if (socket != null) socket.close();
            if (sendSocket == socket) sendSocket = null;
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[PACKET_LIMIT];
        while (running) {
            DatagramSocket socket = receiveSocket;
            if (socket == null) return;
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String text = new String(packet.getData(), packet.getOffset(),
                        packet.getLength(), StandardCharsets.UTF_8);
                JsonObject json = new JsonParser().parse(text).getAsJsonObject();
                if (!FORMAT.equals(jsonString(json, "format"))
                        || jsonInt(json, "version", -1) != VERSION) continue;
                String id = jsonString(json, "deviceId");
                if (id == null || id.isEmpty() || id.equals(selfId)) continue;
                int port = jsonInt(json, "port", 0);
                if (port < 1 || port > 65535) continue;
                String name = jsonString(json, "deviceName");
                if (name == null || name.isEmpty()) name = id.substring(0, Math.min(8, id.length()));
                String platform = jsonString(json, "platform");
                String host = packet.getAddress().getHostAddress();
                synchronized (lock) {
                    devices.put(id, new DeviceInfo(id, name,
                            platform == null ? "" : platform, host, port,
                            System.currentTimeMillis()));
                }
                // Broadcast is often one-way on Wi-Fi: the phone can reach the
                // PC but the PC's broadcast is filtered before it reaches the
                // phone. Answer every fresh announcement with a unicast packet
                // so the sender also appears in the other device's list. A
                // reply marked as such is never answered again.
                if (!jsonBool(json, "reply", false)) {
                    byte[] reply = announcement(true);
                    try {
                        socket.send(new DatagramPacket(reply, reply.length,
                                packet.getAddress(), DISCOVERY_PORT));
                    } catch (Exception ignored) {
                        // The peer will retry on its next broadcast.
                    }
                }
            } catch (java.net.SocketTimeoutException timeout) {
                // Expected: lets the loop re-check `running` and prune peers.
                prune();
            } catch (Exception error) {
                if (running) Logger.warn("sync discovery packet ignored: {}", error.getMessage());
            }
        }
    }

    private byte[] announcement(boolean reply) {
        JsonObject json = new JsonObject();
        json.addProperty("format", FORMAT);
        json.addProperty("version", VERSION);
        json.addProperty("deviceId", selfId);
        json.addProperty("deviceName", selfName);
        json.addProperty("platform", selfPlatform);
        json.addProperty("port", selfPort);
        json.addProperty("reply", reply);
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Limited broadcast plus one directed broadcast per real interface. */
    private static Set<InetAddress> broadcastTargets() {
        Set<InetAddress> targets = new LinkedHashSet<>();
        try {
            targets.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                    InetAddress broadcast = address.getBroadcast();
                    if (broadcast != null) targets.add(broadcast);
                }
            }
        } catch (Exception ignored) {
        }
        return targets;
    }

    private void prune() {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            devices.values().removeIf(d -> now - d.lastSeenMs > EXPIRE_MS);
        }
    }

    private static String jsonString(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive()
                ? json.get(key).getAsString() : null;
    }

    private static int jsonInt(JsonObject json, String key, int fallback) {
        try {
            return json.has(key) && json.get(key).isJsonPrimitive()
                    ? json.get(key).getAsInt() : fallback;
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static boolean jsonBool(JsonObject json, String key, boolean fallback) {
        try {
            return json.has(key) && json.get(key).isJsonPrimitive()
                    ? json.get(key).getAsBoolean() : fallback;
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
