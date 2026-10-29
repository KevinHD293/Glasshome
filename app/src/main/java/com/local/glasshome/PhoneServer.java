package com.local.glasshome;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Locale;

/**
 * Tiny HTTP server for the "customize from your phone" page. It only runs while the panel on the TV is open,
 * every API call needs the 4-digit PIN shown on the TV, and 5 wrong PINs lock it until the panel is reopened.
 */
final class PhoneServer {
    interface Bridge {
        String stateJson();                       // current settings as JSON (called on a worker thread)
        void apply(String kind, String json);     // change a setting (the bridge hops to the UI thread)
        String upload(byte[] image);              // a photo sent from the phone; returns JSON
        String page();                            // the HTML page
    }

    private volatile ServerSocket server;
    private volatile boolean running;
    private Bridge bridge;
    private String pin = "";
    private int fails = 0;

    /** Starts the server and returns the address to open on the phone, or null if no port could be opened. */
    synchronized String start(Bridge b) {
        stop();
        bridge = b;
        pin = String.format(Locale.US, "%04d", new SecureRandom().nextInt(10000));
        fails = 0;
        ServerSocket s = null;
        int port = 0;
        for (int p = 8765; p <= 8775 && s == null; p++) {
            try { s = new ServerSocket(p); port = p; } catch (IOException ignored) { s = null; }
        }
        if (s == null) return null;
        server = s;
        running = true;
        final ServerSocket fs = s;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { loop(fs); }
        }, "phone-settings");
        t.setDaemon(true);
        t.start();
        return "http://" + ip() + ":" + port;
    }

    String pin() { return pin; }

    synchronized void stop() {
        running = false;
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private void loop(ServerSocket s) {
        while (running) {
            try {
                final Socket c = s.accept();
                c.setSoTimeout(5000);
                Thread t = new Thread(new Runnable() {
                    @Override public void run() { handle(c); }
                });
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                break;
            }
        }
    }

    private void handle(Socket sock) {
        try {
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            String req = readLine(in);
            if (req == null) return;
            String[] parts = req.split(" ");
            if (parts.length < 2) return;
            String method = parts[0], path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            int len = 0;
            String hdrPin = "";
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String k = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String v = line.substring(colon + 1).trim();
                if (k.equals("content-length")) {
                    try { len = Integer.parseInt(v); } catch (Exception e) { len = 0; }
                } else if (k.equals("x-pin")) {
                    hdrPin = v;
                }
            }

            if (path.equals("/") || path.equals("/index.html")) {
                send(out, 200, "text/html; charset=utf-8", bridge.page());
                return;
            }
            if (!path.startsWith("/api/")) { send(out, 404, "text/plain; charset=utf-8", "not found"); return; }

            synchronized (this) {   // the PIN is checked before any body is read
                if (fails >= 5) { send(out, 429, "application/json; charset=utf-8", "{\"error\":\"locked\"}"); return; }
                if (!MessageDigest.isEqual(hdrPin.getBytes("UTF-8"), pin.getBytes("UTF-8"))) {
                    fails++;
                    send(out, 401, "application/json; charset=utf-8", "{\"error\":\"pin\"}");
                    return;
                }
            }

            boolean upload = method.equals("POST") && path.equals("/api/upload");
            int limit = upload ? 4 * 1024 * 1024 : 65536;
            if (len > limit) { send(out, 413, "application/json; charset=utf-8", "{\"error\":\"too large\"}"); return; }
            byte[] raw = new byte[Math.max(len, 0)];
            int off = 0;
            while (off < len) {
                int r = in.read(raw, off, len - off);
                if (r < 0) break;
                off += r;
            }

            if (path.equals("/api/state")) {
                send(out, 200, "application/json; charset=utf-8", bridge.stateJson());
            } else if (upload) {
                byte[] img = off == raw.length ? raw : java.util.Arrays.copyOf(raw, off);
                send(out, 200, "application/json; charset=utf-8", bridge.upload(img));
            } else if (method.equals("POST")) {
                bridge.apply(path.substring(5), new String(raw, 0, off, "UTF-8"));
                send(out, 200, "application/json; charset=utf-8", "{\"ok\":true}");
            } else {
                send(out, 404, "text/plain; charset=utf-8", "not found");
            }
        } catch (Exception ignored) {
        } finally {
            try { sock.close(); } catch (IOException ignored) { }
        }
    }

    private static void send(OutputStream out, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes("UTF-8");
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Error") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') break;
            if (ch != '\r') sb.append((char) ch);
            if (sb.length() > 8192) break;
        }
        if (ch < 0 && sb.length() == 0) return null;
        return sb.toString();
    }

    /** This TV's IPv4 address on the local network (Wi-Fi or Ethernet). */
    static String ip() {
        try {
            String best = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String name = ni.getName();
                        if (best == null || name.startsWith("wlan") || name.startsWith("eth")) best = a.getHostAddress();
                    }
                }
            }
            return best != null ? best : "?";
        } catch (Exception e) {
            return "?";
        }
    }
}
