package com.xtreamplayer.tv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Netflix's official UK Top 10 (films and TV), from the public data file Netflix publishes.
 * Netflix updates it weekly (Tuesdays, for the week ending the previous Sunday), so although the
 * app checks every hour, it only downloads when a new week is due — and asks Netflix whether the
 * file changed first. Titles are matched against your provider's library so only things you can
 * actually watch are shown.
 */
public final class Top10 {
    private Top10() {}

    static final String URL = "https://www.netflix.com/tudum/top10/data/all-weeks-countries.tsv";
    private static final String FILE = "netflix_top10_uk.json";
    private static final long HOUR = 3600_000L, DAY = 24 * HOUR;

    public static class Data {
        public String week = "";
        public List<String> films = new ArrayList<>(), tv = new ArrayList<>();
        long checked;
        String etag, lastModified;
    }

    // ------------------------------------------------------------ cache

    public static Data load(Context c) {
        try {
            File f = new File(c.getFilesDir(), FILE);
            if (!f.exists()) return null;
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(b);
            in.close();
            JSONObject o = new JSONObject(new String(b, 0, Math.max(0, n), "UTF-8"));
            Data d = new Data();
            d.week = o.optString("week", "");
            d.checked = o.optLong("checked", 0);
            d.etag = o.optString("etag", null);
            d.lastModified = o.optString("lastModified", null);
            JSONArray fa = o.optJSONArray("films"), ta = o.optJSONArray("tv");
            for (int i = 0; fa != null && i < fa.length(); i++) d.films.add(fa.getString(i));
            for (int i = 0; ta != null && i < ta.length(); i++) d.tv.add(ta.getString(i));
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    static void save(Context c, Data d) {
        try {
            JSONObject o = new JSONObject();
            o.put("week", d.week).put("checked", d.checked).put("etag", d.etag).put("lastModified", d.lastModified);
            o.put("films", new JSONArray(d.films)).put("tv", new JSONArray(d.tv));
            FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), FILE));
            out.write(o.toString().getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }

    /** Hourly at most, and only once the next weekly list should be out (the Tuesday after the next week ends). */
    public static boolean due(Data d) {
        if (d == null || d.week.isEmpty()) return d == null || System.currentTimeMillis() - d.checked > HOUR;
        long now = System.currentTimeMillis();
        if (now - d.checked < HOUR) return false;
        try {
            long weekEnd = new SimpleDateFormat("yyyy-MM-dd", Locale.UK).parse(d.week).getTime();
            return now >= weekEnd + 9 * DAY; // Sunday + 7 = next Sunday, + 2 = Tuesday it's published
        } catch (Exception e) {
            return true;
        }
    }

    /** Fetches if due; returns the newest data (or the cached copy if nothing changed or offline). */
    public static Data refresh(Context c) {
        Data old = load(c);
        if (!due(old)) return old;
        try {
            Data fresh = fetch(old);
            fresh.checked = System.currentTimeMillis();
            save(c, fresh);
            return fresh;
        } catch (Exception e) {
            if (old != null) {
                old.checked = System.currentTimeMillis() - HOUR + 10 * 60_000L; // try again in ~10 minutes
                save(c, old);
            }
            return old;
        }
    }

    /** Its own quiet background worker, so the weekly download never holds up the rest of the app. */
    static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(
            new java.util.concurrent.ThreadFactory() {
                public Thread newThread(final Runnable r) {
                    Thread t = new Thread(new Runnable() {
                        public void run() {
                            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                            r.run();
                        }
                    }, "netflix-top10");
                    t.setDaemon(true);
                    return t;
                }
            });

    // ------------------------------------------------------------ download

    private static Data fetch(Data old) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(URL).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(60000);
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9; AFTMM) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        if (old != null && old.etag != null) con.setRequestProperty("If-None-Match", old.etag);
        if (old != null && old.lastModified != null) con.setRequestProperty("If-Modified-Since", old.lastModified);
        try {
            int code = con.getResponseCode();
            if (code == 304 && old != null) return old; // unchanged
            if (code >= 400) throw new IOException("Netflix replied HTTP " + code);
            Data d = parse(new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"), 65536));
            d.etag = con.getHeaderField("ETag");
            d.lastModified = con.getHeaderField("Last-Modified");
            if (d.week.isEmpty() && old != null) return old;
            return d;
        } finally {
            con.disconnect();
        }
    }

    /** Reads the file as it arrives, keeping only UK rows from the latest week. */
    static Data parse(BufferedReader r) throws IOException {
        String header = r.readLine();
        if (header == null) throw new IOException("Empty Top 10 file");
        String[] h = header.split("\t");
        int iCountry = -1, iIso = -1, iWeek = -1, iCat = -1, iRank = -1, iTitle = -1;
        for (int i = 0; i < h.length; i++) {
            String k = h[i].trim().toLowerCase(Locale.UK);
            if (k.equals("country_name")) iCountry = i;
            else if (k.equals("country_iso2")) iIso = i;
            else if (k.equals("week")) iWeek = i;
            else if (k.equals("category")) iCat = i;
            else if (k.equals("weekly_rank")) iRank = i;
            else if (k.equals("show_title")) iTitle = i;
        }
        if (iWeek < 0 || iCat < 0 || iRank < 0 || iTitle < 0 || (iIso < 0 && iCountry < 0)) {
            throw new IOException("Unexpected Top 10 file layout");
        }
        String bestWeek = "";
        TreeMap<Integer, String> films = new TreeMap<>(), tv = new TreeMap<>();
        String line;
        while ((line = r.readLine()) != null) {
            // The file covers ~90 countries and every week since 2021: skip non-UK lines without splitting them
            if (iIso >= 0 ? line.indexOf("\tGB\t") < 0 && !line.startsWith("GB\t") : line.indexOf("United Kingdom") < 0) continue;
            String[] f = line.split("\t", -1);
            if (f.length <= Math.max(Math.max(iWeek, iCat), Math.max(iRank, iTitle))) continue;
            boolean uk = iIso >= 0 && iIso < f.length ? "GB".equalsIgnoreCase(f[iIso].trim())
                    : iCountry < f.length && "United Kingdom".equalsIgnoreCase(f[iCountry].trim());
            if (!uk) continue;
            String week = f[iWeek].trim();
            int cmp = week.compareTo(bestWeek); // yyyy-MM-dd compares correctly as text
            if (cmp < 0) continue;
            if (cmp > 0) {
                bestWeek = week;
                films.clear();
                tv.clear();
            }
            int rank;
            try {
                rank = Integer.parseInt(f[iRank].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            String title = f[iTitle].trim();
            if (f[iCat].toLowerCase(Locale.UK).startsWith("film")) films.put(rank, title);
            else tv.put(rank, title);
        }
        Data d = new Data();
        d.week = bestWeek;
        d.films.addAll(films.values());
        d.tv.addAll(tv.values());
        return d;
    }

    // ------------------------------------------------------------ matching to your library

    /**
     * Finds each Netflix title in the provider's list, ignoring things providers add to names
     * ("EN - ", "UK: ", "(2022)", "[4K]"), punctuation and accents. Keeps Netflix's order.
     */
    public static List<Item> match(List<String> titles, List<Item> library) {
        List<Item> out = new ArrayList<>();
        if (titles == null || library == null || titles.isEmpty()) return out;
        Map<String, Integer> wanted = new HashMap<>();
        for (int i = 0; i < titles.size(); i++) {
            String k = key(titles.get(i));
            if (!k.isEmpty() && !wanted.containsKey(k)) wanted.put(k, i);
        }
        Item[] found = new Item[titles.size()];
        for (Item it : library) {
            String c = it.compact();
            // quick pre-check before the more careful comparison
            boolean maybe = false;
            for (String k : wanted.keySet()) if (c.contains(k)) { maybe = true; break; }
            if (!maybe) continue;
            Integer idx = wanted.get(key(it.name));
            if (idx == null) continue;
            if (found[idx] == null || it.added > found[idx].added) found[idx] = it; // newest copy wins
        }
        for (int i = 0; i < found.length; i++) {
            if (found[i] == null) continue;
            out.add(found[i]);
        }
        return out;
    }

    static String key(String name) {
        String n = name == null ? "" : name;
        n = n.replaceFirst("^\\s*(?:[A-Za-z0-9]{2,4})\\s*[|:\\-]\\s+", ""); // "EN - ", "UK: ", "4K | "
        n = n.replaceAll("\\(.*?\\)|\\[.*?\\]", " ");                       // "(2022)", "[Multi-Sub]"
        n = n.replaceFirst("[:\\-]\\s*(?:season|series|limited series)\\b.*$", ""); // "Show: Season 2"
        return Item.squash(n);
    }
}
