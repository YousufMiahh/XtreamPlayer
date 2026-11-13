package com.xtreamplayer.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import java.io.InputStream;

/**
 * After an update, a summary of what's new (from assets/whats_new.txt, which is also the
 * GitHub release notes). Shown once per new set of notes; never on a fresh install.
 */
final class WhatsNew {
    private WhatsNew() {}

    static String notes(Context c) {
        try {
            InputStream in = c.getAssets().open("whats_new.txt");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** Shows the summary if the app has just been updated to new notes. True if it showed. */
    static boolean maybeShow(Activity a) {
        String notes = notes(a);
        if (notes.isEmpty()) return false;
        SharedPreferences sp = a.getSharedPreferences("whatsnew", Context.MODE_PRIVATE);
        String key = String.valueOf(notes.hashCode());
        String seen = sp.getString("seen", null);
        if (key.equals(seen)) return false;
        sp.edit().putString("seen", key).apply();
        if (seen == null && freshInstall(a)) return false; // new users get the tutorial instead
        show(a, notes);
        return true;
    }

    private static boolean freshInstall(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.firstInstallTime == pi.lastUpdateTime;
        } catch (Exception e) {
            return false;
        }
    }

    static void show(Activity a, String notes) {
        StringBuilder b = new StringBuilder();
        for (String line : notes.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (b.length() > 0) b.append("\n\n");
            b.append(line.startsWith("- ") ? "•  " + line.substring(2) : line);
        }
        String version = "";
        try { version = " " + a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName; } catch (Exception ignored) {}
        new AlertDialog.Builder(a, Ui.DARK ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("What's New in YM Player" + version)
                .setMessage(b.toString())
                .setPositiveButton("Got it", null)
                .show();
    }
}
