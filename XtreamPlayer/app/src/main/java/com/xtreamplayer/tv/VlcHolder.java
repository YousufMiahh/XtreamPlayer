package com.xtreamplayer.tv;

import android.content.Context;

import org.videolan.libvlc.LibVLC;

import java.util.ArrayList;

/**
 * One LibVLC for the whole app (VLC's recommended setup). Creating and destroying it for every
 * channel is slow and can crash; on first use VLC also has to scan its plugins, so we warm it
 * up in the background at launch, before you pick anything.
 */
public final class VlcHolder {
    private VlcHolder() {}

    /** VLC's relative subtitle sizes (smaller number = bigger text). */
    private static final int[] REL_SIZES = {20, 17, 14, 11};

    private static LibVLC vlc;
    private static String key;

    public static synchronized LibVLC get(Context c, Prefs prefs) {
        ArrayList<String> o = options(prefs);
        String k = o.toString();
        if (vlc != null && !k.equals(key)) {
            // Subtitle look changed in Settings: rebuild (only happens between videos).
            try {
                vlc.release();
            } catch (Throwable ignored) {
            }
            vlc = null;
        }
        if (vlc == null) {
            vlc = new LibVLC(c.getApplicationContext(), o);
            key = k;
        }
        return vlc;
    }

    /** Start VLC in the background so the first channel opens quickly and safely. */
    public static void warmUp(final Context c) {
        final Prefs prefs = new Prefs(c);
        if (prefs.vlcBroken()) return;
        Thread t = new Thread(new Runnable() {
            public void run() {
                // Same crash guard as playback: if this dies natively, App counts it.
                prefs.raw().edit().putBoolean("vlc_in_flight", true).commit();
                try {
                    get(c, prefs);
                } catch (Throwable ignored) {
                }
                prefs.raw().edit().putBoolean("vlc_in_flight", false).commit();
            }
        }, "vlc-warmup");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static ArrayList<String> options(Prefs prefs) {
        ArrayList<String> o = new ArrayList<>();
        o.add("--http-reconnect");
        o.add("--network-caching=1000"); // was 2 s: every channel waited for this before starting
        o.add("--live-caching=1000");
        o.add("--clock-jitter=0");
        o.add("--clock-synchro=0");
        o.add("--audio-time-stretch");
        int color = PlayerActivity.SUB_COLORS[prefs.subtitleColor()] & 0xFFFFFF;
        o.add("--freetype-color=" + color);
        o.add("--freetype-rel-fontsize=" + REL_SIZES[Math.max(0, Math.min(3, prefs.subtitleSize()))]);
        o.add("--freetype-bold");
        if (prefs.subtitleBackground()) {
            o.add("--freetype-background-color=0");
            o.add("--freetype-background-opacity=" + PlayerActivity.SUB_OPACITY_ALPHA[prefs.subtitleOpacity()]);
            o.add("--freetype-shadow-opacity=0");
        } else {
            o.add("--freetype-background-opacity=0");
            o.add("--freetype-shadow-opacity=200");
        }
        return o;
    }
}
