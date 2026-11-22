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

    private static final java.util.Map<String, String> IMDB = new java.util.HashMap<>();

    /** A series' IMDb id ("tt0903747") from its TMDB id, for the skip-intro databases that use it; null if none. */
    static String imdbId(String tvId) {
        synchronized (IMDB) { if (IMDB.containsKey(tvId)) return IMDB.get(tvId); }
        String id = null;
        try {
            String v = get("/tv/" + tvId + "/external_ids").optString("imdb_id", "");
            if (v.startsWith("tt")) id = v;
        } catch (Exception e) {
            return null; // not cached: asked again next time
        }
        synchronized (IMDB) { IMDB.put(tvId, id); }
        return id;
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

    /**
     * The title's logo (the transparent artwork Netflix and Apple TV show instead of the name), as an
     * image URL, or null. English first, then one with no words; remembered per title.
     */
    static String logo(Context c, String kind, String id) { return art(c, kind, id)[0]; }

    /** {logo, backdrop} image URLs (either may be null), from details(). */
    static String[] art(Context c, String kind, String id) {
        Details d = details(c, kind, id);
        return d == null ? new String[]{null, null} : new String[]{d.logo, d.backdrop};
    }

    /** What Home shows under a card you're on, Netflix-style, plus its logo and wide picture. */
    static final class Details {
        String logo, backdrop, overview, year, runtime, cert;
        int seasons;
        final List<String> genres = new ArrayList<>();

        /** "Series · Thriller · 2026 · 2 Seasons" (the age rating is drawn separately). */
        String line(boolean series) {
            List<String> p = new ArrayList<>();
            p.add(series ? "Series" : "Film");
            if (!genres.isEmpty()) p.add(genres.get(0));
            if (year != null) p.add(year);
            if (series && seasons > 0) p.add(seasons + (seasons == 1 ? " Season" : " Seasons"));
            if (!series && runtime != null) p.add(runtime);
            StringBuilder b = new StringBuilder();
            for (String x : p) b.append(b.length() == 0 ? "" : "  ·  ").append(x);
            return b.toString();
        }
    }

    private static final java.util.Map<String, Details> DETAILS = new java.util.HashMap<>();

    /**
     * One request for everything: genres, year, seasons or running time, the UK age rating, the
     * summary, and the logo and wide picture. Remembered (in memory, and on the device for a week).
     */
    static Details details(Context c, String kind, String id) {
        if (id == null || id.isEmpty()) return null;
        String ck = "details:" + kind + ":" + id;
        synchronized (DETAILS) { if (DETAILS.containsKey(ck)) return DETAILS.get(ck); }
        SharedPreferences sp = cache(c);
        String saved = sp.getString(ck, null);
        JSONObject o = null;
        try {
            if (saved != null) {
                JSONObject w = new JSONObject(saved);
                if (System.currentTimeMillis() - w.optLong("at") < 7L * 86400000L) o = w.getJSONObject("d");
            }
            if (o == null) {
                boolean tv = TV.equals(kind);
                o = get("/" + kind + "/" + id + "?append_to_response=images," + (tv ? "content_ratings" : "release_dates")
                        + "&include_image_language=en,null");
                JSONObject slim = new JSONObject(); // keep only what's used
                for (String k : new String[]{"genres", "first_air_date", "release_date", "number_of_seasons", "runtime", "overview"}) {
                    if (o.has(k)) slim.put(k, o.get(k));
                }
                JSONObject img = o.optJSONObject("images");
                slim.put("logo", img == null ? "" : String.valueOf(pickLogo(img.optJSONArray("logos"))));
                slim.put("backdrop", img == null ? "" : String.valueOf(pickBackdrop(img.optJSONArray("backdrops"))));
                slim.put("cert", ukRating(o, tv));
                o = slim;
                sp.edit().putString(ck, new JSONObject().put("at", System.currentTimeMillis()).put("d", o).toString()).apply();
            }
        } catch (Exception e) {
            return null; // offline: try again next time
        }
        Details d = new Details();
        d.logo = nn(o.optString("logo", ""));
        d.backdrop = nn(o.optString("backdrop", ""));
        d.overview = nn(o.optString("overview", ""));
        d.cert = nn(o.optString("cert", ""));
        String date = o.optString("first_air_date", o.optString("release_date", ""));
        d.year = date.length() >= 4 ? date.substring(0, 4) : null;
        d.seasons = o.optInt("number_of_seasons", 0);
        int mins = o.optInt("runtime", 0);
        if (mins > 0) d.runtime = mins >= 60 ? (mins / 60) + "h " + (mins % 60) + "m" : mins + "m";
        JSONArray g = o.optJSONArray("genres");
        if (g != null) for (int i = 0; i < g.length(); i++) {
            String n = g.optJSONObject(i) == null ? "" : g.optJSONObject(i).optString("name", "");
            if (!n.isEmpty()) d.genres.add(n.replace("Action & Adventure", "Action").replace("Sci-Fi & Fantasy", "Sci-Fi"));
        }
        synchronized (DETAILS) { DETAILS.put(ck, d); }
        return d;
    }

    private static String nn(String s) { return s == null || s.isEmpty() || "null".equals(s) ? null : s; }

    /** The UK (BBFC) rating: "U", "PG", "12", "12A", "15", "18", or "" if TMDB doesn't have one. */
    static String ukRating(JSONObject o, boolean tv) {
        try {
            if (tv) {
                JSONArray r = o.getJSONObject("content_ratings").getJSONArray("results");
                for (int i = 0; i < r.length(); i++) {
                    JSONObject x = r.getJSONObject(i);
                    if ("GB".equals(x.optString("iso_3166_1"))) return x.optString("rating", "");
                }
            } else {
                JSONArray r = o.getJSONObject("release_dates").getJSONArray("results");
                for (int i = 0; i < r.length(); i++) {
                    JSONObject x = r.getJSONObject(i);
                    if (!"GB".equals(x.optString("iso_3166_1"))) continue;
                    JSONArray rd = x.getJSONArray("release_dates");
                    for (int k = 0; k < rd.length(); k++) {
                        String cert = rd.getJSONObject(k).optString("certification", "");
                        if (!cert.isEmpty()) return cert;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** A backdrop without words first (the logo goes on top), then an English one; most liked. */
    static String pickBackdrop(JSONArray list) {
        if (list == null) return null;
        JSONObject best = null;
        int bestRank = 9;
        double bestVote = -1;
        for (int i = 0; i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            String lang = b.isNull("iso_639_1") ? "" : b.optString("iso_639_1", "");
            int rank = lang.isEmpty() ? 0 : "en".equals(lang) ? 1 : 9;
            if (rank == 9) continue;
            double vote = b.optDouble("vote_average", 0);
            if (rank < bestRank || (rank == bestRank && vote > bestVote)) {
                best = b;
                bestRank = rank;
                bestVote = vote;
            }
        }
        return best == null ? null : "https://image.tmdb.org/t/p/w780" + best.optString("file_path");
    }

    /** Best of TMDB's logos: English (most liked) before language-free; SVGs asked for as PNG. */
    static String pickLogo(JSONArray logos) {
        if (logos == null) return null;
        JSONObject best = null;
        int bestRank = 9;
        double bestVote = -1;
        for (int i = 0; i < logos.length(); i++) {
            JSONObject l = logos.optJSONObject(i);
            if (l == null || l.optString("file_path", "").isEmpty()) continue;
            String lang = l.isNull("iso_639_1") ? "" : l.optString("iso_639_1", "");
            int rank = "en".equals(lang) ? 0 : lang.isEmpty() ? 1 : 9;
            if (rank == 9) continue;
            double vote = l.optDouble("vote_average", 0);
            if (rank < bestRank || (rank == bestRank && vote > bestVote)) {
                best = l;
                bestRank = rank;
                bestVote = vote;
            }
        }
        if (best == null) return null;
        return "https://image.tmdb.org/t/p/w500" + best.optString("file_path").replaceFirst("(?i)\\.svg$", ".png");
    }
}
