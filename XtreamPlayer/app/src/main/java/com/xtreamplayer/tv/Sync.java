package com.xtreamplayer.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cross-device sync: favourites, resume points, Continue Watching, watched, queue, hidden and
 * renamed categories, picture shape and audio sync, shared with your other YM Player devices
 * that use the same login. Format and encryption: SyncCore. Server: Supabase (two functions).
 *
 * Runs after a change (a few seconds later, so many changes become one), while playing (every
 * 20 seconds at most), whenever the app comes to the front, and every 30 seconds while it's open.
 */
final class Sync {
    private Sync() {}

    static final String URL = "https://lqvwqqfdolxkthdqsnct.supabase.co";
    /** The public ("publishable") key: it can only call the two sync functions. */
    static final String KEY = "sb_publishable_PiM4D3uKIbWhVDd4AzL5Vw_QX8f9J8u";

    /** The maps this app keeps (all of them on the TV). */
    static final List<String> MAPS = Arrays.asList(SyncCore.MAPS);
    private static final String[] TYPES = {Item.LIVE, Item.MOVIE, Item.SERIES};

    interface Listener { void changed(); }

    private static final ExecutorService ONE = Executors.newSingleThreadExecutor();
    private static final Set<Listener> LISTENERS = new HashSet<>();
    private static Context app;
    private static boolean running, again;
    private static Runnable pending;
    private static long pendingAt;
    private static volatile long quietUntil; // our own writes don't count as changes
    private static SharedPreferences.OnSharedPreferenceChangeListener watcher; // kept: prefs only hold it weakly

    static boolean configured() { return !URL.isEmpty() && !KEY.isEmpty(); }

    // ================================================================ starting, scheduling

    /** Once, at launch: syncs after this device's own changes. */
    static void start(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        final Prefs p = new Prefs(app);
        watcher = new SharedPreferences.OnSharedPreferenceChangeListener() {
            public void onSharedPreferenceChanged(SharedPreferences sp, String k) {
                if (k == null || System.currentTimeMillis() < quietUntil) return;
                if (isSetting(k)) { soon(4000); return; } // subtitle style, languages, Home rows
                String n = p.ns(), rest;
                if (!n.isEmpty()) {
                    if (!k.startsWith(n)) return;
                    rest = k.substring(n.length());
                } else {
                    if (k.matches("^a\\d+_.*")) return;
                    rest = k;
                }
                if (rest.startsWith("pos_") && !sp.contains(k)) soon(4000); // removed from Continue Watching
                else if (rest.startsWith("pos_") || rest.startsWith("dur_")) later();
                else if (isSynced(rest)) soon(4000);
            }
        };
        p.raw().registerOnSharedPreferenceChangeListener(watcher);
        Async.MAIN.postDelayed(new Runnable() {
            public void run() {
                if (foreground) now(); // another device's changes show within half a minute
                Async.MAIN.postDelayed(this, 30000);
            }
        }, 30000);
    }

    // ================================================================ settings shared too (not per account)

    private static final String[] INT_SETTINGS = {"sub_size", "sub_color", "sub_bg_opacity"};
    private static final String[] STR_SETTINGS = {"sub_lang", "audio_lang"};

    private static boolean isSetting(String k) {
        return Arrays.asList(INT_SETTINGS).contains(k) || Arrays.asList(STR_SETTINGS).contains(k) || k.equals("sub_bg")
                || (k.startsWith("home_") && Arrays.asList(Prefs.HOME_KEYS).contains(k.substring(5)));
    }

    /** Only ones set on this device (a default here mustn't replace a choice made on another device). */
    private static JSONObject readSettings(SharedPreferences sp) throws Exception {
        JSONObject out = new JSONObject();
        for (String k : INT_SETTINGS) if (sp.contains(k)) out.put(k, new JSONObject().put("v", sp.getInt(k, 0)));
        for (String k : STR_SETTINGS) if (sp.contains(k)) out.put(k, new JSONObject().put("v", sp.getString(k, "")));
        if (sp.contains("sub_bg")) out.put("sub_bg", new JSONObject().put("v", sp.getBoolean("sub_bg", false)));
        for (String h : Prefs.HOME_KEYS) if (sp.contains("home_" + h)) out.put("home_" + h, new JSONObject().put("v", sp.getBoolean("home_" + h, true)));
        return out;
    }

    private static void writeSettings(SharedPreferences.Editor e, JSONObject next, JSONObject prev) {
        if (next == null) return;
        for (String k : SyncCore.keys(next)) {
            if (!isSetting(k)) continue;
            Object v = next.optJSONObject(k) == null ? null : next.optJSONObject(k).opt("v");
            if (v == null) continue;
            JSONObject p = prev == null ? null : prev.optJSONObject(k);
            if (p != null && String.valueOf(p.opt("v")).equals(String.valueOf(v))) continue;
            if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
            else if (v instanceof Number) e.putInt(k, ((Number) v).intValue());
            else e.putString(k, String.valueOf(v));
        }
    }

    private static boolean isSynced(String k) {
        return k.startsWith("fav_") || k.startsWith("favcat_") || k.startsWith("hide_") || k.startsWith("show_cat_")
                || k.startsWith("catname_") || k.startsWith("pic_") || k.startsWith("adelay_") || k.startsWith("hist_")
                || k.equals("watched") || k.equals("queue") || k.equals("upnext") || k.equals("rec_block");
    }

    /** A sync in a few seconds (replacing one already waiting). */
    static void soon(long ms) {
        if (app == null) return;
        if (pending != null) Async.MAIN.removeCallbacks(pending);
        pending = new Runnable() { public void run() { pending = null; now(); } };
        pendingAt = System.currentTimeMillis() + ms;
        Async.MAIN.postDelayed(pending, ms);
    }

    /** Within 20 seconds, without putting off a sync already waiting (resume points while playing). */
    static void later() {
        if (pending != null) return;
        soon(20000);
    }

    private static boolean foreground;

    /** The app came to the front: get what other devices changed straight away. */
    static void resumed() {
        foreground = true;
        soon(300);
    }

    /** The app went to the back: send what's changed here now. */
    static void left() {
        foreground = false;
        now();
    }

    static void addListener(Listener l) { LISTENERS.add(l); }
    static void removeListener(Listener l) { LISTENERS.remove(l); }

    /** Sync now (or straight after the one running). Main thread. */
    static void now() {
        now(null);
    }

    static void now(final Runnable done) {
        if (app == null) return;
        if (pending != null) { Async.MAIN.removeCallbacks(pending); pending = null; }
        if (running) { again = true; if (done != null) Async.MAIN.postDelayed(done, 3000); return; }
        running = true;
        Async.runOn(ONE, new Async.Work<Boolean>() {
            public Boolean run() throws Exception { return once(app); }
        }, new Async.Done<Boolean>() {
            public void done(Boolean changed, Exception e) {
                running = false;
                if (changed != null && changed) {
                    for (Listener l : new ArrayList<>(LISTENERS)) {
                        try { l.changed(); } catch (Exception ignored) {}
                    }
                }
                if (done != null) done.run();
                if (again) { again = false; now(); }
            }
        });
    }

    // ================================================================ status (Settings)

    private static SharedPreferences store(Context c) { return c.getSharedPreferences("sync", Context.MODE_PRIVATE); }

    static Long lastOk() {
        if (app == null) return null;
        Prefs p = new Prefs(app);
        long t = store(app).getLong(p.ns() + "ok_at", 0);
        return t == 0 ? null : t;
    }

    /** "Synced 2 min ago", or why it isn't. */
    static String label(Context c) {
        Prefs p = new Prefs(c);
        if (!configured()) return "Not set up in this version";
        if (!p.syncEnabled()) return "Off";
        SharedPreferences s = store(c);
        long ok = s.getLong(p.ns() + "ok_at", 0), fail = s.getLong(p.ns() + "fail_at", 0);
        if (fail > ok) return "Couldn't reach the sync server";
        if (ok == 0) return "Not synced yet";
        long min = (System.currentTimeMillis() - ok) / 60000;
        if (min < 1) return "Synced just now";
        if (min < 60) return "Synced " + min + " min ago";
        return "Synced " + (min / 60) + " h ago";
    }

    /** After signing in again or removing an account: start this account's sync from scratch. */
    static void forget(Context c) {
        Prefs p = new Prefs(c);
        String n = p.ns();
        store(c).edit().remove(n + "shadow").remove(n + "keys").remove(n + "ok_at").remove(n + "fail_at").apply();
    }

    // ================================================================ one sync

    private static boolean once(Context c) throws Exception {
        Prefs p = new Prefs(c);
        if (!configured() || !p.syncEnabled() || !p.hasAccount()) return false;
        String n = p.ns();
        SharedPreferences s = store(c);
        try {
            String[] keys = keysFor(p, s);
            byte[] key = SyncCore.unhex(keys[1]);
            for (int attempt = 0; attempt < 3; attempt++) {
                if (!p.ns().equals(n)) return false; // switched account meanwhile
                String sh = s.getString(n + "shadow", null);
                JSONObject shadow = sh == null ? null : new JSONObject(sh);
                JSONObject prev = readLocal(p);
                long now = System.currentTimeMillis();
                JSONObject local = SyncCore.stamp(prev, shadow, now, MAPS);
                JSONArray rows = new JSONArray(rpc("ym_sync_get", new JSONObject().put("p_id", keys[0])));
                JSONObject row = rows.length() > 0 ? rows.getJSONObject(0) : null;
                JSONObject remote = null;
                if (row != null) {
                    try {
                        remote = new JSONObject(SyncCore.decrypt(key, row.getString("data")));
                    } catch (Exception ignored) {
                        remote = null; // not readable with this key: replaced
                    }
                }
                Object[] m = SyncCore.merge(local, remote == null ? SyncCore.emptyDoc() : remote, now);
                JSONObject doc = (JSONObject) m[0];
                boolean dirty = remote == null || (Boolean) m[1];
                if (dirty) {
                    JSONObject body = new JSONObject().put("p_id", keys[0]).put("p_data", SyncCore.encrypt(key, doc.toString(), null))
                            .put("p_version", row == null ? 0 : row.optLong("version"));
                    String v = rpc("ym_sync_put", body).trim();
                    if (v.isEmpty() || v.equals("null")) continue; // another device saved first: merge with theirs
                }
                if (!p.ns().equals(n)) return false;
                boolean changed = writeLocal(p, SyncCore.toState(doc), prev);
                s.edit().putString(n + "shadow", doc.toString()).putLong(n + "ok_at", System.currentTimeMillis()).apply();
                return changed;
            }
        } catch (Exception e) {
            android.util.Log.w("YMSync", "sync failed", e);
        }
        s.edit().putLong(n + "fail_at", System.currentTimeMillis()).apply();
        return false;
    }

    private static String[] keysFor(Prefs p, SharedPreferences s) throws Exception {
        String n = p.ns();
        String fp = SyncCore.sha256Hex(p.server() + "\n" + p.user() + "\n" + p.pass());
        String saved = s.getString(n + "keys", null);
        if (saved != null) {
            String[] parts = saved.split(",");
            if (parts.length == 3 && parts[0].equals(fp)) return new String[]{parts[1], parts[2]};
        }
        String[] k = SyncCore.deriveKeys(p.server(), p.user(), p.pass());
        s.edit().putString(n + "keys", fp + "," + k[0] + "," + k[1]).apply();
        return k;
    }

    private static String rpc(String fn, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(URL + "/rest/v1/rpc/" + fn).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("apikey", KEY);
            if (KEY.startsWith("eyJ")) c.setRequestProperty("Authorization", "Bearer " + KEY);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] out = body.toString().getBytes("UTF-8");
            try (OutputStream os = c.getOutputStream()) { os.write(out); }
            int code = c.getResponseCode();
            if (code >= 400) throw new java.io.IOException("sync " + fn + ": HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
                return b.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    // ================================================================ this device's data <-> the shared format

    /** Keys of the current account without its prefix (the first account's have none: skip others' "a123_…"). */
    private static List<String> accountKeys(Prefs p) {
        String n = p.ns();
        List<String> out = new ArrayList<>();
        for (String k : p.raw().getAll().keySet()) {
            if (n.isEmpty()) { if (!k.matches("^a\\d+_.*")) out.add(k); }
            else if (k.startsWith(n)) out.add(k.substring(n.length()));
        }
        return out;
    }

    private static Set<String> strSet(SharedPreferences sp, String k) {
        try {
            return new HashSet<>(sp.getStringSet(k, new HashSet<String>()));
        } catch (ClassCastException e) {
            Set<String> out = new HashSet<>();
            JSONArray a = SyncCore.arr(sp.getString(k, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
            return out;
        }
    }

    private static long num(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return 0; }
    }

    static JSONObject readLocal(Prefs p) throws Exception {
        SharedPreferences sp = p.raw();
        String n = p.ns();
        Map<String, ?> all = sp.getAll();
        JSONObject st = new JSONObject();
        for (String m : MAPS) st.put(m, new JSONObject());
        JSONObject hist = new JSONObject();
        st.put("hist", hist);
        JSONObject items = new JSONObject();
        for (String kind : new String[]{"live", "vod"}) {
            JSONArray a = SyncCore.arr(sp.getString(n + "hist_" + kind, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i);
                if (it == null) continue;
                String k = it.optString("type") + ":" + it.optString("id");
                hist.put(k, new JSONObject().put("o", i).put("i", it));
                items.put(k, it);
            }
        }
        for (String k : accountKeys(p)) {
            Object v = all.get(n + k);
            if (k.startsWith("pos_")) {
                String ik = k.substring(4);
                long pos = num(v);
                if (pos <= 0) continue;
                JSONObject e = new JSONObject().put("p", pos).put("d", num(all.get(n + "dur_" + ik)));
                if (items.has(ik)) e.put("i", items.get(ik));
                st.getJSONObject("pos").put(ik, e);
            } else if (k.startsWith("catname_")) {
                String rest = k.substring(8);
                int u = rest.indexOf('_');
                String name = v == null ? "" : String.valueOf(v);
                if (u > 0 && !name.isEmpty()) st.getJSONObject("catname").put(rest.substring(0, u) + ":" + rest.substring(u + 1), new JSONObject().put("n", name));
            } else if (k.startsWith("pic_")) {
                long mode = num(v);
                if (mode > 0) st.getJSONObject("pic").put(k.substring(4), new JSONObject().put("m", mode));
            } else if (k.startsWith("adelay_")) {
                long ms = num(v);
                if (ms == 0) continue;
                String rest = k.substring(7);
                String ck;
                if (rest.startsWith("series_")) ck = rest; // a whole series ("series_<id>")
                else {
                    int u = rest.indexOf('_');
                    if (u <= 0) continue;
                    ck = rest.substring(0, u) + ":" + rest.substring(u + 1);
                }
                st.getJSONObject("adelay").put(ck, new JSONObject().put("ms", ms));
            }
        }
        for (String k : strSet(sp, n + "upnext")) st.getJSONObject("up").put(k, items.has(k) ? new JSONObject().put("i", items.get(k)) : new JSONObject());
        for (String k : strSet(sp, n + "watched")) st.getJSONObject("watched").put(k, new JSONObject());
        for (String k : strSet(sp, n + "rec_block")) st.getJSONObject("recblock").put(k, new JSONObject());
        JSONArray tags = SyncCore.arr(sp.getString(n + "hide_tags", "[]"));
        for (int i = 0; i < tags.length(); i++) st.getJSONObject("hidetag").put(tags.optString(i), new JSONObject());
        for (String type : TYPES) {
            JSONArray f = SyncCore.arr(sp.getString(n + "fav_" + type, "[]"));
            for (int i = 0; i < f.length(); i++) {
                JSONObject it = f.optJSONObject(i);
                if (it == null) continue;
                it.put("type", type);
                st.getJSONObject("fav").put(type + ":" + it.optString("id"), new JSONObject().put("o", i).put("i", it));
            }
            String[][] lists = {{"favcat", "favcat_"}, {"hidecat", "hide_cat_"}, {"showcat", "show_cat_"}};
            for (String[] l : lists) {
                JSONArray a = SyncCore.arr(sp.getString(n + l[1] + type, "[]"));
                for (int i = 0; i < a.length(); i++) st.getJSONObject(l[0]).put(type + ":" + a.optString(i), new JSONObject());
            }
        }
        JSONArray q = SyncCore.arr(sp.getString(n + "queue", "[]"));
        for (int i = 0; i < q.length(); i++) {
            JSONObject it = q.optJSONObject(i);
            if (it != null) st.getJSONObject("queue").put(it.optString("type") + ":" + it.optString("id"), new JSONObject().put("o", i).put("i", it));
        }
        st.put("settings", readSettings(sp));
        return st;
    }

    private static List<String> sortedKeys(final JSONObject m) {
        List<String> ks = SyncCore.keys(m);
        java.util.Collections.sort(ks, (a, b) -> Long.compare(m.optJSONObject(a).optLong("o"), m.optJSONObject(b).optLong("o")));
        return ks;
    }

    private static String typeOf(String k) { int i = k.indexOf(':'); return i < 0 ? "" : k.substring(0, i); }
    private static String idOf(String k) { return k.substring(k.indexOf(':') + 1); }

    /** Writes what differs between next and prev (what readLocal gave). True if anything changed. */
    static boolean writeLocal(Prefs p, JSONObject next, JSONObject prev) throws Exception {
        String n = p.ns();
        SharedPreferences.Editor e = p.raw().edit();
        boolean any = false, filters = false, favs = false;
        List<String> diff = new ArrayList<>();
        for (String m : MAPS) if (!SyncCore.sameMap(m, next.optJSONObject(m), prev.optJSONObject(m))) diff.add(m);
        if (!SyncCore.sameMap("hist", next.optJSONObject("hist"), prev.optJSONObject("hist"))) diff.add("hist");
        if (diff.isEmpty()) return false;
        any = true;
        for (String m : diff) {
            JSONObject nx = next.optJSONObject(m), pv = prev.optJSONObject(m);
            if (nx == null) nx = new JSONObject();
            if (pv == null) pv = new JSONObject();
            switch (m) {
                case "pos":
                    for (String k : SyncCore.keys(pv)) if (!nx.has(k)) e.remove(n + "pos_" + k);
                    for (String k : SyncCore.keys(nx)) {
                        JSONObject x = nx.getJSONObject(k);
                        e.putLong(n + "pos_" + k, x.optLong("p"));
                        if (x.optLong("d") > 0) e.putLong(n + "dur_" + k, x.optLong("d"));
                    }
                    break;
                case "up": e.putStringSet(n + "upnext", new HashSet<>(SyncCore.keys(nx))); break;
                case "watched": e.putStringSet(n + "watched", new HashSet<>(SyncCore.keys(nx))); break;
                case "recblock": e.putStringSet(n + "rec_block", new HashSet<>(SyncCore.keys(nx))); break;
                case "hidetag": e.putString(n + "hide_tags", new JSONArray(SyncCore.keys(nx)).toString()); filters = true; break;
                case "fav":
                    favs = true;
                    for (String type : TYPES) {
                        JSONArray a = new JSONArray();
                        for (String k : sortedKeys(nx)) if (typeOf(k).equals(type)) a.put(nx.getJSONObject(k).opt("i"));
                        e.putString(n + "fav_" + type, a.toString());
                    }
                    break;
                case "favcat": case "hidecat": case "showcat": {
                    String pre = m.equals("favcat") ? "favcat_" : m.equals("hidecat") ? "hide_cat_" : "show_cat_";
                    if (!m.equals("favcat")) filters = true;
                    for (String type : TYPES) {
                        JSONArray a = new JSONArray();
                        for (String k : SyncCore.keys(nx)) if (typeOf(k).equals(type)) a.put(idOf(k));
                        e.putString(n + pre + type, a.toString());
                    }
                    break;
                }
                case "catname":
                    filters = true;
                    for (String k : SyncCore.keys(pv)) if (!nx.has(k)) e.remove(n + "catname_" + typeOf(k) + "_" + idOf(k));
                    for (String k : SyncCore.keys(nx)) e.putString(n + "catname_" + typeOf(k) + "_" + idOf(k), nx.getJSONObject(k).optString("n"));
                    break;
                case "queue": {
                    JSONArray a = new JSONArray();
                    for (String k : sortedKeys(nx)) a.put(nx.getJSONObject(k).opt("i"));
                    e.putString(n + "queue", a.toString());
                    break;
                }
                case "pic":
                    for (String k : SyncCore.keys(pv)) if (!nx.has(k)) e.remove(n + "pic_" + k);
                    for (String k : SyncCore.keys(nx)) e.putInt(n + "pic_" + k, (int) nx.getJSONObject(k).optLong("m"));
                    break;
                case "adelay":
                    for (String k : SyncCore.keys(pv)) if (!nx.has(k)) e.remove(n + "adelay_" + adelayKey(k));
                    for (String k : SyncCore.keys(nx)) e.putInt(n + "adelay_" + adelayKey(k), (int) nx.getJSONObject(k).optLong("ms"));
                    break;
                case "settings":
                    writeSettings(e, nx, pv);
                    break;
                case "hist":
                    for (String kind : new String[]{"live", "vod"}) {
                        JSONArray a = new JSONArray();
                        for (String k : sortedKeys(nx)) if (typeOf(k).equals(Item.LIVE) == kind.equals("live")) a.put(nx.getJSONObject(k).opt("i"));
                        e.putString(n + "hist_" + kind, a.toString());
                    }
                    break;
            }
        }
        quietUntil = System.currentTimeMillis() + 2000;
        e.commit();
        if (favs) Prefs.favsChanged();
        if (filters) Filters.changed();
        return any;
    }

    /** "series_12" → "series_12"; "movie:5" → "movie_5" (Prefs' audio sync keys). */
    private static String adelayKey(String k) {
        return k.startsWith("series_") ? k : typeOf(k) + "_" + idOf(k);
    }
}
