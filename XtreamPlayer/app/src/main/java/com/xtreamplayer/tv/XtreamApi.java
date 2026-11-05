package com.xtreamplayer.tv;

import android.net.Uri;
import android.util.Base64;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Xtream Codes "player_api.php" client. */
public class XtreamApi {
    public final String base;
    public final String user;
    public final String pass;
    public final String ua;
    private final Prefs prefs;

    public XtreamApi(Prefs p) {
        prefs = p;
        base = normalize(p.server());
        user = p.user();
        pass = p.pass();
        ua = p.userAgent();
    }

    /** Turns whatever the user typed into "http(s)://host:port". */
    public static String normalize(String server) {
        String s = server == null ? "" : server.trim();
        if (s.isEmpty()) return s;
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        String[] tails = {"/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php"};
        for (String t : tails) if (s.endsWith(t)) s = s.substring(0, s.length() - t.length());
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private String api(String action, String extra) {
        StringBuilder b = new StringBuilder(base)
                .append("/player_api.php?username=").append(Uri.encode(user))
                .append("&password=").append(Uri.encode(pass));
        if (action != null) b.append("&action=").append(action);
        if (extra != null) b.append(extra);
        return b.toString();
    }

    // ---------------- account ----------------

    public JSONObject login() throws Exception {
        String body = Http.string(api(null, null), ua).trim();
        if (!body.startsWith("{")) throw new IOException("This doesn't look like an Xtream server");
        return new JSONObject(body);
    }

    public static String describeAccount(JSONObject r) {
        JSONObject u = r.optJSONObject("user_info");
        if (u == null) return "";
        StringBuilder b = new StringBuilder();
        b.append("Status: ").append(u.optString("status", "?"));
        String exp = u.optString("exp_date", "");
        if (exp.isEmpty() || "null".equals(exp)) {
            b.append("  ·  Expires: never");
        } else {
            try {
                long t = Long.parseLong(exp) * 1000L;
                b.append("  ·  Expires: ").append(new SimpleDateFormat("d MMM yyyy", Locale.UK).format(new Date(t)));
            } catch (NumberFormatException ignored) {
            }
        }
        String act = u.optString("active_cons", ""), max = u.optString("max_connections", "");
        if (!max.isEmpty()) b.append("  ·  Connections: ").append(act.isEmpty() ? "0" : act).append("/").append(max);
        return b.toString();
    }

    // ---------------- lists ----------------

    public List<Item> categories(String type) throws IOException {
        String action = Item.LIVE.equals(type) ? "get_live_categories"
                : Item.MOVIE.equals(type) ? "get_vod_categories" : "get_series_categories";
        final List<Item> out = new ArrayList<>();
        readArray(api(action, null), new ObjectHandler() {
            public void handle(Map<String, String> m) {
                String id = m.get("category_id");
                if (id == null) return;
                out.add(new Item(Item.CATEGORY, id, clean(m.get("category_name"))));
            }
        });
        return out;
    }

    /** @param categoryId null for every item of that type. */
    public List<Item> streams(final String type, String categoryId) throws IOException {
        String action = Item.LIVE.equals(type) ? "get_live_streams"
                : Item.MOVIE.equals(type) ? "get_vod_streams" : "get_series";
        String extra = categoryId == null ? null : "&category_id=" + Uri.encode(categoryId);
        final List<Item> out = new ArrayList<>();
        readArray(api(action, extra), new ObjectHandler() {
            public void handle(Map<String, String> m) {
                Item it;
                if (Item.SERIES.equals(type)) {
                    it = new Item(type, m.get("series_id"), clean(m.get("name")));
                    it.icon = m.get("cover");
                    it.plot = m.get("plot");
                    it.info = joinNonEmpty(m.get("genre"), rating(m.get("rating")));
                } else {
                    it = new Item(type, m.get("stream_id"), clean(m.get("name")));
                    it.icon = m.get("stream_icon");
                    it.num = m.get("num");
                    it.ext = m.get("container_extension");
                    if (Item.MOVIE.equals(type)) it.info = rating(m.get("rating"));
                }
                if (it.id == null || it.id.isEmpty()) return;
                if (it.icon != null && it.icon.isEmpty()) it.icon = null;
                out.add(it);
            }
        });
        return out;
    }

    public JSONObject seriesInfo(String seriesId) throws Exception {
        String body = Http.string(api("get_series_info", "&series_id=" + Uri.encode(seriesId)), ua).trim();
        if (!body.startsWith("{")) return new JSONObject();
        return new JSONObject(body);
    }

    /** @return {"Now: ...", "Next: ..."} (either may be null). */
    public String[] shortEpg(String streamId) {
        String[] res = new String[2];
        try {
            String body = Http.string(api("get_short_epg", "&stream_id=" + Uri.encode(streamId) + "&limit=4"), ua);
            JSONObject o = new JSONObject(body);
            JSONArray a = o.optJSONArray("epg_listings");
            if (a == null) return res;
            long now = System.currentTimeMillis() / 1000L;
            SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.UK);
            int found = 0;
            for (int i = 0; i < a.length() && found < 2; i++) {
                JSONObject e = a.getJSONObject(i);
                long start = e.optLong("start_timestamp", 0), stop = e.optLong("stop_timestamp", 0);
                if (stop != 0 && stop < now) continue;
                String title = b64(e.optString("title", ""));
                String time = start > 0 ? hm.format(new Date(start * 1000L)) + "–" + hm.format(new Date(stop * 1000L)) + "  " : "";
                res[found++] = time + title;
            }
        } catch (Exception ignored) {
        }
        return res;
    }

    // ---------------- playback URLs ----------------

    public String playUrl(Item it, String liveFormat) {
        String u = Uri.encode(user), p = Uri.encode(pass);
        if (Item.LIVE.equals(it.type)) {
            return base + "/live/" + u + "/" + p + "/" + it.id + "." + liveFormat;
        }
        String ext = it.ext == null || it.ext.isEmpty() ? "mp4" : it.ext;
        String kind = Item.EPISODE.equals(it.type) ? "series" : "movie";
        return base + "/" + kind + "/" + u + "/" + p + "/" + it.id + "." + ext;
    }

    public Prefs prefs() { return prefs; }

    // ---------------- helpers ----------------

    interface ObjectHandler {
        void handle(Map<String, String> fields);
    }

    /** Streams a JSON array of flat objects; keeps memory low for huge VOD lists. */
    private void readArray(String url, ObjectHandler h) throws IOException {
        HttpURLConnection c = Http.open(url, ua);
        try (InputStream in = new BufferedInputStream(c.getInputStream(), 65536);
             JsonReader r = new JsonReader(new InputStreamReader(in, "UTF-8"))) {
            r.setLenient(true);
            if (r.peek() != JsonToken.BEGIN_ARRAY) return; // empty or error object
            r.beginArray();
            Map<String, String> m = new HashMap<>();
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) {
                    r.skipValue();
                    continue;
                }
                m.clear();
                r.beginObject();
                while (r.hasNext()) {
                    String k = r.nextName();
                    JsonToken t = r.peek();
                    if (t == JsonToken.STRING || t == JsonToken.NUMBER) m.put(k, r.nextString());
                    else if (t == JsonToken.BOOLEAN) m.put(k, String.valueOf(r.nextBoolean()));
                    else r.skipValue();
                }
                r.endObject();
                h.handle(m);
            }
            r.endArray();
        } finally {
            c.disconnect();
        }
    }

    static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    static String rating(String r) {
        if (r == null || r.isEmpty() || "0".equals(r) || "null".equals(r)) return null;
        try {
            double d = Double.parseDouble(r);
            if (d <= 0) return null;
            return String.format(Locale.UK, "★ %.1f", d);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String joinNonEmpty(String... parts) {
        StringBuilder b = new StringBuilder();
        for (String s : parts) {
            if (s == null || s.trim().isEmpty() || "null".equals(s)) continue;
            if (b.length() > 0) b.append("  ·  ");
            b.append(s.trim());
        }
        return b.length() == 0 ? null : b.toString();
    }

    static String b64(String s) {
        try {
            return new String(Base64.decode(s, Base64.DEFAULT), "UTF-8").trim();
        } catch (Exception e) {
            return s;
        }
    }
}
