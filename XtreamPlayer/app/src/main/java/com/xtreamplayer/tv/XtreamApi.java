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
        readArray(api(action, null), CAT_FIELDS, new ObjectHandler() {
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
        final int[] counter = new int[1];
        if (categoryId == null) PROGRESS.put(type, counter);
        readArray(api(action, extra), STREAM_FIELDS, new ObjectHandler() {
            public void handle(Map<String, String> m) {
                counter[0]++;
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
                it.categoryId = m.get("category_id");
                it.added = parseLong(Item.SERIES.equals(type) ? m.get("last_modified") : m.get("added"));
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

    public JSONObject vodInfo(String vodId) throws Exception {
        String body = Http.string(api("get_vod_info", "&vod_id=" + Uri.encode(vodId)), ua).trim();
        if (!body.startsWith("{")) return new JSONObject();
        return new JSONObject(body);
    }

    /** A TV guide entry. Times are unix seconds. */
    public static class Programme {
        public String title, desc;
        public long start, stop;

        public String timeRange() {
            if (start <= 0) return "";
            SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.UK);
            return hm.format(new Date(start * 1000L)) + " – " + hm.format(new Date(stop * 1000L));
        }

        /** 0..1 how far through the programme we are now. */
        public float progress() {
            long now = System.currentTimeMillis() / 1000L;
            if (stop <= start) return 0;
            return Math.max(0f, Math.min(1f, (now - start) / (float) (stop - start)));
        }
    }

    /** Current and upcoming programmes (may be empty). */
    public List<Programme> epg(String streamId) { return epg(streamId, 6); }

    /** @param limit how many programmes ahead (the TV guide asks for more). */
    public List<Programme> epg(String streamId, int limit) {
        List<Programme> out = new ArrayList<>();
        try {
            String body = Http.string(api("get_short_epg", "&stream_id=" + Uri.encode(streamId) + "&limit=" + limit), ua);
            JSONObject o = new JSONObject(body);
            JSONArray a = o.optJSONArray("epg_listings");
            if (a == null) return out;
            long now = System.currentTimeMillis() / 1000L;
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.getJSONObject(i);
                Programme p = new Programme();
                p.start = e.optLong("start_timestamp", 0);
                p.stop = e.optLong("stop_timestamp", 0);
                if (p.stop != 0 && p.stop < now) continue;
                p.title = b64(e.optString("title", ""));
                p.desc = b64(e.optString("description", ""));
                out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** @return {"Now ...", "Next ..."} (either may be null). */
    public String[] shortEpg(String streamId) {
        String[] res = new String[2];
        List<Programme> l = epg(streamId);
        for (int i = 0; i < 2 && i < l.size(); i++) {
            String t = l.get(i).timeRange();
            res[i] = (t.isEmpty() ? "" : t + "  ") + l.get(i).title;
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

    /** Items read so far in each full-list download, by type (shown on the setup screen). */
    public static final Map<String, int[]> PROGRESS = new java.util.concurrent.ConcurrentHashMap<>();

    public static int progress(String type) {
        int[] c = PROGRESS.get(type);
        return c == null ? 0 : c[0];
    }

    // Only the fields the app uses; everything else in each entry (cast, director, trailers,
    // long descriptions…) is skipped without being stored, which makes big lists much faster.
    private static final java.util.Set<String> CAT_FIELDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "category_id", "category_name"));
    private static final java.util.Set<String> STREAM_FIELDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "stream_id", "series_id", "name", "stream_icon", "cover", "num", "container_extension",
            "rating", "genre", "plot", "category_id", "added", "last_modified"));

    /** Streams a JSON array of flat objects; keeps memory low for huge VOD lists. */
    private void readArray(String url, java.util.Set<String> keep, ObjectHandler h) throws IOException {
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
                    if (!keep.contains(k)) {
                        r.skipValue();
                        continue;
                    }
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

    static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
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
