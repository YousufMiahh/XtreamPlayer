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
            byte[] data = Http.bytes(url, userAgent, 8000, 15000); // a stuck image server mustn't hold up the rest
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
            return b;
        } catch (Throwable t) {
            return null;
        }
    }
}
