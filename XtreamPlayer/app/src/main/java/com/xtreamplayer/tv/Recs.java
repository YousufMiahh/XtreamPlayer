package com.xtreamplayer.tv;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Because you watched …" rows for Home: TMDB's recommendations for the films and series you've
 * watched most recently, showing only the ones your provider actually has (and not ones you've
 * already watched). If TMDB has nothing that matches, a simple "Recommended for You" row picks
 * series in the genres you watch.
 */
final class Recs {
    private Recs() {}

    static final class Row {
        final String title;
        final List<Item> items;

        Row(String title, List<Item> items) {
            this.title = title;
            this.items = items;
        }
    }

    private static final long KEEP_MS = 6 * 3600_000L;
    private static List<Row> cached;
    private static String cachedFor;
    private static long cachedAt;

    /** Background thread. */
    static List<Row> rows(Context c, XtreamApi api, Prefs prefs) throws Exception {
        // What you've been watching: newest first, one per film / series
        Map<String, Item> sources = new LinkedHashMap<>();
        for (Item h : prefs.history(false)) {
            if (sources.size() >= 3) break;
            if (Item.MOVIE.equals(h.type)) sources.put(h.key(), h);
            else if (Item.EPISODE.equals(h.type) && h.parentId != null) {
                Item s = new Item(Item.SERIES, h.parentId, h.parentName == null ? "" : h.parentName);
                s.icon = h.parentIcon;
                s.tmdb = h.tmdb;
                if (!sources.containsKey(s.key())) sources.put(s.key(), s);
            }
        }
        String sig = prefs.ns() + sources.keySet();
        synchronized (Recs.class) {
            if (cached != null && sig.equals(cachedFor) && System.currentTimeMillis() - cachedAt < KEEP_MS) return cached;
        }
        List<Row> out = new ArrayList<>();
        if (sources.isEmpty()) return out;

        List<Item> movies = library(api, prefs, Item.MOVIE), series = library(api, prefs, Item.SERIES);
        Set<String> shown = new HashSet<>();
        for (Item src : sources.values()) {
            boolean tv = Item.SERIES.equals(src.type);
            String id = Tmdb.findId(c, tv ? Tmdb.TV : Tmdb.MOVIE, src.name, src.tmdb);
            if (id == null) continue;
            List<Item> found = Top10.match(Tmdb.recommendations(tv ? Tmdb.TV : Tmdb.MOVIE, id), tv ? series : movies);
            List<Item> keep = new ArrayList<>();
            for (Item it : found) {
                if (it.key().equals(src.key()) || prefs.isWatched(it) || !shown.add(it.key())) continue;
                keep.add(it);
                if (keep.size() >= 20) break;
            }
            String name = Tmdb.cleanName(src.name)[0];
            if (keep.size() >= 3) out.add(new Row("Because you watched " + (name.isEmpty() ? src.name : name), keep));
        }
        if (out.isEmpty()) {
            List<Item> byGenre = byGenre(prefs, series, sources.values());
            if (byGenre.size() >= 3) out.add(new Row("Recommended for You", byGenre));
        }
        synchronized (Recs.class) {
            cached = out;
            cachedFor = sig;
            cachedAt = System.currentTimeMillis();
        }
        return out;
    }

    private static List<Item> library(XtreamApi api, Prefs prefs, String type) throws Exception {
        List<Item> l = Repo.cached(type, null);
        if (l == null && Repo.hasLibrary(api)) l = Repo.streams(api, type, null);
        return l == null ? new ArrayList<Item>() : Filters.items(api, prefs, type, l);
    }

    /** Fallback: series sharing a genre with what you watch, best rated first. */
    private static List<Item> byGenre(Prefs prefs, List<Item> series, java.util.Collection<Item> watched) {
        Set<String> want = new HashSet<>(), seen = new HashSet<>();
        Map<String, Item> byId = new java.util.HashMap<>();
        for (Item s : series) byId.put(s.id, s);
        for (Item w : watched) {
            seen.add(w.key());
            Item full = byId.get(w.id);
            if (full != null && full.info != null) for (String g : genres(full.info)) want.add(g);
        }
        List<Item> out = new ArrayList<>();
        if (want.isEmpty()) return out;
        for (Item s : series) {
            if (seen.contains(s.key()) || s.info == null || prefs.isWatched(s)) continue;
            for (String g : genres(s.info)) if (want.contains(g)) { out.add(s); break; }
        }
        java.util.Collections.sort(out, new java.util.Comparator<Item>() {
            public int compare(Item a, Item b) { return Float.compare(rating(b.info), rating(a.info)); }
        });
        return out.size() > 20 ? new ArrayList<>(out.subList(0, 20)) : out;
    }

    private static List<String> genres(String info) {
        List<String> g = new ArrayList<>();
        for (String part : info.split("·|,|/|&")) {
            String t = part.trim().toLowerCase();
            if (!t.isEmpty() && !t.startsWith("★") && t.length() < 30) g.add(t);
        }
        return g;
    }

    private static float rating(String info) {
        if (info == null) return 0;
        int i = info.indexOf('★');
        if (i < 0) return 0;
        try {
            return Float.parseFloat(info.substring(i + 1).trim().split("\\s")[0]);
        } catch (Exception e) {
            return 0;
        }
    }
}
