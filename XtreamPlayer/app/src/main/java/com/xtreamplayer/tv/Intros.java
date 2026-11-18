package com.xtreamplayer.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Where the recap, intro and end credits are in an episode, from TheIntroDB (theintrodb.org), a
 * free community database used by Jellyfin, Emby and Kodi, looked up by the series' TMDB id,
 * season and episode (and the episode's length, so it picks the right cut). When an episode
 * isn't covered there's simply no Skip button for it.
 */
final class Intros {
    private Intros() {}

    /** Times in ms, -1 where unknown. */
    static final class Segments {
        long introStart = -1, introEnd = -1, recapStart = -1, recapEnd = -1, creditsStart = -1;
        /** What happened, for Settings / the player's info line ("found intro 0:48–1:30", "not in the database"…). */
        String status = "";

        boolean hasIntro() { return introEnd > 0 && introEnd > introStart; }
        boolean hasRecap() { return recapEnd > 0 && recapEnd > recapStart; }
    }

    private static final Map<String, Segments> CACHE = new HashMap<>();
    /** The last lookup's result, shown in the player's info so a missing button can be explained. */
    static volatile String lastStatus = "";

    static Segments none(String why) {
        Segments s = new Segments();
        s.status = why;
        lastStatus = why;
        return s;
    }

    /** @param durationMs the episode's length if known (0 = unknown) */
    static Segments get(String tmdbId, int season, int episode, long durationMs) {
        if (tmdbId == null) return none("Couldn't find this show on TMDB");
        if (season < 0 || episode <= 0) return none("No season / episode number for this episode");
        String k = tmdbId + ":" + season + ":" + episode;
        synchronized (CACHE) {
            Segments s = CACHE.get(k);
            if (s != null) { lastStatus = s.status; return s; }
        }
        String url = "https://api.theintrodb.org/v4/media?tmdb_id=" + tmdbId + "&season=" + season + "&episode=" + episode
                + (durationMs > 60_000 ? "&duration_ms=" + durationMs : "");
        Segments s;
        try {
            s = parse(new JSONObject(Http.string(url, "Mozilla/5.0 (YMPlayer)")));
        } catch (java.io.IOException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("404")) {
                s = none("Not in TheIntroDB (TMDB " + tmdbId + " S" + season + "E" + episode + ")");
            } else {
                return none("Couldn't reach TheIntroDB: " + m); // offline: asked again next time
            }
        } catch (Exception e) {
            return none("TheIntroDB answered in an unexpected way");
        }
        if (s.status.isEmpty()) {
            StringBuilder b = new StringBuilder("TMDB " + tmdbId + " S" + season + "E" + episode + ": ");
            if (s.hasRecap()) b.append("recap ").append(clock(s.recapStart)).append("–").append(clock(s.recapEnd)).append("  ");
            if (s.hasIntro()) b.append("intro ").append(clock(s.introStart)).append("–").append(clock(s.introEnd)).append("  ");
            if (s.creditsStart > 0) b.append("credits ").append(clock(s.creditsStart));
            if (!s.hasIntro() && !s.hasRecap() && s.creditsStart <= 0) b.append("no times yet");
            s.status = b.toString().trim();
        }
        lastStatus = s.status;
        synchronized (CACHE) { CACHE.put(k, s); }
        return s;
    }

    static Segments parse(JSONObject o) {
        if (!o.has("intro") && o.optJSONObject("data") != null) o = o.optJSONObject("data");
        Segments s = new Segments();
        long[] intro = longest(o.opt("intro"));
        if (intro != null) { s.introStart = intro[0]; s.introEnd = intro[1]; }
        long[] recap = longest(o.opt("recap"));
        if (recap != null) { s.recapStart = recap[0]; s.recapEnd = recap[1]; }
        long[] credits = longest(o.opt("credits"));
        if (credits != null && credits[0] > 0) s.creditsStart = credits[0];
        return s;
    }

    /**
     * The segment as {start, end} in ms: a missing start means from the beginning (0); a missing
     * end, to the end of the episode (-1). With several entries, the longest one. Null if none.
     */
    private static long[] longest(Object seg) {
        JSONArray a;
        if (seg instanceof JSONArray) a = (JSONArray) seg;
        else if (seg instanceof JSONObject) { a = new JSONArray(); a.put(seg); }
        else return null;
        long[] best = null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            long start = num(o, "start_ms", "startMs");
            long end = num(o, "end_ms", "endMs");
            if (start <= 0 && end <= 0) continue; // {0,0} / {null,null}: confirmed there isn't one
            if (start < 0) start = 0;
            if (best == null || (end > 0 && best[1] > 0 && end - start > best[1] - best[0])) best = new long[]{start, end};
        }
        return best;
    }

    private static long num(JSONObject o, String a, String b) {
        String k = o.has(a) ? a : o.has(b) ? b : null;
        if (k == null || o.isNull(k)) return -1;
        return (long) o.optDouble(k, -1);
    }

    static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}
