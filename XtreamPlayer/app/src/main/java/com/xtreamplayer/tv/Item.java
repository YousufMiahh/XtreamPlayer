package com.xtreamplayer.tv;

import org.json.JSONObject;

/** One thing in a list: a category, channel, movie, series, season or episode. */
public class Item {
    public static final String LIVE = "live";
    public static final String MOVIE = "movie";
    public static final String SERIES = "series";
    public static final String EPISODE = "episode";
    public static final String CATEGORY = "cat";

    public String type;
    public String id;
    public String name = "";
    public String icon;
    public String ext;
    public String num;
    public String plot;
    public String info;
    public String categoryId;
    /** Unix seconds the provider added it (used for "Recently added"). */
    public long added;
    /** For episodes: the series they belong to. */
    public String parentId, parentName, parentIcon;
    /** Episode running time, kept separately so the resume note can be rebuilt. */
    public String duration;
    /** For episodes: season number (0 = specials, -1 unknown). */
    public int season = -1;
    /** The Movie Database id (of the film, or of the series for episodes), when the provider gives one. */
    public String tmdb;

    private String lower, compact;
    private String[] words;

    /** Lower-cased name, computed once (makes searching big libraries fast). */
    public String lower() {
        if (lower == null) lower = name.toLowerCase(java.util.Locale.UK);
        return lower;
    }

    /** Name with accents, punctuation and spaces removed: "Spider-Man: Homecoming" → "spidermanhomecoming". */
    public String compact() {
        if (compact == null) compact = squash(name);
        return compact;
    }

    /** The name's words, folded and squashed: "UK: Premier Sports 1" → [uk, premier, sports, 1]. Computed once. */
    public String[] words() {
        if (words == null) {
            java.util.List<String> w = new java.util.ArrayList<>();
            for (String part : fold(name).split("[^\\p{L}\\p{N}]+")) {
                String sq = squash(part);
                if (!sq.isEmpty()) w.add(sq);
            }
            words = w.toArray(new String[0]);
        }
        return words;
    }

    /** True if some word in the name starts with this (already squashed) text, e.g. "spider" in "UK: Spider-Man". */
    public boolean wordStartsWith(String q) {
        String n = name;
        boolean atStart = true;
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            boolean alnum = Character.isLetterOrDigit(ch);
            if (alnum && atStart && squash(n.substring(i)).startsWith(q)) return true;
            atStart = !alnum;
        }
        return false;
    }

    /**
     * Lower-case, drop accents and keep only letters and digits. Written as a plain loop
     * (no regular expressions) so searching tens of thousands of titles stays quick.
     */
    public static String squash(String s) {
        StringBuilder b = new StringBuilder(s.length());
        boolean ascii = true;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch > 127) { ascii = false; break; }
        }
        String src = ascii ? s : java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD);
        for (int i = 0; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (Character.isLetterOrDigit(ch)) b.append(Character.toLowerCase(ch));
        }
        return b.toString();
    }

    /** Lower-case and strip accents (é → e); keeps spaces and punctuation. */
    public static String fold(String s) {
        String n = java.text.Normalizer.normalize(s.toLowerCase(java.util.Locale.UK), java.text.Normalizer.Form.NFKD);
        StringBuilder b = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            if (Character.getType(ch) != Character.NON_SPACING_MARK) b.append(ch);
        }
        return b.toString();
    }

    public Item() {}

    public Item(String type, String id, String name) {
        this.type = type;
        this.id = id;
        this.name = name == null ? "" : name;
    }

    public String key() {
        return type + ":" + id;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("type", type);
            o.put("id", id);
            o.put("name", name);
            o.put("icon", icon);
            o.put("ext", ext);
            o.put("num", num);
            o.put("plot", plot);
            o.put("info", info);
            o.put("cat", categoryId);
            o.put("added", added);
            o.put("pid", parentId);
            o.put("pname", parentName);
            o.put("picon", parentIcon);
            o.put("dur", duration);
            o.put("season", season);
            o.put("tmdb", tmdb);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Item fromJson(JSONObject o) {
        Item it = new Item(o.optString("type"), o.optString("id"), o.optString("name"));
        it.icon = opt(o, "icon");
        it.ext = opt(o, "ext");
        it.num = opt(o, "num");
        it.plot = opt(o, "plot");
        it.info = opt(o, "info");
        it.categoryId = opt(o, "cat");
        it.added = o.optLong("added", 0);
        it.parentId = opt(o, "pid");
        it.parentName = opt(o, "pname");
        it.parentIcon = opt(o, "picon");
        it.duration = opt(o, "dur");
        it.season = o.optInt("season", -1);
        it.tmdb = opt(o, "tmdb");
        return it;
    }

    private static String opt(JSONObject o, String k) {
        if (o.isNull(k)) return null;
        String s = o.optString(k, null);
        return (s == null || s.isEmpty()) ? null : s;
    }
}
