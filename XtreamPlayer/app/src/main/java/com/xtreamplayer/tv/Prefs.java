package com.xtreamplayer.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Saved accounts, settings, favourites, resume positions and watch history.
 * Favourites/history/positions are kept per account (each provider has its own IDs).
 */
public class Prefs {
    public static final String DEFAULT_UA = "XtreamPlayer/1.0 (Linux; Android; Fire TV)";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("xtream", Context.MODE_PRIVATE);
    }

    // ================================================================ accounts

    public static class Account {
        public String id = "", name = "", server = "", user = "", pass = "", ua = "";
        public boolean ok;

        public String label() {
            if (name != null && !name.trim().isEmpty()) return name.trim();
            String host = server.replaceFirst("^https?://", "");
            return user + " @ " + host;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id).put("name", name).put("server", server).put("user", user)
                        .put("pass", pass).put("ua", ua).put("ok", ok);
            } catch (Exception ignored) {
            }
            return o;
        }

        static Account fromJson(JSONObject o) {
            Account a = new Account();
            a.id = o.optString("id", "");
            a.name = o.optString("name", "");
            a.server = o.optString("server", "");
            a.user = o.optString("user", "");
            a.pass = o.optString("pass", "");
            a.ua = o.optString("ua", "");
            a.ok = o.optBoolean("ok", false);
            return a;
        }
    }

    public List<Account> accounts() {
        List<Account> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString("accounts", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Account.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        if (out.isEmpty() && !sp.getString("server", "").isEmpty()) {
            // First run after the multi-account update: adopt the old single login.
            Account legacy = new Account();
            legacy.server = sp.getString("server", "");
            legacy.user = sp.getString("user", "");
            legacy.pass = sp.getString("pass", "");
            legacy.ua = sp.getString("ua", "");
            legacy.ok = sp.getBoolean("ok", false);
            out.add(legacy);
            writeAccounts(out);
            sp.edit().putString("current", "").apply();
        }
        return out;
    }

    private void writeAccounts(List<Account> list) {
        JSONArray a = new JSONArray();
        for (Account x : list) a.put(x.toJson());
        sp.edit().putString("accounts", a.toString()).apply();
    }

    /** The account in use, or null if none. */
    public Account current() {
        List<Account> l = accounts();
        String id = sp.getString("current", null);
        for (Account a : l) if (a.id.equals(id)) return a;
        return l.isEmpty() ? null : l.get(0);
    }

    public void setCurrent(String id) {
        sp.edit().putString("current", id).apply();
    }

    public Account newAccount() {
        Account a = new Account();
        a.id = accounts().isEmpty() ? "" : "a" + System.currentTimeMillis();
        return a;
    }

    public void saveAccount(Account acc) {
        List<Account> l = accounts();
        boolean found = false;
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).id.equals(acc.id)) {
                l.set(i, acc);
                found = true;
            }
        }
        if (!found) l.add(acc);
        writeAccounts(l);
    }

    public void removeAccount(String id) {
        List<Account> l = accounts();
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).id.equals(id)) l.remove(i);
        writeAccounts(l);
        String prefix = id.isEmpty() ? null : id + "_";
        SharedPreferences.Editor e = sp.edit();
        for (String k : sp.getAll().keySet()) {
            if (prefix != null && k.startsWith(prefix)) e.remove(k);
            if (prefix == null && isAccountData(k)) e.remove(k);
        }
        e.putString("current", l.isEmpty() ? "" : l.get(0).id).apply();
        FAV_CACHE.clear();
        FAV_KEYS.clear();
    }

    private static boolean isAccountData(String k) {
        return k.startsWith("fav_") || k.startsWith("pos_") || k.startsWith("dur_") || k.startsWith("hist_") || k.startsWith("lib_time");
    }

    /** Key prefix for the current account's data ("" for the original account, so old data keeps working). */
    String ns() {
        String id = sp.getString("current", null); // fast path: called for every list row
        if (id != null) return id.isEmpty() ? "" : id + "_";
        Account a = current();
        return a == null || a.id.isEmpty() ? "" : a.id + "_";
    }

    /** True once a login has succeeded with the current account. */
    public boolean hasAccount() {
        Account a = current();
        return a != null && a.ok && !a.server.isEmpty() && !a.user.isEmpty();
    }

    public String server() { Account a = current(); return a == null ? "" : a.server; }
    public String user() { Account a = current(); return a == null ? "" : a.user; }
    public String pass() { Account a = current(); return a == null ? "" : a.pass; }

    public String userAgent() {
        Account a = current();
        String ua = a == null ? null : a.ua;
        return ua == null || ua.trim().isEmpty() ? DEFAULT_UA : ua;
    }

    public void setUserAgent(String ua) {
        Account a = current();
        if (a == null) return;
        a.ua = ua == null || ua.equals(DEFAULT_UA) ? "" : ua;
        saveAccount(a);
    }

    // ================================================================ settings (all accounts)

    /** "ts" or "m3u8". */
    public String liveFormat() { return sp.getString("live_format", "ts"); }
    public void setLiveFormat(String f) { sp.edit().putString("live_format", f).apply(); }

    /** 0 small, 1 medium, 2 large, 3 extra large. */
    public int subtitleSize() { return sp.getInt("sub_size", 1); }
    public void setSubtitleSize(int s) { sp.edit().putInt("sub_size", s).apply(); }

    public int subtitleColor() { return Math.min(sp.getInt("sub_color", 0), 5); }
    public void setSubtitleColor(int i) { sp.edit().putInt("sub_color", i).apply(); }

    public boolean subtitleBackground() { return sp.getBoolean("sub_bg", false); }
    public void setSubtitleBackground(boolean b) { sp.edit().putBoolean("sub_bg", b).apply(); }

    /** 0 = 25%, 1 = 50%, 2 = 75%, 3 = 100%. */
    public int subtitleOpacity() { return Math.min(sp.getInt("sub_bg_opacity", 2), 3); }
    public void setSubtitleOpacity(int i) { sp.edit().putInt("sub_bg_opacity", i).apply(); }

    /** Preferred subtitle language; "" = provider default, "off" = no subtitles. */
    public String subtitleLanguage() { return sp.getString("sub_lang", ""); }
    public void setSubtitleLanguage(String l) { sp.edit().putString("sub_lang", l == null ? "" : l).apply(); }

    public String audioLanguage() { return sp.getString("audio_lang", ""); }
    public void setAudioLanguage(String l) { sp.edit().putString("audio_lang", l == null ? "" : l).apply(); }

    public boolean autoRecover() { return sp.getBoolean("auto_recover", true); }
    public void setAutoRecover(boolean b) { sp.edit().putBoolean("auto_recover", b).apply(); }

    /** 0 Automatic (VLC for Live TV, Android player for Movies & Series), 1 Android player only, 2 VLC only. */
    public int engineMode() { return Math.min(sp.getInt("engine", 0), 2); }
    public void setEngineMode(int m) { sp.edit().putInt("engine", m).apply(); }

    /**
     * A player picked for one channel/video, either by you in the player menu or automatically
     * after a freeze: Boolean.TRUE = VLC, Boolean.FALSE = Android player, null = use the default.
     */
    public Boolean playerChoice(Item it) {
        if (set("vlc_items").contains(it.key())) return Boolean.TRUE;
        if (set("exo_items").contains(it.key())) return Boolean.FALSE;
        return null;
    }

    public void setPlayerChoice(Item it, boolean vlc) {
        Set<String> v = new HashSet<>(set("vlc_items")), x = new HashSet<>(set("exo_items"));
        v.remove(it.key());
        x.remove(it.key());
        (vlc ? v : x).add(it.key());
        sp.edit().putStringSet(ns() + "vlc_items", v).putStringSet(ns() + "exo_items", x).apply();
    }

    public int playerChoiceCount() { return set("vlc_items").size() + set("exo_items").size(); }

    public void clearPlayerChoices() { sp.edit().remove(ns() + "vlc_items").remove(ns() + "exo_items").apply(); }

    private Set<String> set(String k) { return sp.getStringSet(ns() + k, new HashSet<String>()); }

    SharedPreferences raw() { return sp; }

    /** True if VLC crashed the app on this device; we then use the Android player. */
    public boolean vlcBroken() { return sp.getBoolean("vlc_broken", false); }
    public void setVlcBroken(boolean b) { sp.edit().putBoolean("vlc_broken", b).apply(); }

    /** One-time notice to show after VLC crashed. */
    public boolean takeVlcBrokenNotice() {
        boolean b = sp.getBoolean("vlc_broken_notice", false);
        if (b) sp.edit().putBoolean("vlc_broken_notice", false).apply();
        return b;
    }

    public boolean darkTheme() { return sp.getBoolean("dark", true); }
    public void setDarkTheme(boolean b) { sp.edit().putBoolean("dark", b).apply(); }

    /** 0 off, 1 on (muted), 2 on with sound. */
    public int previewMode() { return sp.getInt("preview", 1); }
    public void setPreviewMode(int m) { sp.edit().putInt("preview", m).apply(); }

    /** Hours between automatic library refreshes: 0 = every launch, -1 = manual only. */
    public int refreshHours() { return sp.getInt("refresh_hours", 12); }
    public void setRefreshHours(int h) { sp.edit().putInt("refresh_hours", h).apply(); }

    public long lastRefresh() { return sp.getLong(ns() + "lib_time", 0); }
    public void setLastRefresh(long t) { sp.edit().putLong(ns() + "lib_time", t).apply(); }

    // ================================================================ favourites (per account)

    private static final Map<String, List<Item>> FAV_CACHE = new HashMap<>();
    private static final Set<String> FAV_KEYS = new HashSet<>();

    private List<Item> loadFavs(String type) {
        String ck = ns() + type;
        List<Item> cached = FAV_CACHE.get(ck);
        if (cached != null) return cached;
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString(ns() + "fav_" + type, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Item.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        FAV_CACHE.put(ck, out);
        for (Item f : out) FAV_KEYS.add(ns() + f.key());
        return out;
    }

    public List<Item> favs(String type) {
        return new ArrayList<>(loadFavs(type));
    }

    public boolean isFav(Item it) {
        loadFavs(it.type);
        return FAV_KEYS.contains(ns() + it.key());
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
        if (removed) FAV_KEYS.remove(ns() + it.key());
        else {
            list.add(it);
            FAV_KEYS.add(ns() + it.key());
        }
        JSONArray a = new JSONArray();
        for (Item f : list) a.put(f.toJson());
        sp.edit().putString(ns() + "fav_" + it.type, a.toString()).apply();
        return !removed;
    }

    // ================================================================ favourite categories (per account and section)

    public List<String> favCategories(String type) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString(ns() + "favcat_" + type, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    public boolean isFavCategory(String type, String id) { return favCategories(type).contains(id); }

    /** @return true if the category is now a favourite. */
    public boolean toggleFavCategory(String type, String id) {
        List<String> l = favCategories(type);
        boolean now = !l.remove(id);
        if (now) l.add(id);
        sp.edit().putString(ns() + "favcat_" + type, new JSONArray(l).toString()).apply();
        return now;
    }

    // ================================================================ customise the home screen

    /** Rows that can be switched on or off in Settings → Customise Home Screen (all on by default). */
    public static final String[] HOME_KEYS = {"continue", "recent", "fav_live", "fav_movie", "fav_series",
            "top_movies", "top_series", "new_movies", "new_series"};
    public static final String[] HOME_NAMES = {"Continue Watching", "Recent Channels", "Favourite Channels",
            "Favourite Movies", "Favourite Series", "Top 10 Movies in the UK", "Top 10 Series in the UK",
            "Recently Added Movies", "Recently Added Series"};

    public boolean homeShows(String key) { return sp.getBoolean("home_" + key, true); }

    public void setHomeShows(String key, boolean on) { sp.edit().putBoolean("home_" + key, on).apply(); }

    // ================================================================ resume positions (per account, ms)

    public long position(Item it) { return sp.getLong(ns() + "pos_" + it.key(), 0); }

    public void setPosition(Item it, long ms) {
        if (ms <= 0) sp.edit().remove(ns() + "pos_" + it.key()).apply();
        else sp.edit().putLong(ns() + "pos_" + it.key(), ms).apply();
    }

    public long duration(Item it) { return sp.getLong(ns() + "dur_" + it.key(), 0); }

    public void setDuration(Item it, long ms) {
        if (ms > 0) sp.edit().putLong(ns() + "dur_" + it.key(), ms).apply();
    }

    // ================================================================ watch history (per account, newest first)

    public void addHistory(Item it) {
        boolean live = Item.LIVE.equals(it.type);
        List<Item> list = history(live);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).key().equals(it.key())) list.remove(i);
        list.add(0, it);
        while (list.size() > 40) list.remove(list.size() - 1);
        JSONArray a = new JSONArray();
        for (Item h : list) a.put(h.toJson());
        sp.edit().putString(ns() + "hist_" + (live ? "live" : "vod"), a.toString()).apply();
    }

    public List<Item> history(boolean live) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString(ns() + "hist_" + (live ? "live" : "vod"), "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Item.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Movies and episodes started but not finished. */
    public List<Item> continueWatching() {
        List<Item> out = new ArrayList<>();
        for (Item it : history(false)) if (position(it) > 0) out.add(it);
        return out;
    }

    public List<Item> continueMovies() {
        List<Item> out = new ArrayList<>();
        for (Item it : continueWatching()) if (Item.MOVIE.equals(it.type)) out.add(it);
        return out;
    }

    /** Series you've been watching, most recent first (one entry per series). */
    public List<Item> continueSeries() {
        Map<String, Item> m = new LinkedHashMap<>();
        for (Item ep : history(false)) {
            if (!Item.EPISODE.equals(ep.type) || ep.parentId == null || m.containsKey(ep.parentId)) continue;
            Item s = new Item(Item.SERIES, ep.parentId, ep.parentName == null ? "" : ep.parentName);
            s.icon = ep.parentIcon;
            m.put(ep.parentId, s);
        }
        return new ArrayList<>(m.values());
    }

    public void clearHistory() {
        String ns = ns();
        SharedPreferences.Editor e = sp.edit();
        for (String k : sp.getAll().keySet()) {
            String rest = ns.isEmpty() ? k : (k.startsWith(ns) ? k.substring(ns.length()) : null);
            if (rest == null) continue;
            if (ns.isEmpty() && rest.matches("^a\\d+_.*")) continue; // another account's data
            if (rest.startsWith("pos_") || rest.startsWith("dur_") || rest.startsWith("hist_")) e.remove(k);
        }
        e.apply();
    }
}
