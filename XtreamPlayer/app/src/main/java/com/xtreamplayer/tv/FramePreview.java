package com.xtreamplayer.tv;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.LruCache;

import java.util.HashMap;
import java.util.Map;

/**
 * Still frames from a film or episode for the scrub preview. Frames are read straight from the
 * file (only the bit around that point is fetched) on a background thread. While you're
 * dragging, the newest position always wins, so the preview keeps up with the knob; frames
 * already fetched are kept so going back over them is instant.
 *
 * It opens its own connection to the provider while you scrub, which is why it can be turned
 * off in Settings for one-connection accounts.
 */
final class FramePreview {
    interface Listener {
        /** On the main thread. bitmap is null if this file can't give previews. */
        void onFrame(long ms, Bitmap bitmap);
    }

    private static final long BUCKET = 5000; // frames are shared within 5 seconds

    private final String url, userAgent;
    private final int width;
    private final HandlerThread thread;
    private final Handler worker;
    private final LruCache<Long, Bitmap> cache = new LruCache<Long, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(Long k, Bitmap b) { return b.getByteCount(); }
    };
    private Listener listener;
    private MediaMetadataRetriever mmr;
    private boolean failed, busy, released;
    private long wanted = -1;

    FramePreview(String url, String userAgent, int width) {
        this.url = url;
        this.userAgent = userAgent;
        this.width = width;
        thread = new HandlerThread("scrub-preview");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    void setListener(Listener l) { listener = l; }

    boolean failed() { return failed; }

    /** Ask for the frame nearest this point (ms). */
    synchronized void request(long ms) {
        if (released) return;
        final long key = Math.max(0, ms / BUCKET * BUCKET);
        Bitmap hit = cache.get(key);
        if (hit != null) {
            deliver(key, hit);
            return;
        }
        if (failed) {
            deliver(key, null);
            return;
        }
        wanted = key;
        if (!busy) {
            busy = true;
            worker.post(loop);
        }
    }

    private final Runnable loop = new Runnable() {
        public void run() {
            while (true) {
                long t;
                synchronized (FramePreview.this) {
                    if (released || wanted < 0) {
                        busy = false;
                        return;
                    }
                    t = wanted;
                    wanted = -1;
                }
                Bitmap b = grab(t);
                synchronized (FramePreview.this) {
                    if (released) {
                        busy = false;
                        return;
                    }
                    if (b != null) cache.put(t, b);
                }
                deliver(t, b);
            }
        }
    };

    private void deliver(final long ms, final Bitmap b) {
        Async.MAIN.post(new Runnable() {
            public void run() {
                Listener l = listener;
                if (l != null && !released) l.onFrame(ms, b);
            }
        });
    }

    /** Worker thread only. */
    private Bitmap grab(long ms) {
        try {
            if (mmr == null) {
                mmr = new MediaMetadataRetriever();
                if (url.startsWith("file://")) {
                    mmr.setDataSource(android.net.Uri.parse(url).getPath());
                } else if (url.startsWith("/")) {
                    mmr.setDataSource(url);
                } else {
                    Map<String, String> h = new HashMap<>();
                    h.put("User-Agent", userAgent);
                    mmr.setDataSource(url, h);
                }
            }
            long us = ms * 1000L;
            int height = width * 9 / 16;
            Bitmap b;
            if (Build.VERSION.SDK_INT >= 27) {
                b = mmr.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height);
            } else {
                Bitmap full = mmr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (full == null) return null;
                float s = Math.min(width / (float) full.getWidth(), height / (float) full.getHeight());
                b = s < 1f ? Bitmap.createScaledBitmap(full, Math.max(1, Math.round(full.getWidth() * s)),
                        Math.max(1, Math.round(full.getHeight() * s)), true) : full;
                if (b != full) full.recycle();
            }
            return b;
        } catch (Throwable t) { // this file / format can't give previews: stop trying
            failed = true;
            return null;
        }
    }

    /** Stop and let go of the connection. */
    void release() {
        synchronized (this) {
            released = true;
            listener = null;
        }
        worker.post(new Runnable() {
            public void run() {
                try {
                    if (mmr != null) mmr.release();
                } catch (Throwable ignored) {
                }
                mmr = null;
                thread.quitSafely();
            }
        });
    }
}
