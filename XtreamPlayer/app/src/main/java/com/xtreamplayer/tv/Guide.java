package com.xtreamplayer.tv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TV guide listings, fetched per channel only for the channels you can see in the guide
 * (much lighter than downloading the provider's whole guide file), kept for 30 minutes.
 */
public final class Guide {
    private Guide() {}

    private static final long KEEP_MS = 30 * 60_000L;
    private static final int AHEAD = 24; // programmes per channel ≈ the rest of today and tonight

    private static class Entry {
        List<XtreamApi.Programme> list;
        long fetched;
    }

    private static final Map<String, Entry> CACHE = new HashMap<>();
    private static final Set<String> LOADING = new HashSet<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);

    /** Listings for a channel, or null if not loaded yet. */
    public static synchronized List<XtreamApi.Programme> get(String streamId) {
        Entry e = CACHE.get(streamId);
        return e == null ? null : e.list;
    }

    /** Loads listings for these channels (skipping fresh ones), calling done on the main thread as each arrives. */
    public static void load(final XtreamApi api, List<Item> channels, final Runnable done) {
        long now = System.currentTimeMillis();
        List<Item> todo = new ArrayList<>();
        synchronized (Guide.class) {
            for (Item it : channels) {
                Entry e = CACHE.get(it.id);
                if ((e != null && now - e.fetched < KEEP_MS) || LOADING.contains(it.id)) continue;
                LOADING.add(it.id);
                todo.add(it);
            }
        }
        for (final Item it : todo) {
            POOL.execute(new Runnable() {
                public void run() {
                    List<XtreamApi.Programme> l = api.epg(it.id, AHEAD);
                    synchronized (Guide.class) {
                        Entry e = new Entry();
                        e.list = l;
                        e.fetched = System.currentTimeMillis();
                        CACHE.put(it.id, e);
                        LOADING.remove(it.id);
                    }
                    if (done != null) Async.MAIN.post(done);
                }
            });
        }
    }
}
