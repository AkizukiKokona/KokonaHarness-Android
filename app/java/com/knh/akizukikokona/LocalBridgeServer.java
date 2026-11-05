package com.knh.akizukikokona;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loopback HTTP surface for the runtime.
 *
 * <p>The node engine runs in the same app UID, so a tiny local server is the
 * cleanest way to hand it capabilities that only the Android layer can perform:
 * system screenshots through the accessibility service and privileged shell
 * commands through Shizuku. A random token in app-private storage keeps other
 * apps (and the WebView) out.</p>
 */
public final class LocalBridgeServer {
    private static final String TAG = "KokonaBridgeServer";

    private static LocalBridgeServer instance;

    private static final long APPROVAL_TTL_MS = 120_000L;

    private final Context context;
    private final String token;
    private final Map<String, Approval> approvals = new ConcurrentHashMap<>();
    private ServerSocket socket;
    private Thread thread;
    private volatile boolean running;

    /** A single-use grant issued by /confirm and consumed by the next /plus call. */
    private static final class Approval {
        final String capability;
        final long expiresAt;

        Approval(String capability, long expiresAt) {
            this.capability = capability;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Request {
        final String target;
        final Map<String, String> headers;

        Request(String target, Map<String, String> headers) {
            this.target = target;
            this.headers = headers;
        }
    }

    private LocalBridgeServer(Context context) {
        this.context = context.getApplicationContext();
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        StringBuilder builder = new StringBuilder();
        for (byte value : random) builder.append(String.format("%02x", value));
        this.token = builder.toString();
    }

    public static synchronized void start(Context context) {
        if (instance != null) return;
        LocalBridgeServer server = new LocalBridgeServer(context);
        try {
            server.socket = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
        } catch (Exception error) {
            Log.e(TAG, "cannot bind loopback", error);
            return;
        }
        server.running = true;
        writeControlFiles(server);
        server.thread = new Thread(new Runnable() {
            @Override
            public void run() {
                server.acceptLoop();
            }
        }, "kokona-bridge");
        server.thread.setDaemon(true);
        server.thread.start();
        instance = server;
        Log.i(TAG, "bridge listening on " + server.socket.getLocalPort());
    }

    private static void writeControlFiles(LocalBridgeServer server) {
        try {
            File dir = new File(server.context.getFilesDir(), "run");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File port = new File(dir, "screenshot.port");
            FileOutputStream out = new FileOutputStream(port);
            out.write(String.valueOf(server.socket.getLocalPort()).getBytes(StandardCharsets.UTF_8));
            out.close();
            File token = new File(dir, "screenshot.token");
            out = new FileOutputStream(token);
            out.write(server.token.getBytes(StandardCharsets.UTF_8));
            out.close();
            // The engine runs in the same UID; root mode can read anything, but the
            // file must never be readable by other apps. Owner-only, unlike the old
            // setReadable(true, false) which actually granted global read.
            port.setReadable(true, true);
            port.setWritable(true, true);
            token.setReadable(true, true);
            token.setWritable(true, true);
        } catch (Exception error) {
            Log.w(TAG, "cannot write control files", error);
        }
    }

    public static synchronized void stop() {
        LocalBridgeServer server = instance;
        instance = null;
        if (server == null) return;
        server.running = false;
        try {
            server.socket.close();
        } catch (Exception ignored) {
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket client = socket.accept();
                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handle(client);
                    }
                }, "kokona-bridge-client");
                worker.setDaemon(true);
                worker.start();
            } catch (Exception error) {
                if (running) Log.w(TAG, "accept failed", error);
                break;
            }
        }
    }

    /**
     * Authoritative capability map for the bridge. The MCP server has its own copy
     * for messaging, but the app decides: a request that declares a capability
     * different from this table is refused, so a compromised Node process cannot
     * relabel a state-changing action as a read-only one.
     */
    private static String requiredCapability(String action) {
        if (action == null) return null;
        switch (action) {
            case "status":
            case "ui":
            case "find":
            case "wait_for":
            case "foreground":
            case "windows":
            case "screen":
            case "device":
            case "apps":
                return AndroidPermissions.SCREEN;
            case "click":
            case "set_text":
            case "scroll":
            case "gesture":
            case "node_action":
                return AndroidPermissions.INPUT;
            case "global":
            case "open":
            case "open_settings":
                return AndroidPermissions.SYSTEM;
            case "notifications":
                return AndroidPermissions.NOTIFICATIONS_READ;
            case "notification_action":
            case "dismiss_notification":
            case "dismiss_notifications":
                return AndroidPermissions.NOTIFICATIONS_ACT;
            case "media":
            case "media_session":
            case "volume":
            case "brightness":
                return AndroidPermissions.MEDIA;
            case "clipboard":
                return AndroidPermissions.CLIPBOARD;
            case "toast":
            case "vibrate":
            case "speak":
            case "tts_stop":
                return AndroidPermissions.FEEDBACK;
            default:
                return null;
        }
    }

    /**
     * Applies the grant the user chose for this capability. `deny` is final here:
     * the app refuses even when the caller presents a valid one-shot grant, so a
     * stale approval cannot outlive a later "deny" in Settings.
     *
     * @return null when the request may proceed, otherwise the denial message.
     */
    private String authorizeCapability(String capability, String grant) {
        String mode = AndroidPermissions.mode(context, capability);
        if (AndroidPermissions.DENY.equals(mode)) {
            return "「" + capability + "」权限已关闭";
        }
        if (AndroidPermissions.ALLOW.equals(mode)) {
            if (grant != null) approvals.remove(grant);
            return null;
        }
        if (grant == null) return "该能力需要确认";
        Approval approval = approvals.remove(grant);
        if (approval == null) return "确认凭据无效或已被使用";
        if (approval.expiresAt < SystemClock.elapsedRealtime()) return "确认凭据已过期";
        if (!capability.equals(approval.capability)) return "确认凭据与操作能力不匹配";
        return null;
    }

    private static void deny(Socket socket, String message) {
        org.json.JSONObject json = new org.json.JSONObject();
        try {
            json.put("ok", false);
            json.put("error", message);
        } catch (Exception ignored) {
        }
        respond(socket, 403, "application/json; charset=utf-8",
                json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        new SecureRandom().nextBytes(random);
        StringBuilder builder = new StringBuilder(bytes * 2);
        for (byte value : random) builder.append(String.format(Locale.US, "%02x", value));
        return builder.toString();
    }

    private void handle(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(20000);
            InputStream input = socket.getInputStream();
            Request request = readRequest(input);
            if (request == null) return;
            Uri uri = Uri.parse(request.target);
            String path = uri.getPath();
            String supplied = request.headers.get("authorization");
            if (supplied != null && supplied.startsWith("Bearer ")) {
                supplied = supplied.substring("Bearer ".length()).trim();
            } else {
                supplied = request.headers.get("x-kokona-token");
            }
            if (supplied == null || !MessageDigest.isEqual(
                    supplied.getBytes(StandardCharsets.UTF_8),
                    token.getBytes(StandardCharsets.UTF_8))) {
                respond(socket, 403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/policy".equals(path)) {
                respond(socket, 200, "application/json; charset=utf-8",
                        AndroidPermissions.json(context).getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/confirm".equals(path)) {
                String detail = uri.getQueryParameter("detail");
                String capability = uri.getQueryParameter("capability");
                if (detail == null || detail.trim().isEmpty()
                        || capability == null || !AndroidPermissions.isCapability(capability)) {
                    respond(socket, 400, "text/plain; charset=utf-8",
                            "missing detail or capability".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                if (detail.length() > 800) detail = detail.substring(0, 800);
                boolean critical = "critical".equals(uri.getQueryParameter("risk"));
                int timeout = 45;
                try {
                    String raw = uri.getQueryParameter("timeout");
                    if (raw != null) timeout = Math.max(10, Math.min(90, Integer.parseInt(raw)));
                } catch (Exception ignored) {
                }
                Boolean allowed = ConfirmationGate.request(detail, critical, timeout);
                org.json.JSONObject json = new org.json.JSONObject();
                try {
                    json.put("ok", true);
                    json.put("allowed", allowed != null && allowed);
                    json.put("risk", critical ? "critical" : "action");
                    if (allowed == null) {
                        json.put("reason", "app_not_visible");
                    } else if (!allowed) {
                        json.put("reason", "denied_or_timeout");
                    } else {
                        String grant = randomHex(16);
                        approvals.put(grant, new Approval(capability,
                                SystemClock.elapsedRealtime() + APPROVAL_TTL_MS));
                        json.put("grant", grant);
                    }
                } catch (Exception ignored) {
                }
                respond(socket, 200, "application/json; charset=utf-8",
                        json.toString().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/screenshot".equals(path)) {
                String denied = authorizeCapability(AndroidPermissions.SCREENSHOT,
                        uri.getQueryParameter("grant"));
                if (denied != null) {
                    deny(socket, denied);
                    return;
                }
                try {
                    byte[] png = ScreenshotAccessibilityService.capture();
                    respond(socket, 200, "image/png", png);
                } catch (Throwable error) {
                    String message = error.getMessage() == null
                            ? "截图失败" : error.getMessage();
                    respond(socket, 503, "text/plain; charset=utf-8",
                            message.getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            if ("/plus".equals(path)) {
                String required = requiredCapability(uri.getQueryParameter("action"));
                String declared = uri.getQueryParameter("capability");
                if (required == null || declared == null || !declared.equals(required)) {
                    deny(socket, "能力声明不匹配或未知操作");
                    return;
                }
                String denied = authorizeCapability(required, uri.getQueryParameter("grant"));
                if (denied != null) {
                    deny(socket, denied);
                    return;
                }
                try {
                    String body = AndroidCapabilities.run(context, uri);
                    respond(socket, 200, "application/json; charset=utf-8",
                            body.getBytes(StandardCharsets.UTF_8));
                } catch (Throwable error) {
                    String message = error.getMessage() == null
                            ? error.toString() : error.getMessage();
                    org.json.JSONObject json = new org.json.JSONObject();
                    try {
                        json.put("ok", false);
                        json.put("error", message);
                    } catch (Exception ignored) {
                    }
                    respond(socket, 400, "application/json; charset=utf-8",
                            json.toString().getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            if ("/exec".equals(path)) {
                String command = uri.getQueryParameter("cmd");
                if (command == null || command.isEmpty()) {
                    respond(socket, 400, "text/plain; charset=utf-8",
                            "missing cmd".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                if (command.length() > 4000) {
                    respond(socket, 413, "text/plain; charset=utf-8",
                            "command too long".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                String output = RunMode.execAsShell(command);
                if (output == null) {
                    respond(socket, 503, "text/plain; charset=utf-8",
                            "Shizuku 未授权，无法执行特权命令".getBytes(StandardCharsets.UTF_8));
                } else {
                    respond(socket, 200, "text/plain; charset=utf-8",
                            output.getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            respond(socket, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            Log.w(TAG, "request failed", error);
        }
    }

    /** Reads the request line and headers so the token can travel in a header, not a URL. */
    private static Request readRequest(InputStream input) throws Exception {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\r') continue;
            if (value == '\n') {
                if (line.length() == 0) break;
                lines.add(line.toString());
                line.setLength(0);
                if (lines.size() >= 40) break;
            } else {
                line.append((char) value);
            }
        }
        if (lines.isEmpty()) return null;
        String[] parts = lines.get(0).split(" ");
        if (parts.length < 2) return null;
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String header = lines.get(i);
            int colon = header.indexOf(':');
            if (colon <= 0) continue;
            headers.put(header.substring(0, colon).trim().toLowerCase(Locale.US),
                    header.substring(colon + 1).trim());
        }
        return new Request(parts[1], headers);
    }

    private static void respond(Socket socket, int status, String type, byte[] body) {
        try {
            OutputStream out = socket.getOutputStream();
            String reason = status == 200 ? "OK" : status == 403 ? "Forbidden"
                    : status == 404 ? "Not Found" : "Error";
            out.write(("HTTP/1.1 " + status + " " + reason + "\r\n"
                    + "Content-Type: " + type + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();
        } catch (Exception ignored) {
        }
    }
}
