package com.xtreamplayer.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Where the intro and the end credits are in an episode, from TheIntroDB (theintrodb.org) — a
 * free community database used by Jellyfin, Emby and Kodi, looked up by the series' TMDB id,
 * season and episode. Many popular shows are covered; when one isn't, there's simply no
 * Skip Intro button for it.
 */
final class Intros {
    private Intros() {}

    /** Times in ms, -1 where unknown. */
    static final class Segments {
        long introStart = -1, introEnd = -1, creditsStart = -1;

        boolean hasIntro() { return introEnd > 0 && introEnd > introStart; }
    }

    private static final Segments NONE = new Segments();
    private static final Map<String, Segments> CACHE = new HashMap<>();

    static Segments get(String tmdbId, int season, int episode) {
        if (tmdbId == null || season < 0 || episode <= 0) return NONE;
        String k = tmdbId + ":" + season + ":" + episode;
        synchronized (CACHE) {
            Segments s = CACHE.get(k);
            if (s != null) return s;
        }
        Segments s = new Segments();
        try {
            String body = Http.string("https://api.theintrodb.org/v4/media?tmdb_id=" + tmdbId
                    + "&season=" + season + "&episode=" + episode, "YMPlayer/1.0");
            JSONObject o = new JSONObject(body);
            if (!o.has("intro") && o.optJSONObject("data") != null) o = o.optJSONObject("data");
            long[] intro = first(o.opt("intro"));
            if (intro != null) {
                s.introStart = intro[0] < 0 ? 0 : intro[0];
                s.introEnd = intro[1];
            }
            long[] credits = first(o.opt("credits"));
            if (credits != null && credits[0] > 0) s.creditsStart = credits[0];
        } catch (Exception e) {
            return NONE; // not in the database, or offline: no button (asked again next time)
        }
        synchronized (CACHE) { CACHE.put(k, s); }
        return s;
    }

    /** First segment as {start, end} (start null = from the beginning, end null = to the end → -1). */
    private static long[] first(Object seg) {
        JSONObject o = null;
        if (seg instanceof JSONArray && ((JSONArray) seg).length() > 0) o = ((JSONArray) seg).optJSONObject(0);
        else if (seg instanceof JSONObject) o = (JSONObject) seg;
        if (o == null) return null;
        long start = o.isNull("start_ms") ? -1 : o.optLong("start_ms", -1);
        long end = o.isNull("end_ms") ? -1 : o.optLong("end_ms", -1);
        if (start <= 0 && end <= 0) return null; // {0,0}: confirmed there isn't one
        return new long[]{start, end};
    }
}
