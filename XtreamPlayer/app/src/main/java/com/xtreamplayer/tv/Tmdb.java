package com.xtreamplayer.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Movie Database (themoviedb.org): finds a film's or series' TMDB id from its name, and
 * fetches "people who liked this also liked" recommendations. Used for Skip Intro (the intro
 * database is keyed by TMDB id) and for "Because you watched…" on Home.
 */
final class Tmdb {
    private Tmdb() {}

    private static final String KEY = "ec34788e4bc515bbf0c395f7f3441b78";
    private static final String BASE = "https://api.themoviedb.org/3";
    private static final String UA = "YMPlayer/1.0";
    private static final Pattern YEAR = Pattern.compile("[(\\[]((?:19|20)\\d{2})[)\\]]|\\S\\s+((?:19|20)\\d{2})\\s*$");
    /** Set when a lookup on this thread failed because TMDB couldn't be reached (not "not found"). */
    static final ThreadLocal<Boolean> FAILED = new ThreadLocal<>();

    static final String MOVIE = "movie", TV = "tv";

    private static SharedPreferences cache(Context c) { return c.getSharedPreferences("tmdb", Context.MODE_PRIVATE); }

    private static JSONObject get(String path) throws Exception {
        String url = BASE + path + (path.contains("?") ? "&" : "?") + "api_key=" + KEY + "&language=en-GB";
        return new JSONObject(Http.string(url, UA));
    }

    /**
     * "EN - Breaking Bad (2008) [4K]" → name "Breaking Bad", year "2008". Only a provider's tag is
     * taken off the front ("EN - ", "UK: ", "NF | "), never part of a real title ("CSI: Miami",
     * "24: Legacy", "Thor: Ragnarok"), and a title that is a year ("1917") stays.
     * [2] is the name with an unbracketed trailing number kept ("Blade Runner 2049").
     */
    static String[] cleanName(String raw) {
        String n = raw == null ? "" : raw.trim();
        n = n.replaceFirst("^[^|]{1,8}\\|\\s*", "");                                         // "NF | ", "EN| "
        n = n.replaceFirst("^(?:[A-Z]{2}|(?i:ENG|AMZ|DSNP|ATVP|VOD|MULTI|4K|UHD|FHD|HD))\\s*[:\\-]\\s+", ""); // "EN - ", "UK: "
        String year = null;
        Matcher m = YEAR.matcher(n);
        if (m.find()) year = m.group(1) != null ? m.group(1) : m.group(2);
        n = n.replaceAll("\\(.*?\\)|\\[.*?\\]", " ");                                    // "(2008)", "[Multi-Sub]"
        n = n.replaceFirst("(?i)\\s+[-:]?\\s*S\\d{1,2}\\s*E\\d{1,3}\\b.*$", "");          // " - S01E01 …"
        n = n.replaceFirst("\\s+(?:4K|UHD|FHD|HD|SD|MULTI|SUB|DUB|HEVC|H265)\\b.*$", "");    // " 4K", " MULTI SUB"
        n = n.replaceAll("\\s+", " ").trim();
        String withNumber = n;
        if (year != null) n = n.replaceFirst("\\s*" + year + "\\s*$", "").trim();
        if (n.isEmpty()) { n = withNumber.isEmpty() ? (raw == null ? "" : raw.trim()) : withNumber; year = null; }
        return new String[]{n, year, withNumber};
    }

    /**
     * TMDB id for a film or series. Uses the provider's id when it gives one; otherwise looks the
     * name up (remembered, so each title is only searched once). Null if it can't be found.
     */
    static String findId(Context c, String kind, String name, String known) {
        if (known != null && known.matches("\\d+") && !"0".equals(known)) return known;
        String[] cn = cleanName(name);
        if (cn[0].isEmpty()) return null;
        String ck = kind + ":" + cn[0].toLowerCase() + ":" + (cn[1] == null ? "" : cn[1]);
        SharedPreferences sp = cache(c);
        if (sp.contains(ck)) {
            String v = sp.getString(ck, "");
            return v.isEmpty() ? null : v;
        }
        String id = null;
        try {
            String q = "/search/" + kind + "?query=" + Uri.encode(cn[0]);
            if (cn[1] != null) q += (TV.equals(kind) ? "&first_air_date_year=" : "&year=") + cn[1];
            JSONArray r = get(q).optJSONArray("results");
            if ((r == null || r.length() == 0) && cn[1] != null && !cn[2].equals(cn[0])) { // the number is part of the title
                r = get("/search/" + kind + "?query=" + Uri.encode(cn[2])).optJSONArray("results");
            }
            if ((r == null || r.length() == 0) && cn[1] != null) { // wrong year in the name: try without
                r = get("/search/" + kind + "?query=" + Uri.encode(cn[0])).optJSONArray("results");
            }
            if (r != null && r.length() > 0) id = String.valueOf(r.getJSONObject(0).optLong("id"));
        } catch (Exception e) {
            FAILED.set(true);
            return null; // offline: try again next time
        }
        sp.edit().putString(ck, id == null ? "" : id).apply();
        return id;
    }

    /** Titles TMDB recommends for people who liked this one (both the English and original titles). */
    static List<String> recommendations(String kind, String id) {
        List<String> out = new ArrayList<>();
        if (id == null) return out;
        try {
            for (int page = 1; page <= 2; page++) {
                JSONArray r = get("/" + kind + "/" + id + "/recommendations?page=" + page).optJSONArray("results");
                if (r == null) break;
                for (int i = 0; i < r.length(); i++) {
                    JSONObject o = r.getJSONObject(i);
                    String t = o.optString(TV.equals(kind) ? "name" : "title", "");
                    String ot = o.optString(TV.equals(kind) ? "original_name" : "original_title", "");
                    if (!t.isEmpty()) out.add(t);
                    if (!ot.isEmpty() && !ot.equals(t)) out.add(ot);
                }
            }
        } catch (Exception e) {
            if (out.isEmpty()) FAILED.set(true);
        }
        return out;
    }
}
