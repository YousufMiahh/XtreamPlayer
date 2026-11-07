package com.xtreamplayer.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Search ranking. Every result gets a score, best first:
 *   exact name  >  name starts with it  >  your words together and in order
 *   >  all your words at the start of words  >  contained somewhere.
 * Provider tags in front of names ("UK:", "4K |", "EN -") are ignored, and shorter names win
 * ties, so "premier sports" finds "UK: Premier Sports 1" before "Sky Sports Premier League".
 */
public final class Match {
    private Match() {}

    /** A search, prepared once and used against every title. */
    public static final class Query {
        final String whole;      // "premiersports"
        final String[] words;    // ["premier", "sports"]

        public Query(String text) {
            List<String> w = new ArrayList<>();
            for (String part : Item.fold(text == null ? "" : text).split("[^\\p{L}\\p{N}]+")) {
                String s = Item.squash(part);
                if (!s.isEmpty()) w.add(s);
            }
            words = w.toArray(new String[0]);
            StringBuilder b = new StringBuilder();
            for (String s : words) b.append(s);
            whole = b.toString();
        }

        public boolean usable() { return whole.length() >= 2; }
    }

    /** @return 0 for no match, higher is better. */
    public static int score(Item it, Query q) {
        if (!q.usable()) return 0;
        String c = it.compact();
        // Quick rejection: every word must appear somewhere
        for (String w : q.words) if (!c.contains(w)) return 0;
        String[] t = it.words();
        int start = tagLength(t);
        String core = joinFrom(t, start), full = start == 0 ? core : joinFrom(t, 0);
        if (core.equals(q.whole) || full.equals(q.whole)) return 1000;
        if (core.startsWith(q.whole) || full.startsWith(q.whole)) return 900; // with or without the "UK:" tag
        int phrase = phraseAt(t, start, q.words);
        if (phrase < 0 && start > 0) phrase = phraseAt(t, 0, q.words);
        if (phrase >= 0) return 800 - Math.min(phrase, 50); // earlier in the name is better
        if (allWordStarts(t, q.words)) return 600;
        if (c.contains(q.whole)) return 400;
        return 200;
    }

    /** Sorts by score (best first), then shorter names, keeping the provider's order otherwise. */
    public static List<Item> rank(List<Item> all, String text, int max, java.util.concurrent.atomic.AtomicBoolean cancelled) {
        final Query q = new Query(text);
        List<Item> hits = new ArrayList<>();
        final java.util.HashMap<Item, Integer> scores = new java.util.HashMap<>();
        if (!q.usable() || all == null) return hits;
        int n = 0;
        for (Item it : all) {
            if ((++n & 1023) == 0 && cancelled != null && cancelled.get()) return null;
            int s = score(it, q);
            if (s <= 0) continue;
            hits.add(it);
            scores.put(it, s);
        }
        Collections.sort(hits, new Comparator<Item>() {
            public int compare(Item a, Item b) {
                int d = scores.get(b) - scores.get(a);
                if (d != 0) return d;
                return a.compact().length() - b.compact().length();
            }
        });
        return hits.size() > max ? new ArrayList<>(hits.subList(0, max)) : hits;
    }

    /** Leading provider tags: "UK", "4K", "EN", "HD"… (short tokens before the real name, max 2). */
    static int tagLength(String[] t) {
        int i = 0;
        while (i < t.length - 1 && i < 2 && t[i].length() <= 3 && isTag(t[i])) i++;
        return i;
    }

    private static boolean isTag(String w) {
        switch (w) {
            case "uk": case "us": case "usa": case "ca": case "au": case "ie": case "en": case "fr": case "de":
            case "es": case "it": case "nl": case "pl": case "pt": case "tr": case "ar": case "in": case "4k":
            case "8k": case "hd": case "fhd": case "uhd": case "sd": case "vip": case "eu": case "nz": case "za":
            case "multi": case "sub": case "dub":
                return true;
            default:
                return false;
        }
    }

    private static String joinFrom(String[] t, int from) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < t.length; i++) b.append(t[i]);
        return b.toString();
    }

    /** Index of the first word where the query's words appear together, in order (last may be partial). */
    private static int phraseAt(String[] t, int from, String[] q) {
        outer:
        for (int i = from; i + q.length <= t.length; i++) {
            for (int k = 0; k < q.length; k++) {
                boolean last = k == q.length - 1;
                if (last ? !t[i + k].startsWith(q[k]) : !t[i + k].equals(q[k])) continue outer;
            }
            return i - from;
        }
        // "spiderman" typed as one word for "Spider-Man"
        if (q.length == 1) {
            StringBuilder b = new StringBuilder();
            for (int i = from; i < t.length; i++) {
                b.setLength(0);
                for (int j = i; j < t.length && b.length() < q[0].length(); j++) b.append(t[j]);
                if (b.toString().startsWith(q[0])) return i - from;
            }
        }
        return -1;
    }

    private static boolean allWordStarts(String[] t, String[] q) {
        for (String w : q) {
            boolean found = false;
            for (String x : t) if (x.startsWith(w)) { found = true; break; }
            if (!found) return false;
        }
        return true;
    }
}
