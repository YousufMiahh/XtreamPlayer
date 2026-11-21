package com.xtreamplayer.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Tiny HTTP helper that follows redirects across http/https (common with Xtream servers). */
public final class Http {
    private Http() {}

    public static HttpURLConnection open(String url, String userAgent) throws IOException { return open(url, userAgent, 15000, 45000); }

    public static HttpURLConnection open(String url, String userAgent, int connectMs, int readMs) throws IOException {
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", userAgent);
            c.setRequestProperty("Accept", "*/*");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("Redirect without location");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code >= 400) {
                c.disconnect();
                throw new IOException("Server replied HTTP " + code);
            }
            return c;
        }
        throw new IOException("Too many redirects");
    }

    public static byte[] bytes(String url, String userAgent) throws IOException { return bytes(url, userAgent, 15000, 45000); }

    public static byte[] bytes(String url, String userAgent, int connectMs, int readMs) throws IOException {
        HttpURLConnection c = open(url, userAgent, connectMs, readMs);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    public static String string(String url, String userAgent) throws IOException {
        return new String(bytes(url, userAgent), "UTF-8");
    }
}
