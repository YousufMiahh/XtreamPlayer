package com.xtreamplayer.tv;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * App-wide safety net:
 *  - Java crashes are written to a report the app shows on the next launch.
 *  - VLC runs native code, whose crashes can't be caught; if the app dies while VLC is
 *    starting, the next launch notices and switches back to the Android player.
 */
public class App extends Application {
    private static final String CRASH_FILE = "last_crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                writeCrash(App.this, e);
                if (previous != null) previous.uncaughtException(t, e);
            }
        });
        // Did VLC take the app down last time? Only give up on it after two crashes in a row
        // (one can happen while VLC sets itself up); a successful playback resets the count.
        SharedPreferences sp = getSharedPreferences("xtream", MODE_PRIVATE);
        if (!sp.getBoolean("vlc_fix_v2", false)) {
            // This version fixes VLC's start-up, so give it a clean slate once.
            sp.edit().putBoolean("vlc_fix_v2", true).putBoolean("vlc_broken", false)
                    .putBoolean("vlc_in_flight", false).putInt("vlc_crashes", 0).commit();
        }
        if (sp.getBoolean("vlc_in_flight", false)) {
            int crashes = sp.getInt("vlc_crashes", 0) + 1;
            SharedPreferences.Editor e = sp.edit().putBoolean("vlc_in_flight", false).putInt("vlc_crashes", crashes);
            if (crashes >= 2) e.putBoolean("vlc_broken", true).putBoolean("vlc_broken_notice", true).putInt("vlc_crashes", 0);
            e.commit();
        }
        // Get VLC ready in the background so the first channel opens first time.
        if (new Prefs(this).engineMode() != 1) VlcHolder.warmUp(this);
    }

    static void writeCrash(Context c, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("YM Player crash — " + new SimpleDateFormat("d MMM yyyy HH:mm:ss", Locale.UK).format(new Date()));
            pw.println("Device: " + Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")");
            pw.println();
            e.printStackTrace(pw);
            pw.flush();
            FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), CRASH_FILE));
            out.write(sw.toString().getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
        }
    }

    /** The last crash report, or null. */
    static String lastCrash(Context c) {
        File f = new File(c.getFilesDir(), CRASH_FILE);
        if (!f.exists()) return null;
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 20000)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(b);
            in.close();
            return new String(b, 0, Math.max(0, n), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** True once per crash report (so the notice isn't shown on every launch). */
    static boolean crashIsNew(Context c) {
        File f = new File(c.getFilesDir(), CRASH_FILE);
        if (!f.exists()) return false;
        SharedPreferences sp = c.getSharedPreferences("xtream", MODE_PRIVATE);
        long seen = sp.getLong("crash_seen", 0);
        if (f.lastModified() <= seen) return false;
        sp.edit().putLong("crash_seen", f.lastModified()).apply();
        return true;
    }

    static void clearCrash(Context c) {
        new File(c.getFilesDir(), CRASH_FILE).delete();
    }
}
