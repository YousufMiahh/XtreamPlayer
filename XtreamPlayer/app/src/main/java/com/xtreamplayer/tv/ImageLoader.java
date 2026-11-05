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
