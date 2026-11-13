package com.xtreamplayer.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A series' episodes in order, for the player: so an episode started on its own (from Continue
 * Watching, the Queue, or the last of a season) still knows what comes next.
 */
final class Episodes {
    private Episodes() {}

    /** Background thread. All the episodes after-and-including this one's season, in order; or null. */
    static List<Item> following(XtreamApi api, Item ep) throws Exception {
        if (ep.parentId == null) return null;
        JSONObject r = api.seriesInfo(ep.parentId);
        if (r == null) return null;
        JSONObject info = r.optJSONObject("info");
        String tmdb = info == null ? null : MovieActivity.str(info, "tmdb", MovieActivity.str(info, "tmdb_id", null));
        TreeMap<Integer, List<Item>> seasons = new TreeMap<>();
        Object eps = r.opt("episodes");
        if (eps instanceof JSONObject) {
            JSONObject o = (JSONObject) eps;
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                int n;
                try { n = Integer.parseInt(k.trim()); } catch (Exception e) { n = 0; }
                add(seasons, o.optJSONArray(k), n, ep, tmdb);
            }
        } else if (eps instanceof JSONArray) {
            JSONArray a = (JSONArray) eps;
            for (int i = 0; i < a.length(); i++) {
                Object x = a.opt(i);
                if (x instanceof JSONArray) add(seasons, (JSONArray) x, i + 1, ep, tmdb);
            }
        }
        List<Item> out = new ArrayList<>();
        boolean special = ep.season == 0;
        for (Map.Entry<Integer, List<Item>> s : seasons.entrySet()) {
            if ((s.getKey() == 0) != special) continue; // Specials don't run into the main seasons
            out.addAll(s.getValue());
        }
        return out;
    }

    private static void add(TreeMap<Integer, List<Item>> seasons, JSONArray arr, int fallbackSeason, Item like, String tmdb) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            int season = e.has("season") ? e.optInt("season", fallbackSeason) : fallbackSeason;
            Item it = new Item(Item.EPISODE, e.optString("id"), "");
            if (it.id.isEmpty()) continue;
            it.num = MovieActivity.str(e, "episode_num", String.valueOf(i + 1));
            it.name = MovieActivity.str(e, "title", like.parentName == null ? "" : like.parentName);
            it.ext = MovieActivity.str(e, "container_extension", "mp4");
            it.parentId = like.parentId;
            it.parentName = like.parentName;
            it.parentIcon = like.parentIcon;
            it.season = season;
            it.tmdb = tmdb != null ? tmdb : like.tmdb;
            JSONObject ei = e.optJSONObject("info");
            if (ei != null) {
                it.plot = MovieActivity.str(ei, "plot", null);
                it.icon = MovieActivity.str(ei, "movie_image", null);
            }
            List<Item> list = seasons.get(season);
            if (list == null) seasons.put(season, list = new ArrayList<>());
            list.add(it);
        }
    }
}
