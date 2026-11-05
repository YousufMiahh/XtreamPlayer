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

    private String lower;

    /** Lower-cased name, computed once (makes searching big libraries fast). */
    public String lower() {
        if (lower == null) lower = name.toLowerCase(java.util.Locale.UK);
        return lower;
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
        return it;
    }

    private static String opt(JSONObject o, String k) {
        if (o.isNull(k)) return null;
        String s = o.optString(k, null);
        return (s == null || s.isEmpty()) ? null : s;
    }
}
