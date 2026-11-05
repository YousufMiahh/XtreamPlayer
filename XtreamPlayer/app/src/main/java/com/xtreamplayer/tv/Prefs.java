package com.xtreamplayer.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** Saved login, settings, favourites and resume positions. */
public class Prefs {
    public static final String DEFAULT_UA = "XtreamPlayer/1.0 (Linux; Android; Fire TV)";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("xtream", Context.MODE_PRIVATE);
    }

    /** True once a login has succeeded with the saved details. */
    public boolean hasAccount() {
        return sp.getBoolean("ok", false) && !server().isEmpty() && !user().isEmpty();
    }

    public void markLoggedIn(boolean ok) { sp.edit().putBoolean("ok", ok).apply(); }

    public String server() { return sp.getString("server", ""); }
    public String user() { return sp.getString("user", ""); }
    public String pass() { return sp.getString("pass", ""); }

    public void setAccount(String server, String user, String pass) {
        sp.edit().putString("server", server).putString("user", user).putString("pass", pass).apply();
    }

    public void clearAccount() {
        sp.edit().remove("server").remove("user").remove("pass").putBoolean("ok", false).apply();
    }

    /** "ts" or "m3u8". */
    public String liveFormat() { return sp.getString("live_format", "ts"); }
    public void setLiveFormat(String f) { sp.edit().putString("live_format", f).apply(); }

    public String userAgent() {
        String ua = sp.getString("ua", DEFAULT_UA);
        return ua == null || ua.trim().isEmpty() ? DEFAULT_UA : ua;
    }
    public void setUserAgent(String ua) { sp.edit().putString("ua", ua).apply(); }

    // ---- favourites ----

    private static final java.util.Map<String, List<Item>> FAV_CACHE = new java.util.HashMap<>();
    private static final java.util.Set<String> FAV_KEYS = new java.util.HashSet<>();

    private List<Item> loadFavs(String type) {
        List<Item> cached = FAV_CACHE.get(type);
        if (cached != null) return cached;
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString("fav_" + type, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Item.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        FAV_CACHE.put(type, out);
        for (Item f : out) FAV_KEYS.add(f.key());
        return out;
    }

    public List<Item> favs(String type) {
        return new ArrayList<>(loadFavs(type));
    }

    public boolean isFav(Item it) {
        loadFavs(it.type);
        return FAV_KEYS.contains(it.key());
    }

    /** @return true if the item is now a favourite. */
    public boolean toggleFav(Item it) {
        List<Item> list = loadFavs(it.type);
        boolean removed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(it.id)) {
                list.remove(i);
                removed = true;
            }
        }
        if (removed) FAV_KEYS.remove(it.key());
        else {
            list.add(it);
            FAV_KEYS.add(it.key());
        }
        JSONArray a = new JSONArray();
        for (Item f : list) a.put(f.toJson());
        sp.edit().putString("fav_" + it.type, a.toString()).apply();
        return !removed;
    }

    // ---- resume positions (milliseconds) ----

    public long position(Item it) { return sp.getLong("pos_" + it.key(), 0); }

    public void setPosition(Item it, long ms) {
        if (ms <= 0) sp.edit().remove("pos_" + it.key()).apply();
        else sp.edit().putLong("pos_" + it.key(), ms).apply();
    }
}
