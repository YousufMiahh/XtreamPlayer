package com.xtreamplayer.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal async image loader with a memory cache, sized for TV posters and logos. */
public final class ImageLoader {
    private ImageLoader() {}

    private static final int MAX_KB = (int) Math.min(Runtime.getRuntime().maxMemory() / 1024 / 6, 48 * 1024);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(MAX_KB) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };
    /**
     * Images that just failed, and when. A failure is only remembered for a short while (servers
     * have blips, or are briefly too busy), so the picture is tried again later instead of
     * staying blank for the rest of the session.
     */
    private static final java.util.Map<String, Long> FAILED = Collections.synchronizedMap(new java.util.HashMap<String, Long>());
    private static final long RETRY_MS = 45_000;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(6);

    private static boolean failedRecently(String url) {
        Long t = FAILED.get(url);
        if (t == null) return false;
        if (System.currentTimeMillis() - t < RETRY_MS) return true;
        FAILED.remove(url);
        return false;
    }
    private static String userAgent = Prefs.DEFAULT_UA;

    public static void setUserAgent(String ua) { userAgent = ua; }

    public interface ColorCallback { void color(int c); }

    private static final LruCache<String, Integer> COLORS = new LruCache<>(600);

    /** Main colour of an image (ignores transparent and near-white/black pixels). */
    public static void color(final String url, final ColorCallback cb) {
        if (url == null || url.isEmpty() || failedRecently(url)) return;
        Integer known = COLORS.get(url);
        if (known != null) {
            cb.color(known);
            return;
        }
        final Bitmap cached = CACHE.get(url);
        POOL.execute(new Runnable() {
            public void run() {
                Bitmap bm = cached != null ? cached : fetch(url, 64, 64);
                if (bm == null) return;
                final int c = average(bm);
                COLORS.put(url, c);
                Async.MAIN.post(new Runnable() {
                    public void run() { cb.color(c); }
                });
            }
        });
    }

    static int average(Bitmap b) {
        Bitmap s;
        try {
            s = Bitmap.createScaledBitmap(b, 12, 12, true);
        } catch (Throwable t) {
            return 0xFF3A3F52;
        }
        long r = 0, g = 0, bl = 0, n = 0, ar = 0, ag = 0, ab = 0, an = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < s.getHeight(); y++) {
            for (int x = 0; x < s.getWidth(); x++) {
                int p = s.getPixel(x, y);
                if (android.graphics.Color.alpha(p) < 128) continue;
                ar += android.graphics.Color.red(p);
                ag += android.graphics.Color.green(p);
                ab += android.graphics.Color.blue(p);
                an++;
                android.graphics.Color.colorToHSV(p, hsv);
                if (hsv[1] < 0.18f || hsv[2] < 0.12f) continue; // skip greys, whites and blacks
                float w = hsv[1] * hsv[2];                     // favour vivid pixels
                r += (long) (android.graphics.Color.red(p) * w * 100);
                g += (long) (android.graphics.Color.green(p) * w * 100);
                bl += (long) (android.graphics.Color.blue(p) * w * 100);
                n += (long) (w * 100);
            }
        }
        if (n > 0) return android.graphics.Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
        if (an > 0) return android.graphics.Color.rgb((int) (ar / an), (int) (ag / an), (int) (ab / an));
        return 0xFF3A3F52;
    }

    /** Already-loaded image, or null (the TV guide draws channel logos from here). */
    public static Bitmap cached(String url) {
        return url == null ? null : CACHE.get(url);
    }

    /** Loads an image into the cache without a view, then runs done on the main thread. */
    public static void prefetch(final String url, final int reqW, final int reqH, final Runnable done) {
        if (url == null || url.isEmpty() || failedRecently(url) || CACHE.get(url) != null) return;
        POOL.execute(new Runnable() {
            public void run() {
                if (CACHE.get(url) != null) return;
                Bitmap bm = fetch(url, reqW, reqH);
                if (bm == null) {
                    FAILED.put(url, System.currentTimeMillis());
                    return;
                }
                CACHE.put(url, bm);
                if (done != null) Async.MAIN.post(done);
            }
        });
    }

    private static final java.util.Map<String, Integer> TRIES = Collections.synchronizedMap(new java.util.HashMap<String, Integer>());

    /**
     * Still blank after both goes (the network wasn't up yet, or the image server is busy): try
     * again a little later while the card is on screen, a few times, instead of staying empty.
     */
    private static void retryLater(final ImageView view, final String url, final int reqW, final int reqH, final android.view.View fallback) {
        Integer n = TRIES.get(url);
        if (n != null && n >= 4) return;
        TRIES.put(url, n == null ? 1 : n + 1);
        Async.MAIN.postDelayed(new Runnable() {
            public void run() {
                if (url.equals(view.getTag()) && view.isAttachedToWindow() && view.getDrawable() == null) load(view, url, reqW, reqH, fallback);
            }
        }, RETRY_MS + 1000);
    }

    public static void load(final ImageView view, final String url, final int reqW, final int reqH) { load(view, url, reqW, reqH, null); }

    /**
     * @param fallback shown until the picture arrives, and kept if it can't be loaded (e.g. the
     *                 title in place of a missing poster). Hidden once the picture is in.
     */
    public static void load(final ImageView view, final String url, final int reqW, final int reqH, final android.view.View fallback) {
        view.setTag(url);
        if (fallback != null) fallback.setVisibility(android.view.View.VISIBLE);
        if (url == null || url.isEmpty() || failedRecently(url)) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            if (fallback != null) fallback.setVisibility(android.view.View.GONE);
            return;
        }
        view.setImageDrawable(null);
        POOL.execute(new Runnable() {
            public void run() {
                if (!url.equals(view.getTag())) return; // view was recycled for another row
                Bitmap got = CACHE.get(url); // another card may have fetched it meanwhile
                if (got == null) got = fetch(url, reqW, reqH);
                if (got == null) {
                    try { Thread.sleep(1200); } catch (InterruptedException ignored) { }
                    if (!url.equals(view.getTag())) return;
                    got = fetch(url, reqW, reqH); // one more go: often just a blip
                }
                if (got == null) {
                    FAILED.put(url, System.currentTimeMillis());
                    retryLater(view, url, reqW, reqH, fallback);
                    return;
                }
                final Bitmap bm = got;
                CACHE.put(url, bm);
                Async.MAIN.post(new Runnable() {
                    public void run() {
                        if (!url.equals(view.getTag())) return;
                        view.setImageBitmap(bm);
                        if (fallback != null) fallback.setVisibility(android.view.View.GONE);
                    }
                });
            }
        });
    }

    private static Bitmap fetch(String url, int reqW, int reqH) {
        try {
            byte[] data = download(url);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, d);
            if (b == null) { // some images only decode with the default settings
                b = BitmapFactory.decodeByteArray(data, 0, data.length);
            }
            if (b == null) problem(url, "not a picture this device can show (" + data.length + " bytes" + kind(data) + ")");
            else loaded++;
            return b;
        } catch (Throwable t) {
            problem(url, t instanceof java.net.SocketTimeoutException ? "timed out"
                    : t instanceof java.net.UnknownHostException ? "server not found"
                    : t instanceof javax.net.ssl.SSLException ? "secure connection failed"
                    : t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------ asking the way other players do

    /**
     * How each picture server is asked: 0 as the app asks the provider (its user agent), 1 with the
     * address tidied (spaces and other characters encoded) and Android's own user agent, 2 the
     * same as a web browser. Some picture servers turn away an app they don't know with a
     * "not found"; other players get the pictures because they ask like 1 or 2. Remembered per server.
     */
    private static final java.util.Map<String, Integer> HOST_WAY = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String BROWSER_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    private static byte[] download(String url) throws java.io.IOException {
        String host = "";
        try { host = new java.net.URL(url).getHost(); } catch (Exception ignored) {}
        Integer known = HOST_WAY.get(host);
        int first = known == null ? 0 : known;
        java.io.IOException last = null;
        for (int way = first; way <= 2; way++) {
            try {
                byte[] b;
                if (way == 0) b = Http.bytes(url, userAgent, 8000, 15000); // a stuck image server mustn't hold up the rest
                else {
                    String ua = way == 1 ? System.getProperty("http.agent", BROWSER_UA) : BROWSER_UA;
                    b = Http.bytes(tidy(url), ua, 8000, 15000);
                }
                if (known == null || known != way) HOST_WAY.put(host, way);
                return b;
            } catch (java.io.IOException e) {
                last = e;
                String m = e.getMessage() == null ? "" : e.getMessage();
                if (!m.contains("HTTP 4")) throw e; // only "not found" / "forbidden" are worth asking differently
            }
        }
        throw last;
    }

    /** Spaces and other characters a browser would encode (leaving %xx as it is). */
    static String tidy(String url) {
        StringBuilder sb = new StringBuilder();
        for (byte b : url.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c <= 0x20 || c >= 0x7f || c == '"' || c == '<' || c == '>' || c == '\\' || c == '^' || c == '`' || c == '{' || c == '|' || c == '}') {
                sb.append('%').append(String.format("%02X", c));
            } else sb.append((char) c);
        }
        return sb.toString();
    }

    /** Which way each picture server answered (for Settings). */
    static String ways() {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : HOST_WAY.entrySet()) {
            if (e.getValue() == 0) continue;
            sb.append(sb.length() == 0 ? "" : ", ").append(e.getKey()).append(e.getValue() == 1 ? " (as Android)" : " (as a browser)");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ what went wrong (Settings › About › Pictures)

    private static volatile int loaded, failed;
    private static volatile String lastProblem;
    private static volatile long lastProblemAt;

    private static void problem(String url, String why) {
        failed++;
        String host = url;
        try { host = new java.net.URL(url).getHost(); } catch (Exception ignored) {}
        lastProblem = host + ": " + why;
        lastProblemAt = System.currentTimeMillis();
    }

    private static String kind(byte[] d) {
        if (d.length > 12 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') return ", AVIF/HEIF";
        if (d.length > 0 && (d[0] == '<' || d[0] == '{')) return ", a web page";
        return "";
    }

    /** "312 loaded, 0 failed", plus the last problem if there was one. */
    public static String report() {
        String s = loaded + " loaded, " + failed + " failed";
        String w = ways();
        if (!w.isEmpty()) s += ". Asked differently: " + w;
        if (lastProblem == null) return s;
        long min = (System.currentTimeMillis() - lastProblemAt) / 60000;
        return s + ". Last problem " + (min < 1 ? "just now" : min + " min ago") + ": " + lastProblem;
    }
}
