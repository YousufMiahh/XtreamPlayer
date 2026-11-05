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
    private static final Set<String> FAILED = Collections.synchronizedSet(new HashSet<String>());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static String userAgent = Prefs.DEFAULT_UA;

    public static void setUserAgent(String ua) { userAgent = ua; }

    public interface ColorCallback { void color(int c); }

    private static final LruCache<String, Integer> COLORS = new LruCache<>(600);

    /** Main colour of an image (ignores transparent and near-white/black pixels). */
    public static void color(final String url, final ColorCallback cb) {
        if (url == null || url.isEmpty() || FAILED.contains(url)) return;
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

    public static void load(final ImageView view, final String url, final int reqW, final int reqH) {
        view.setTag(url);
        if (url == null || url.isEmpty() || FAILED.contains(url)) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        POOL.execute(new Runnable() {
            public void run() {
                if (!url.equals(view.getTag())) return; // view was recycled for another row
                final Bitmap bm = fetch(url, reqW, reqH);
                if (bm == null) {
                    FAILED.add(url);
                    return;
                }
                CACHE.put(url, bm);
                Async.MAIN.post(new Runnable() {
                    public void run() {
                        if (url.equals(view.getTag())) view.setImageBitmap(bm);
                    }
                });
            }
        });
    }

    private static Bitmap fetch(String url, int reqW, int reqH) {
        try {
            byte[] data = Http.bytes(url, userAgent);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, d);
        } catch (Throwable t) {
            return null;
        }
    }
}
