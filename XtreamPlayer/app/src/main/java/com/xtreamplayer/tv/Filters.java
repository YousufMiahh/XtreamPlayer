package com.xtreamplayer.tv;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hiding what you don't watch, per account:
 *  - countries / languages: categories named "FR | …", "AR: …", "|IN| …" are hidden together
 *    by unticking FR, AR or IN in Settings;
 *  - single categories hidden by hand (hold OK / ☰ on a category).
 * Hidden categories, and the channels, movies and series in them, disappear from the category
 * lists, "All", Search, the TV guide and Recently Added. Favourites and history are left alone.
 */
public final class Filters {
    private Filters() {}

    private static final Pattern TAG = Pattern.compile(
            "^[^\\p{L}\\p{N}]*(EX-YU|[A-Za-z]{2,3}|4K|8K)\\s*(?:[|:~\\-–—»▎│\\]⎮/]+|\\s{2,})");

    /** Bumped whenever the hidden lists change, so cached results are rebuilt. */
    private static int version = 1;

    public static synchronized void changed() { version++; }

    public static synchronized int version() { return version; }

    /**
     * "UK| SPORT ʰᵉᵛᶜ" → "UK". Names with no tag but written mostly in another alphabet
     * (Arabic, Cyrillic, Hindi…) get a writing-system tag such as "#ARABIC". Null otherwise.
     */
    public static String tag(String name) {
        if (name == null) return null;
        Matcher m = TAG.matcher(name.trim());
        if (!m.find()) return script(name);
        String t = m.group(1).toUpperCase(Locale.UK);
        if (t.equals("USA")) t = "US";
        if (t.equals("GB")) t = "UK";
        return t;
    }

    /** The writing system most of the name's letters use, if it isn't the Latin alphabet. */
    static String script(String name) {
        Map<String, Integer> counts = new HashMap<>();
        int letters = 0;
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (!Character.isLetter(ch)) continue;
            letters++;
            String s = scriptOf(ch);
            if (s == null) continue;
            Integer n = counts.get(s);
            counts.put(s, n == null ? 1 : n + 1);
        }
        String best = null;
        int bestN = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestN) {
                best = e.getKey();
                bestN = e.getValue();
            }
        }
        return best != null && bestN * 10 >= letters * 4 ? best : null; // at least 40% of the letters
    }

    private static String scriptOf(char ch) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(ch);
        if (b == null) return null;
        if (b == Character.UnicodeBlock.ARABIC || b == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A
                || b == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B || "ARABIC_SUPPLEMENT".equals(b.toString())) return "#ARABIC";
        if (b == Character.UnicodeBlock.CYRILLIC || b == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY) return "#CYRILLIC";
        if (b == Character.UnicodeBlock.DEVANAGARI) return "#DEVANAGARI";
        if (b == Character.UnicodeBlock.BENGALI) return "#BENGALI";
        if (b == Character.UnicodeBlock.GURMUKHI) return "#GURMUKHI";
        if (b == Character.UnicodeBlock.GUJARATI) return "#GUJARATI";
        if (b == Character.UnicodeBlock.TAMIL) return "#TAMIL";
        if (b == Character.UnicodeBlock.TELUGU) return "#TELUGU";
        if (b == Character.UnicodeBlock.KANNADA) return "#KANNADA";
        if (b == Character.UnicodeBlock.MALAYALAM) return "#MALAYALAM";
        if (b == Character.UnicodeBlock.SINHALA) return "#SINHALA";
        if (b == Character.UnicodeBlock.THAI) return "#THAI";
        if (b == Character.UnicodeBlock.HEBREW) return "#HEBREW";
        if (b == Character.UnicodeBlock.GREEK) return "#GREEK";
        if (b == Character.UnicodeBlock.ARMENIAN) return "#ARMENIAN";
        if (b == Character.UnicodeBlock.GEORGIAN) return "#GEORGIAN";
        if (b == Character.UnicodeBlock.ETHIOPIC) return "#ETHIOPIC";
        if (b == Character.UnicodeBlock.HANGUL_SYLLABLES || b == Character.UnicodeBlock.HANGUL_JAMO
                || b == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) return "#KOREAN";
        if (b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA) return "#JAPANESE";
        if (b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) return "#CHINESE";
        return null;
    }

    private static final Map<String, String> NAMES = new HashMap<>();
    static {
        String[] p = {"UK", "United Kingdom", "US", "United States", "CA", "Canada", "IE", "Ireland", "AU", "Australia",
                "NZ", "New Zealand", "FR", "France", "DE", "Germany", "ES", "Spain", "IT", "Italy", "PT", "Portugal",
                "NL", "Netherlands", "BE", "Belgium", "PL", "Poland", "TR", "Turkey", "AR", "Arabic", "IN", "India",
                "PK", "Pakistan", "BD", "Bangladesh", "AF", "Africa", "NG", "Nigeria", "ZA", "South Africa", "GR", "Greece",
                "RO", "Romania", "AL", "Albania", "EX-YU", "Balkans", "SE", "Sweden", "NO", "Norway", "DK", "Denmark",
                "FI", "Finland", "RU", "Russia", "UA", "Ukraine", "BR", "Brazil", "MX", "Mexico", "LAT", "Latino",
                "PH", "Philippines", "CN", "China", "KR", "Korea", "JP", "Japan", "4K", "4K channels", "8K", "8K channels",
                "EN", "English", "VIP", "VIP", "PPV", "Pay-per-view", "KU", "Kurdish", "IR", "Iran", "IL", "Israel",
                "CH", "Switzerland", "AT", "Austria", "HU", "Hungary", "CZ", "Czechia", "SK", "Slovakia", "BG", "Bulgaria",
                "HR", "Croatia", "RS", "Serbia", "BA", "Bosnia", "MK", "North Macedonia", "LT", "Lithuania", "LV", "Latvia",
                "EE", "Estonia", "CY", "Cyprus", "MT", "Malta", "SA", "Saudi Arabia", "AE", "UAE", "EG", "Egypt",
                "MA", "Morocco", "DZ", "Algeria", "TN", "Tunisia", "SO", "Somalia", "ET", "Ethiopia", "KE", "Kenya",
                "GH", "Ghana", "CAR", "Caribbean", "JM", "Jamaica", "TH", "Thailand", "VN", "Vietnam", "ID", "Indonesia",
                "MY", "Malaysia", "LK", "Sri Lanka", "NP", "Nepal", "AM", "Armenia", "AZ", "Azerbaijan", "GE", "Georgia",
                "SC", "Scotland", "WAL", "Wales", "IRL", "Ireland", "SP", "Spain", "SW", "Sweden", "LU", "Luxembourg",
                "ASI", "Asia", "AFR", "Africa", "ARA", "Arabic", "HIN", "Hindi", "URD", "Urdu", "PUN", "Punjabi",
                "TAM", "Tamil", "BEN", "Bengali", "SOM", "Somali", "KID", "Kids", "XXX", "Adult", "ADU", "Adult"};
        for (int i = 0; i + 1 < p.length; i += 2) NAMES.put(p[i], p[i + 1]);
        String[] w = {"#ARABIC", "Arabic writing", "#CYRILLIC", "Cyrillic writing (Russian, Ukrainian…)",
                "#DEVANAGARI", "Hindi writing (Devanagari)", "#BENGALI", "Bengali writing", "#GURMUKHI", "Punjabi writing",
                "#GUJARATI", "Gujarati writing", "#TAMIL", "Tamil writing", "#TELUGU", "Telugu writing", "#KANNADA", "Kannada writing",
                "#MALAYALAM", "Malayalam writing", "#SINHALA", "Sinhala writing", "#THAI", "Thai writing", "#HEBREW", "Hebrew writing",
                "#GREEK", "Greek writing", "#ARMENIAN", "Armenian writing", "#GEORGIAN", "Georgian writing", "#ETHIOPIC", "Amharic writing",
                "#KOREAN", "Korean writing", "#JAPANESE", "Japanese writing", "#CHINESE", "Chinese writing"};
        for (int i = 0; i + 1 < w.length; i += 2) NAMES.put(w[i], w[i + 1]);
    }

    public static String tagName(String tag) {
        String n = NAMES.get(tag);
        return n == null ? tag : n;
    }

    // ------------------------------------------------------------ saved choices (per account)

    public static Set<String> hiddenTags(Prefs p) { return readSet(p, "hide_tags"); }

    public static void setHiddenTags(Prefs p, Set<String> tags) {
        writeSet(p, "hide_tags", tags);
        changed();
    }

    public static Set<String> hiddenCategories(Prefs p, String type) { return readSet(p, "hide_cat_" + type); }

    /** Categories you brought back even though a country / writing filter would hide them. */
    public static Set<String> alwaysShown(Prefs p, String type) { return readSet(p, "show_cat_" + type); }

    public static void setAlwaysShown(Prefs p, String type, String id, boolean on) {
        Set<String> s = alwaysShown(p, type);
        if (on) s.add(id);
        else s.remove(id);
        writeSet(p, "show_cat_" + type, s);
        changed();
    }

    public static void setCategoryHidden(Prefs p, String type, String id, boolean hidden) {
        Set<String> s = hiddenCategories(p, type);
        if (hidden) s.add(id);
        else s.remove(id);
        writeSet(p, "hide_cat_" + type, s);
        changed();
    }

    private static Set<String> readSet(Prefs p, String key) {
        Set<String> out = new HashSet<>();
        try {
            JSONArray a = new JSONArray(p.raw().getString(p.ns() + key, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void writeSet(Prefs p, String key, Set<String> s) {
        p.raw().edit().putString(p.ns() + key, new JSONArray(new ArrayList<>(s)).toString()).apply();
    }

    // ------------------------------------------------------------ applying them

    public static boolean isHidden(Item cat, Set<String> tags, Set<String> cats, Set<String> shows) {
        if (cats.contains(cat.id)) return true; // hidden by you
        if (shows.contains(cat.id)) return false; // brought back despite its country / writing
        String t = tag(cat.name);
        return t != null && tags.contains(t);
    }

    /** The categories to show. */
    public static List<Item> categories(Prefs p, String type, List<Item> all) {
        if (all == null) return null;
        Set<String> tags = hiddenTags(p), cats = hiddenCategories(p, type), shows = alwaysShown(p, type);
        List<Item> out = new ArrayList<>();
        for (Item c : all) {
            if (isHidden(c, tags, cats, shows)) continue;
            String n = p.catName(type, c.id); // renamed by you (the original name still decides country filters)
            if (n != null) {
                Item r = new Item(c.type, c.id, n);
                r.categoryId = c.categoryId;
                r.info = c.info;
                c = r;
            }
            out.add(c);
        }
        return out;
    }

    private static final Map<List<Item>, Object[]> CACHE = new IdentityHashMap<>();

    /** The channels / movies / series to show from a whole-section list (cached until something changes). */
    public static List<Item> items(XtreamApi api, Prefs p, String type, List<Item> all) { return items(api, p, type, all, false); }

    /**
     * For search: categories you hid yourself still turn up (you hid the category, not what's in
     * it), but anything from a country / language / writing you've switched off stays out.
     */
    public static List<Item> searchItems(XtreamApi api, Prefs p, String type, List<Item> all) { return items(api, p, type, all, true); }

    private static List<Item> items(XtreamApi api, Prefs p, String type, List<Item> all, boolean forSearch) {
        if (all == null) return null;
        Set<String> tags = hiddenTags(p), catIds = forSearch ? new HashSet<String>() : hiddenCategories(p, type);
        if (tags.isEmpty() && catIds.isEmpty()) return all;
        int v = version();
        synchronized (CACHE) {
            Object[] c = CACHE.get(all);
            if (c != null && (Integer) c[0] == v && p.ns().equals(c[2]) && c[3].equals(forSearch)) {
                @SuppressWarnings("unchecked") List<Item> l = (List<Item>) c[1];
                return l;
            }
        }
        Set<String> hidden = new HashSet<>();
        try {
            Set<String> shows = alwaysShown(p, type);
            for (Item cat : Repo.categories(api, type)) if (isHidden(cat, tags, catIds, shows)) hidden.add(cat.id);
        } catch (Exception ignored) {
        }
        if (hidden.isEmpty()) return all;
        List<Item> out = new ArrayList<>();
        for (Item it : all) if (it.categoryId == null || !hidden.contains(it.categoryId)) out.add(it);
        synchronized (CACHE) {
            if (CACHE.size() > 8) CACHE.clear();
            CACHE.put(all, new Object[]{v, out, p.ns(), forSearch});
        }
        return out;
    }

    /** Tags found in the provider's category names, most categories first, with counts. */
    public static LinkedHashMap<String, Integer> detectTags(XtreamApi api) {
        final Map<String, Integer> counts = new HashMap<>();
        for (String t : new String[]{Item.LIVE, Item.MOVIE, Item.SERIES}) {
            try {
                for (Item c : Repo.categories(api, t)) {
                    String tag = tag(c.name);
                    if (tag == null) continue;
                    Integer n = counts.get(tag);
                    counts.put(tag, n == null ? 1 : n + 1);
                }
            } catch (Exception ignored) {
            }
        }
        List<String> keys = new ArrayList<>(counts.keySet());
        java.util.Collections.sort(keys, new java.util.Comparator<String>() {
            public int compare(String a, String b) {
                int d = counts.get(b) - counts.get(a);
                return d != 0 ? d : a.compareTo(b);
            }
        });
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        for (String k : keys) out.put(k, counts.get(k));
        return out;
    }
}
