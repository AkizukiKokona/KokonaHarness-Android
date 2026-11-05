package com.knh.akizukikokona;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

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

    private final Context context;
    private final String token;
    private ServerSocket socket;
    private Thread thread;
    private volatile boolean running;

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
            // The engine may run as root; keep both files readable but not world-readable.
            port.setReadable(true, false);
            token.setReadable(true, false);
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

    private void handle(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(20000);
            InputStream input = socket.getInputStream();
            String request = readRequest(input);
            if (request == null || request.isEmpty()) return;
            String[] parts = request.split(" ");
            if (parts.length < 2) return;
            Uri uri = Uri.parse(parts[1]);
            String path = uri.getPath();
            String supplied = uri.getQueryParameter("token");
            if (supplied == null || !supplied.equals(token)) {
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
                if (detail == null || detail.trim().isEmpty()) {
                    respond(socket, 400, "text/plain; charset=utf-8",
                            "missing detail".getBytes(StandardCharsets.UTF_8));
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
                    if (allowed == null) json.put("reason", "app_not_visible");
                    else if (!allowed) json.put("reason", "denied_or_timeout");
                } catch (Exception ignored) {
                }
                respond(socket, 200, "application/json; charset=utf-8",
                        json.toString().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/screenshot".equals(path)) {
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

    private static String readRequest(InputStream input) throws Exception {
        StringBuilder builder = new StringBuilder();
        int state = 0;
        byte[] buffer = new byte[1];
        while (input.read(buffer) > 0) {
            char c = (char) buffer[0];
            if (c == '\r') continue;
            if (c == '\n') {
                if (builder.length() == 0) break;
                state++;
                if (state >= 1) break;
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
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
