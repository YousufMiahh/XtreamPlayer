package com.xtreamplayer.tv;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Library cache: memory first, then a copy on disk, then the provider.
 * The disk copy makes browsing and search instant after the first load, and is
 * refreshed in the background on a schedule (see {@link #refreshIfDue}).
 */
public final class Repo {
    private Repo() {}

    private static final Map<String, List<Item>> LISTS = new HashMap<>();
    private static final Map<String, List<Item>> CATS = new HashMap<>();
    private static final Map<String, Object> LOCKS = new HashMap<>();
    private static String account = "";
    private static File dir;
    private static volatile boolean refreshing;

    public static void init(Context c) {
        if (dir == null) dir = c.getApplicationContext().getFilesDir();
    }

    private static synchronized void check(XtreamApi api) {
        String k = api.base + "|" + api.user;
        if (!k.equals(account)) {
            LISTS.clear();
            CATS.clear();
            account = k;
        }
    }

    /** Forget the in-memory copy (the disk copy stays until the next refresh). */
    public static synchronized void clear() {
        LISTS.clear();
        CATS.clear();
    }

    private static synchronized Object lock(String type) {
        Object o = LOCKS.get(type);
        if (o == null) {
            o = new Object();
            LOCKS.put(type, o);
        }
        return o;
    }

    private static synchronized List<Item> get(Map<String, List<Item>> m, String k) { return m.get(k); }

    private static synchronized void put(Map<String, List<Item>> m, String k, List<Item> v) { m.put(k, v); }

    // ------------------------------------------------------------ public API

    public static List<Item> categories(XtreamApi api, String type) throws IOException {
        check(api);
        List<Item> c = get(CATS, type);
        if (c != null) return c;
        synchronized (lock("cat" + type)) {
            c = get(CATS, type);
            if (c != null) return c;
            c = readFile(file(api, type, "cats"));
            if (c == null) {
                c = api.categories(type);
                writeFile(file(api, type, "cats"), c);
            }
            put(CATS, type, c);
            return c;
        }
    }

    /** @param cat null for everything of that type. */
    public static List<Item> streams(XtreamApi api, String type, String cat) throws IOException {
        check(api);
        String key = type + "|" + (cat == null ? "*" : cat);
        List<Item> l = get(LISTS, key);
        if (l != null) return l;
        List<Item> all = cat == null ? null : get(LISTS, type + "|*");
        if (all == null && (cat == null || file(api, type, "all").exists())) all = all(api, type);
        if (cat == null) return all;
        if (all != null) {
            l = new ArrayList<>();
            for (Item it : all) if (cat.equals(it.categoryId)) l.add(it);
        } else {
            l = api.streams(type, cat); // first ever visit and no full list yet: just this category
        }
        put(LISTS, key, l);
        return l;
    }

    /** Whole library of one type: memory → disk → provider. Only one download per type at a time. */
    private static List<Item> all(XtreamApi api, String type) throws IOException {
        String key = type + "|*";
        synchronized (lock(type)) {
            List<Item> l = get(LISTS, key);
            if (l != null) return l;
            l = readFile(file(api, type, "all"));
            if (l == null) {
                l = api.streams(type, null);
                writeFile(file(api, type, "all"), l);
            }
            put(LISTS, key, l);
            return l;
        }
    }

    public static List<Item> cached(String type, String cat) {
        return get(LISTS, type + "|" + (cat == null ? "*" : cat));
    }

    public static List<Item> recentlyAdded(XtreamApi api, String type, int n) throws IOException {
        List<Item> all = new ArrayList<>(streams(api, type, null));
        Collections.sort(all, new Comparator<Item>() {
            public int compare(Item a, Item b) { return Long.compare(b.added, a.added); }
        });
        return new ArrayList<>(all.subList(0, Math.min(n, all.size())));
    }

    public static boolean isRefreshing() { return refreshing || setupRunning; }

    // ------------------------------------------------------------ first-time setup

    /** Progress callbacks for the setup screen (always delivered on the main thread). */
    public interface SetupListener {
        void started(String type);
        void finished(String type, int count, Exception error);
        void allDone(boolean ok);
    }

    private static volatile boolean setupRunning;
    private static volatile SetupListener setupListener;
    /** Called on the main thread whenever a section's list has been replaced (e.g. Home reloads its rows). */
    public static volatile Runnable onUpdated;

    public static void setSetupListener(SetupListener l) { setupListener = l; }

    public static boolean isSetupRunning() { return setupRunning; }

    /**
     * Downloads the given sections: Live TV first (so it's ready soonest), then Movies and Series
     * at the same time (most of the wait is the provider building each list, so overlapping
     * them saves time). Keeps going if the setup screen is left.
     */
    public static synchronized void startSetup(final XtreamApi api, final Prefs prefs, final List<String> types) {
        if (setupRunning) return;
        setupRunning = true;
        new Thread(new Runnable() {
            public void run() {
                boolean ok = true;
                if (types.contains(Item.LIVE)) ok = setupStep(api, Item.LIVE);
                List<Thread> parallel = new ArrayList<>();
                final boolean[] results = {true, true};
                String[] rest = {Item.MOVIE, Item.SERIES};
                for (int i = 0; i < rest.length; i++) {
                    if (!types.contains(rest[i])) continue;
                    final int k = i;
                    final String t = rest[i];
                    Thread th = new Thread(new Runnable() {
                        public void run() { results[k] = setupStep(api, t); }
                    }, "setup-" + t);
                    th.start();
                    parallel.add(th);
                }
                for (Thread th : parallel) {
                    try {
                        th.join();
                    } catch (InterruptedException ignored) {
                    }
                }
                ok = ok && results[0] && results[1];
                if (ok) prefs.setLastRefresh(System.currentTimeMillis());
                setupRunning = false;
                final boolean fok = ok;
                Async.MAIN.post(new Runnable() {
                    public void run() {
                        SetupListener l = setupListener;
                        if (l != null) l.allDone(fok);
                    }
                });
            }
        }, "library-setup").start();
    }

    private static boolean setupStep(XtreamApi api, final String type) {
        Async.MAIN.post(new Runnable() {
            public void run() {
                SetupListener l = setupListener;
                if (l != null) l.started(type);
            }
        });
        int n = -1;
        Exception err = null;
        try {
            n = downloadType(api, type);
        } catch (Exception e) {
            err = e;
        } catch (OutOfMemoryError oom) {
            err = new Exception("Not enough memory for this list");
        }
        final int fn = n;
        final Exception fe = err;
        Async.MAIN.post(new Runnable() {
            public void run() {
                SetupListener l = setupListener;
                if (l != null) l.finished(type, fn, fe);
            }
        });
        return err == null;
    }

    /** True if a saved copy of the movie and series libraries exists on disk. */
    public static boolean hasLibrary(XtreamApi api) {
        return dir != null && file(api, Item.LIVE, "all").exists()
                && file(api, Item.MOVIE, "all").exists() && file(api, Item.SERIES, "all").exists();
    }

    /** True if the saved library is missing or older than the user's refresh setting. */
    public static boolean isDue(Prefs prefs) {
        int h = prefs.refreshHours();
        long last = prefs.lastRefresh();
        if (last == 0) return true;
        if (h < 0) return false;
        if (h == 0) return true;
        return System.currentTimeMillis() - last > h * 3600_000L;
    }

    /** Re-downloads categories and full lists for Live, Movies and Series. Call off the UI thread. */
    public static void refreshAll(XtreamApi api, Prefs prefs) throws IOException {
        check(api);
        refreshing = true;
        int oldPriority = android.os.Process.getThreadPriority(android.os.Process.myTid());
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); // never compete with the screen
        try {
            String[] types = {Item.LIVE, Item.MOVIE, Item.SERIES};
            for (String t : types) downloadType(api, t);
            prefs.setLastRefresh(System.currentTimeMillis());
        } finally {
            android.os.Process.setThreadPriority(oldPriority);
            refreshing = false;
        }
    }

    /**
     * Downloads one section (categories + everything in it), saves it to disk and swaps it into
     * memory. Readers keep using the old copy until the new one is ready, so nothing waits on this.
     * @return number of items
     */
    public static int downloadType(XtreamApi api, String t) throws IOException {
        check(api);
        List<Item> cats = api.categories(t);
        List<Item> all = api.streams(t, null);
        writeFile(file(api, t, "cats"), cats);
        writeFile(file(api, t, "all"), all);
        synchronized (Repo.class) {
            Iterator<String> it = LISTS.keySet().iterator();
            while (it.hasNext()) if (it.next().startsWith(t + "|")) it.remove();
            LISTS.put(t + "|*", all);
            CATS.put(t, cats);
        }
        Async.MAIN.post(new Runnable() {
            public void run() {
                Runnable r = onUpdated;
                if (r != null) r.run();
            }
        });
        return all.size();
    }

    /** Mark a full download as done (used by the setup screen). */
    public static void markRefreshed(Prefs prefs) {
        prefs.setLastRefresh(System.currentTimeMillis());
    }

    /** Starts a background refresh if one is due. */
    public static void refreshIfDue(final XtreamApi api, final Prefs prefs, final Runnable whenDone) {
        if (refreshing || !isDue(prefs)) return;
        Async.run(new Async.Work<Boolean>() {
            public Boolean run() throws Exception {
                refreshAll(api, prefs);
                return true;
            }
        }, new Async.Done<Boolean>() {
            public void done(Boolean ok, Exception e) {
                if (whenDone != null && ok != null) whenDone.run();
            }
        });
    }

    /** Warm the memory cache from disk/provider in the background (makes search instant). */
    public static void preload(final XtreamApi api) {
        // One section at a time, at low priority: loading all three at once strained the
        // Fire Stick's memory and made the whole app stall.
        Thread t = new Thread(new Runnable() {
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                String[] types = {Item.LIVE, Item.MOVIE, Item.SERIES};
                for (String type : types) {
                    if (refreshing) return;
                    try {
                        if (dir != null && file(api, type, "all").exists()) streams(api, type, null);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "library-preload");
        t.start();
    }

    public static void deleteFiles(String server, String user) {
        if (dir == null) return;
        String prefix = "lib_" + Integer.toHexString((XtreamApi.normalize(server) + "|" + user).hashCode()) + "_";
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) if (f.getName().startsWith(prefix)) f.delete();
    }

    // ------------------------------------------------------------ disk

    private static File file(XtreamApi api, String type, String kind) {
        String h = Integer.toHexString((api.base + "|" + api.user).hashCode());
        return new File(dir, "lib_" + h + "_" + type + "_" + kind + ".json");
    }

    private static void writeFile(File f, List<Item> list) {
        if (dir == null) return;
        File tmp = new File(f.getPath() + ".tmp");
        try (JsonWriter w = new JsonWriter(new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(tmp), 65536), "UTF-8"))) {
            w.beginArray();
            for (Item it : list) {
                w.beginObject();
                w.name("t").value(it.type);
                w.name("i").value(it.id);
                w.name("n").value(it.name);
                if (it.icon != null) w.name("c").value(it.icon);
                if (it.ext != null) w.name("e").value(it.ext);
                if (it.num != null) w.name("u").value(it.num);
                if (it.plot != null) w.name("p").value(it.plot);
                if (it.info != null) w.name("f").value(it.info);
                if (it.categoryId != null) w.name("g").value(it.categoryId);
                if (it.added != 0) w.name("a").value(it.added);
                w.endObject();
            }
            w.endArray();
        } catch (Exception e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    private static List<Item> readFile(File f) {
        if (dir == null || !f.exists()) return null;
        List<Item> out = new ArrayList<>();
        try (JsonReader r = new JsonReader(new InputStreamReader(new BufferedInputStream(new FileInputStream(f), 65536), "UTF-8"))) {
            r.beginArray();
            while (r.hasNext()) {
                Item it = new Item();
                r.beginObject();
                while (r.hasNext()) {
                    String k = r.nextName();
                    if (r.peek() == JsonToken.NULL) {
                        r.nextNull();
                        continue;
                    }
                    switch (k) {
                        case "t": it.type = r.nextString(); break;
                        case "i": it.id = r.nextString(); break;
                        case "n": it.name = r.nextString(); break;
                        case "c": it.icon = r.nextString(); break;
                        case "e": it.ext = r.nextString(); break;
                        case "u": it.num = r.nextString(); break;
                        case "p": it.plot = r.nextString(); break;
                        case "f": it.info = r.nextString(); break;
                        case "g": it.categoryId = r.nextString(); break;
                        case "a": it.added = r.nextLong(); break;
                        default: r.skipValue();
                    }
                }
                r.endObject();
                if (it.id != null) out.add(it);
            }
            r.endArray();
            return out;
        } catch (Exception e) {
            f.delete();
            return null;
        }
    }
}
