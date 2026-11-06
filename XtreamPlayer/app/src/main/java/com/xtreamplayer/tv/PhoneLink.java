package com.xtreamplayer.tv;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * "Add from your phone": while the login screen is open, the Fire TV serves a small web form
 * on your home Wi-Fi. The QR code points at it with a one-time code, so only someone who can
 * see the TV can send details. Nothing leaves your network.
 */
public class PhoneLink {
    public interface Reply { void done(boolean ok, String message); }

    public interface Handler {
        /** Called on the main thread with the submitted fields; must call reply.done(...) once. */
        void onDetails(Map<String, String> fields, Reply reply);
    }

    private final Handler handler;
    private final String token;
    private ServerSocket server;
    private volatile boolean running;
    private int port;
    private String ip;

    public PhoneLink(Handler h) {
        handler = h;
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 8; i++) b.append(chars.charAt(r.nextInt(chars.length())));
        token = b.toString();
    }

    /** @return the address to open on the phone, or null if there's no network. */
    public String start() {
        ip = localIp();
        if (ip == null) return null;
        for (int p = 8642; p < 8662 && server == null; p++) {
            try {
                server = new ServerSocket(p, 8);
                port = p;
            } catch (IOException ignored) {
            }
        }
        if (server == null) return null;
        running = true;
        Thread t = new Thread(new Runnable() {
            public void run() { loop(); }
        }, "phone-link");
        t.setDaemon(true);
        t.start();
        return url();
    }

    public String url() {
        return "http://" + ip + ":" + port + "/?t=" + token;
    }

    public String shortUrl() {
        return ip + ":" + port;
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
    }

    private void loop() {
        while (running) {
            try {
                final Socket s = server.accept();
                new Thread(new Runnable() {
                    public void run() { handle(s); }
                }).start();
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    // ------------------------------------------------------------ tiny HTTP handling

    private void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) return;
            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("Content-Length")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(c + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";
            Map<String, String> query = parse(path.contains("?") ? path.substring(path.indexOf('?') + 1) : "");
            String route = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;

            if ("GET".equals(method) && "/".equals(route)) {
                if (!token.equals(query.get("t"))) send(s, 403, page("This code has expired", "<p>Open the login screen on your TV and scan the QR code again.</p>"));
                else send(s, 200, form());
            } else if ("POST".equals(method) && "/add".equals(route)) {
                byte[] body = new byte[Math.min(contentLength, 64 * 1024)];
                int read = 0;
                while (read < body.length) {
                    int n = in.read(body, read, body.length - read);
                    if (n < 0) break;
                    read += n;
                }
                Map<String, String> f = parse(new String(body, 0, read, "UTF-8"));
                if (!token.equals(f.get("t"))) {
                    send(s, 403, page("This code has expired", "<p>Open the login screen on your TV and scan the QR code again.</p>"));
                    return;
                }
                send(s, 200, submit(f));
            } else {
                send(s, 404, page("Not found", ""));
            }
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Passes the details to the TV and waits (up to 40 s) for the login result. */
    private String submit(final Map<String, String> f) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = new boolean[1];
        final String[] msg = new String[1];
        Async.MAIN.post(new Runnable() {
            public void run() {
                handler.onDetails(f, new Reply() {
                    public void done(boolean success, String message) {
                        ok[0] = success;
                        msg[0] = message;
                        latch.countDown();
                    }
                });
            }
        });
        if (!latch.await(40, TimeUnit.SECONDS)) {
            return page("Still checking…", "<p>Your TV is still trying to sign in. Have a look at the TV screen.</p>");
        }
        if (ok[0]) return page("You're in ✓", "<p>" + esc(msg[0]) + "</p><p class='dim'>You can close this page.</p>");
        return page("Couldn't sign in", "<p>" + esc(msg[0]) + "</p><p><a href='/?t=" + token + "'>Try again</a></p>");
    }

    private static void send(Socket s, int code, String html) throws IOException {
        byte[] b = html.getBytes("UTF-8");
        String status = code == 200 ? "OK" : code == 403 ? "Forbidden" : "Not Found";
        String head = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes("ISO-8859-1"));
        out.write(b);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) break;
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }

    private static Map<String, String> parse(String q) {
        Map<String, String> m = new HashMap<>();
        for (String kv : q.split("&")) {
            if (kv.isEmpty()) continue;
            int e = kv.indexOf('=');
            try {
                String k = URLDecoder.decode(e < 0 ? kv : kv.substring(0, e), "UTF-8");
                String v = e < 0 ? "" : URLDecoder.decode(kv.substring(e + 1), "UTF-8");
                m.put(k, v.trim());
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    // ------------------------------------------------------------ pages

    private static final String CSS = "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>*{box-sizing:border-box}body{margin:0;min-height:100vh;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;"
            + "background:linear-gradient(160deg,#1c2040 0%,#0a0b12 55%,#2a1030 100%);color:#fff;display:flex;align-items:center;justify-content:center;padding:24px}"
            + ".card{width:100%;max-width:420px;background:rgba(255,255,255,.07);border:1px solid rgba(255,255,255,.08);border-radius:24px;padding:28px}"
            + ".logo{width:56px;height:56px;border-radius:14px;background:linear-gradient(135deg,#2640ff,#7c3aed 45%,#ec4899 80%,#ff805a);"
            + "display:flex;align-items:center;justify-content:center;font-weight:800;font-size:22px;letter-spacing:-.5px;margin-bottom:16px}"
            + "h1{font-size:24px;margin:0 0 6px;letter-spacing:-.4px}p{color:rgba(235,235,245,.75);line-height:1.45;margin:8px 0}.dim{color:rgba(235,235,245,.45);font-size:14px}"
            + "label{display:block;font-size:13px;color:rgba(235,235,245,.6);margin:16px 0 6px}"
            + "input{width:100%;padding:14px 16px;border-radius:12px;border:1px solid rgba(255,255,255,.12);background:rgba(0,0,0,.25);color:#fff;font-size:16px}"
            + "input:focus{outline:none;border-color:#7c3aed}button{width:100%;margin-top:22px;padding:15px;border:0;border-radius:14px;font-size:17px;font-weight:600;"
            + "color:#fff;background:linear-gradient(135deg,#2640ff,#7c3aed 50%,#ec4899)}a{color:#c4b5fd}</style>";

    private String form() {
        return "<!doctype html><html><head><title>YM Player</title>" + CSS + "</head><body><form class='card' method='post' action='/add'>"
                + "<div class='logo'>YM</div><h1>Add a playlist</h1><p>Your details go straight to your TV over your home Wi-Fi.</p>"
                + "<input type='hidden' name='t' value='" + token + "'>"
                + "<label>Account name (optional)</label><input name='name' placeholder='e.g. Home or Sports' autocapitalize='words'>"
                + "<label>Server URL or full M3U link</label><input name='server' required placeholder='http://example.com:8080' autocapitalize='off' autocorrect='off' inputmode='url'>"
                + "<label>Username</label><input name='user' placeholder='Not needed with an M3U link' autocapitalize='off' autocorrect='off'>"
                + "<label>Password</label><input name='pass' placeholder='Not needed with an M3U link' autocapitalize='off' autocorrect='off'>"
                + "<button type='submit'>Add to TV</button><p class='dim'>Developed by Yousuf Miah</p></form></body></html>";
    }

    private static String page(String title, String body) {
        return "<!doctype html><html><head><title>YM Player</title>" + CSS + "</head><body><div class='card'>"
                + "<div class='logo'>YM</div><h1>" + esc(title) + "</h1>" + body + "</div></body></html>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;");
    }

    // ------------------------------------------------------------ helpers

    /** This device's address on the home network (Wi-Fi or Ethernet). */
    static String localIp() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    if (a.isSiteLocalAddress()) return a.getHostAddress(); // 192.168.x / 10.x / 172.16-31.x
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    public static Bitmap qr(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? 0xFF0B0B0F : Color.WHITE;
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }
}
