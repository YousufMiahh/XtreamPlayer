package com.xtreamplayer.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Updates straight from the GitHub releases: on launch (at most every few hours) the app asks
 * GitHub for the latest release, and if it's newer than this one offers to download and
 * install it. Android always shows its own "Install" confirmation for apps from outside the
 * store; the first time, it also asks to allow YM Player to install apps.
 * Only proper releases are offered, never test builds.
 */
public final class Updater {
    private Updater() {}

    static final String REPO = "YousufMiahh/XtreamPlayer";
    private static final String APP = "YM Player";
    private static final long EVERY_MS = 3 * 3600_000L;
    private static boolean busy;

    static class Release {
        int number;
        String name, notes, apkUrl;
        long size;
    }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("updater", Context.MODE_PRIVATE); }

    static int installedNumber(Context c) {
        try {
            return (int) c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * @param manual from Settings: always check, and say so if there's nothing new.
     */
    public static void check(final Activity a, final boolean manual) {
        if (busy) return;
        final SharedPreferences sp = sp(a);
        long now = System.currentTimeMillis();
        if (!manual && now - sp.getLong("checked", 0) < EVERY_MS) return;
        busy = true;
        if (manual) Toast.makeText(a, "Checking for updates…", Toast.LENGTH_SHORT).show();
        Async.run(new Async.Work<Release>() {
            public Release run() throws Exception { return latest(); }
        }, new Async.Done<Release>() {
            public void done(Release r, Exception e) {
                busy = false;
                if (a.isFinishing()) return;
                if (e != null || r == null) {
                    if (manual) Toast.makeText(a, "Couldn't check for updates — try again later", Toast.LENGTH_LONG).show();
                    return;
                }
                sp.edit().putLong("checked", System.currentTimeMillis()).apply();
                int mine = installedNumber(a);
                if (r.number <= mine || r.apkUrl == null) {
                    if (manual) Toast.makeText(a, "You're up to date (" + APP + " 1.0." + mine + ")", Toast.LENGTH_LONG).show();
                    return;
                }
                if (!manual && r.number == sp.getInt("later", 0)
                        && System.currentTimeMillis() - sp.getLong("later_at", 0) < 24 * 3600_000L) return; // "Later" pressed today
                offer(a, r);
            }
        });
    }

    /** Asks GitHub for the newest release. */
    static Release latest() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "YMPlayer-Updater");
        try {
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            JSONObject o = new JSONObject(out.toString("UTF-8"));
            Release r = new Release();
            String tag = o.optString("tag_name", "");
            r.number = Integer.parseInt(tag.substring(tag.lastIndexOf('.') + 1)); // "v1.0.51" → 51
            r.name = APP + " " + tag.replaceFirst("^v", "");
            r.notes = o.optString("body", "").trim().replaceAll("(?m)^- ", "•  ");
            JSONArray assets = o.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject as = assets.getJSONObject(i);
                if (as.optString("name", "").endsWith(".apk")) {
                    r.apkUrl = as.optString("browser_download_url", null);
                    r.size = as.optLong("size", 0);
                    break;
                }
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static AlertDialog.Builder dialog(Activity a) {
        return new AlertDialog.Builder(a, Ui.DARK ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert);
    }

    private static void offer(final Activity a, final Release r) {
        String msg = r.name + " is ready to install. Your accounts, favourites and history stay as they are.";
        if (r.notes != null && !r.notes.isEmpty() && !r.notes.startsWith("Latest test build")) {
            msg += "\n\nWhat's new:\n" + (r.notes.length() > 700 ? r.notes.substring(0, 700) + "…" : r.notes);
        }
        dialog(a).setTitle("Update available")
                .setMessage(msg)
                .setPositiveButton("Update now", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { start(a, r); }
                })
                .setNegativeButton("Later", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        sp(a).edit().putInt("later", r.number).putLong("later_at", System.currentTimeMillis()).apply();
                    }
                })
                .show();
    }

    private static void start(final Activity a, final Release r) {
        // Android 8+: the app must be allowed to install apps (asked once)
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            dialog(a).setTitle("One-time permission")
                    .setMessage("To install updates, allow YM Player to install apps. On the next screen, switch it on, then come back and choose Update again.")
                    .setPositiveButton("Open setting", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) {
                            try {
                                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
                            } catch (Exception e) {
                                Toast.makeText(a, "Open Settings → Apps → YM Player → Install unknown apps", Toast.LENGTH_LONG).show();
                            }
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            sp(a).edit().putLong("checked", 0).apply(); // offer again when you come back
            return;
        }
        download(a, r);
    }

    /** Streams the new APK straight into Android's installer, with a progress bar. */
    private static void download(final Activity a, final Release r) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(a, 24);
        box.setPadding(p, p / 2, p, 0);
        final ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final TextView label = Ui.text(a, "Starting…", 15, Ui.TEXT2);
        label.setPadding(0, Ui.dp(a, 10), 0, 0);
        box.addView(label);
        final boolean[] cancelled = {false};
        final AlertDialog d = dialog(a).setTitle("Downloading " + r.name).setView(box).setCancelable(false)
                .setNegativeButton("Cancel", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface di, int w) { cancelled[0] = true; }
                })
                .show();
        final Context app = a.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                PackageInstaller.Session session = null;
                String error = null;
                try {
                    PackageInstaller pi = app.getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    params.setAppPackageName(app.getPackageName());
                    int id = pi.createSession(params);
                    session = pi.openSession(id);
                    HttpURLConnection c = (HttpURLConnection) new URL(r.apkUrl).openConnection();
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(30000);
                    c.setRequestProperty("User-Agent", "YMPlayer-Updater");
                    c.setInstanceFollowRedirects(true);
                    long total = c.getContentLength() > 0 ? c.getContentLength() : r.size;
                    long done = 0, lastUi = 0;
                    try (InputStream in = c.getInputStream(); OutputStream out = session.openWrite("ymplayer.apk", 0, total > 0 ? total : -1)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            if (cancelled[0]) throw new InterruptedException();
                            out.write(buf, 0, n);
                            done += n;
                            long t = System.currentTimeMillis();
                            if (t - lastUi > 200) {
                                lastUi = t;
                                final long dn = done, tt = total;
                                Async.MAIN.post(new Runnable() {
                                    public void run() {
                                        if (tt > 0) bar.setProgress((int) (dn * 1000 / tt));
                                        else bar.setIndeterminate(true);
                                        label.setText(String.format(java.util.Locale.UK, "%.1f MB%s", dn / 1048576f,
                                                tt > 0 ? String.format(java.util.Locale.UK, " of %.1f MB", tt / 1048576f) : ""));
                                    }
                                });
                            }
                        }
                        session.fsync(out);
                    } finally {
                        c.disconnect();
                    }
                    Intent done2 = new Intent(app, UpdateReceiver.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* FLAG_MUTABLE: the installer adds its result */ : 0);
                    PendingIntent pend = PendingIntent.getBroadcast(app, 7, done2, flags);
                    session.commit(pend.getIntentSender()); // Android shows its "Install" screen
                    session = null;
                } catch (InterruptedException e) {
                    error = null;
                } catch (Exception e) {
                    error = "Download failed — check your connection and try again";
                } finally {
                    if (session != null) session.abandon();
                }
                final String err = error;
                Async.MAIN.post(new Runnable() {
                    public void run() {
                        try { d.dismiss(); } catch (Exception ignored) { }
                        if (err != null) Toast.makeText(app, err, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "update-download").start();
    }

    /** Hears back from the installer: shows its confirmation screen, or says what went wrong. */
    public static class UpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { c.startActivity(confirm); } catch (Exception ignored) { }
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(c, "Update didn't install" + (m == null ? "" : ": " + m), Toast.LENGTH_LONG).show();
            }
        }
    }
}
