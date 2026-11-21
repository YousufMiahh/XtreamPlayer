package com.xtreamplayer.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cross-device sync: the part every YM Player app shares (the phone, Mac and Samsung apps do
 * exactly the same; the Samsung app's sync-core.js documents the format and has the tests).
 *
 * The online copy of an account's watch data is one encrypted blob in a row whose id is a one-way
 * hash of the login. The key that opens the blob is made from the login too and never leaves the
 * device, so the server (and whoever runs it) can't see logins or what was watched.
 *
 *   material = "ymplayer-sync-v1\n" + server + "\n" + user + "\n" + pass   (server normalised)
 *   64 bytes = PBKDF2-HMAC-SHA256(material, "ymplayer-sync-v1", 10000 rounds)
 *   row id = hex of the first 32 bytes; AES-256-GCM key = the last 32
 *   blob = base64(12-byte nonce + ciphertext + 16-byte tag)
 *
 * The document is maps of entries stamped with when they last changed ("t", ms); merging keeps the
 * newest per key; a removal is kept a while as {x:1, t}.
 */
final class SyncCore {
    private SyncCore() {}

    static final String[] MAPS = {"pos", "up", "watched", "fav", "favcat", "hidecat", "showcat", "hidetag", "recblock",
            "catname", "queue", "pic", "adelay", "settings"};
    static final long TOMBSTONE_MS = 60L * 86400000L;
    static final int HIST_KEEP = 60, HIST_SHOW = 40, ROUNDS = 10000;

    // ================================================================ keys and encryption (plain Java)

    static String normServer(String s) {
        String v = s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
        v = v.replaceFirst("^https?://", "");
        v = v.replaceFirst("/+$", "");
        v = v.replaceFirst(":(80|443)$", "");
        return v;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    /** {id (hex), key (hex)}. Slow on purpose: callers keep the result. */
    static String[] deriveKeys(String server, String user, String pass) throws Exception {
        String material = "ymplayer-sync-v1\n" + normServer(server) + "\n" + (user == null ? "" : user.trim()) + "\n"
                + (pass == null ? "" : pass.trim());
        byte[] dk = pbkdf2(material.getBytes(StandardCharsets.UTF_8), "ymplayer-sync-v1".getBytes(StandardCharsets.UTF_8), ROUNDS, 64);
        return new String[]{hex(Arrays.copyOfRange(dk, 0, 32)), hex(Arrays.copyOfRange(dk, 32, 64))};
    }

    /** PBKDF2-HMAC-SHA256 (written out: the built-in one needs Android 8). */
    static byte[] pbkdf2(byte[] password, byte[] salt, int rounds, int len) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(password, "HmacSHA256"));
        byte[] out = new byte[len];
        int blocks = (len + 31) / 32;
        for (int b = 1; b <= blocks; b++) {
            mac.update(salt);
            mac.update(new byte[]{(byte) (b >>> 24), (byte) (b >>> 16), (byte) (b >>> 8), (byte) b});
            byte[] u = mac.doFinal();
            byte[] t = u.clone();
            for (int i = 1; i < rounds; i++) {
                u = mac.doFinal(u);
                for (int j = 0; j < t.length; j++) t[j] ^= u[j];
            }
            System.arraycopy(t, 0, out, (b - 1) * 32, Math.min(32, len - (b - 1) * 32));
        }
        return out;
    }

    static String encrypt(byte[] key, String json, byte[] nonce) throws Exception {
        if (nonce == null) {
            nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
        }
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ct = c.doFinal(json.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[12 + ct.length];
        System.arraycopy(nonce, 0, all, 0, 12);
        System.arraycopy(ct, 0, all, 12, ct.length);
        return B64.encode(all);
    }

    /** The JSON text, or throws if the blob wasn't made with this key. */
    static String decrypt(byte[] key, String blob) throws Exception {
        byte[] all = B64.decode(blob);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, all, 0, 12));
        return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
    }

    static String sha256Hex(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    /** Standard base64 (android.util.Base64 isn't there in plain Java tests). */
    static final class B64 {
        private static final char[] A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

        static String encode(byte[] b) {
            StringBuilder sb = new StringBuilder((b.length + 2) / 3 * 4);
            for (int i = 0; i < b.length; i += 3) {
                int n = (b[i] & 0xff) << 16 | (i + 1 < b.length ? (b[i + 1] & 0xff) << 8 : 0) | (i + 2 < b.length ? b[i + 2] & 0xff : 0);
                sb.append(A[n >>> 18 & 63]).append(A[n >>> 12 & 63]);
                sb.append(i + 1 < b.length ? A[n >>> 6 & 63] : '=');
                sb.append(i + 2 < b.length ? A[n & 63] : '=');
            }
            return sb.toString();
        }

        static byte[] decode(String s) {
            int[] v = new int[128];
            Arrays.fill(v, -1);
            for (int i = 0; i < A.length; i++) v[A[i]] = i;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length() * 3 / 4);
            int acc = 0, bits = 0;
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch >= 128 || v[ch] < 0) continue; // '=', whitespace
                acc = acc << 6 | v[ch];
                bits += 6;
                if (bits >= 8) {
                    bits -= 8;
                    out.write(acc >>> bits & 0xff);
                }
            }
            return out.toByteArray();
        }
    }

    // ================================================================ the document

    static JSONObject emptyDoc() {
        JSONObject d = new JSONObject();
        try {
            d.put("v", 1);
            JSONObject hc = new JSONObject();
            hc.put("live", 0);
            hc.put("vod", 0);
            d.put("histclear", hc);
            d.put("hist", new JSONObject());
            for (String m : MAPS) d.put(m, new JSONObject());
        } catch (Exception ignored) {
        }
        return d;
    }

    /** A copy with every map present. */
    static JSONObject normDoc(JSONObject d) {
        JSONObject out = emptyDoc();
        if (d == null) return out;
        try {
            List<String> all = new ArrayList<>(Arrays.asList(MAPS));
            all.add("hist");
            for (String m : all) {
                JSONObject src = d.optJSONObject(m);
                if (src == null) continue;
                JSONObject dst = out.getJSONObject(m);
                for (String k : keys(src)) {
                    JSONObject e = src.optJSONObject(k);
                    if (e != null) dst.put(k, e);
                }
            }
            JSONObject hc = d.optJSONObject("histclear");
            if (hc != null) {
                out.getJSONObject("histclear").put("live", hc.optLong("live", 0));
                out.getJSONObject("histclear").put("vod", hc.optLong("vod", 0));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static List<String> keys(JSONObject o) {
        List<String> out = new ArrayList<>();
        if (o == null) return out;
        for (Iterator<String> i = o.keys(); i.hasNext(); ) out.add(i.next());
        return out;
    }

    private static String histKind(String k) { return k.startsWith("live:") ? "live" : "vod"; }

    private static JSONObject strip(JSONObject e) throws Exception {
        JSONObject o = new JSONObject();
        for (String k : keys(e)) if (!k.equals("t") && !k.equals("x")) o.put(k, e.get(k));
        return o;
    }

    private static boolean same(String m, JSONObject a, JSONObject b) {
        switch (m) {
            case "pos": return a.optLong("p", 0) == b.optLong("p", 0);
            case "catname": return a.optString("n", "").equals(b.optString("n", ""));
            case "pic": return a.optLong("m", 0) == b.optLong("m", 0);
            case "adelay": return a.optLong("ms", 0) == b.optLong("ms", 0);
            case "settings": return String.valueOf(a.opt("v")).equals(String.valueOf(b.opt("v")));
            default: return true;
        }
    }

    private static boolean ordered(String m) { return m.equals("fav") || m.equals("queue"); }

    /**
     * This device's data as a document: unchanged since the last sync keeps its time, changed gets
     * now, gone becomes a removal. maps: the maps this app keeps (others pass through).
     */
    static JSONObject stamp(JSONObject state, JSONObject shadowIn, long now, List<String> maps) throws Exception {
        JSONObject shadow = normDoc(shadowIn);
        JSONObject out = normDoc(shadow);
        for (String m : maps) {
            final JSONObject cur = state.optJSONObject(m) != null ? state.getJSONObject(m) : new JSONObject();
            JSONObject old = shadow.getJSONObject(m);
            JSONObject res = new JSONObject();
            long next = 0;
            List<String> order = keys(cur);
            Collections.sort(order, (a, b) -> Long.compare(cur.optJSONObject(a).optLong("o", 0), cur.optJSONObject(b).optLong("o", 0)));
            for (String k : order) {
                JSONObject e = cur.getJSONObject(k);
                JSONObject s = old.optJSONObject(k);
                if (s != null && !s.has("x") && same(m, s, e)) {
                    res.put(k, s);
                    continue;
                }
                JSONObject n = strip(e);
                n.put("t", now);
                if (ordered(m)) n.put("o", now + next++);
                res.put(k, n);
            }
            for (String k : keys(old)) {
                if (res.has(k)) continue;
                JSONObject s = old.getJSONObject(k);
                if (s.has("x") || m.equals("settings")) res.put(k, s); // settings: one this app doesn't have
                else res.put(k, new JSONObject().put("x", 1).put("t", now));
            }
            out.put(m, res);
        }
        final JSONObject sh = state.optJSONObject("hist");
        if (sh == null) return out;
        JSONObject hist = new JSONObject(shadow.getJSONObject("hist").toString());
        JSONObject oldHist = shadow.getJSONObject("hist");
        JSONObject clear = shadow.getJSONObject("histclear");
        for (String kind : new String[]{"live", "vod"}) {
            List<String> mine = new ArrayList<>();
            for (String k : keys(sh)) if (histKind(k).equals(kind)) mine.add(k);
            Collections.sort(mine, (a, b) -> Long.compare(sh.optJSONObject(b).optLong("o", 0), sh.optJSONObject(a).optLong("o", 0)));
            Long prev = null;
            for (String k : mine) {
                JSONObject s = oldHist.optJSONObject(k);
                JSONObject e;
                if (s != null && !s.has("x") && (prev == null || s.optLong("t") > prev)) e = s;
                else {
                    long o = sh.getJSONObject(k).optLong("o", 0);
                    long t = prev == null ? now - o : Math.max(prev + 1, now - o);
                    e = new JSONObject().put("i", sh.getJSONObject(k).opt("i")).put("t", t);
                }
                hist.put(k, e);
                prev = e.optLong("t");
            }
            if (mine.isEmpty()) {
                boolean had = false;
                for (String k : keys(oldHist)) {
                    JSONObject s = oldHist.getJSONObject(k);
                    if (histKind(k).equals(kind) && !s.has("x") && s.optLong("t") > clear.optLong(kind, 0)) had = true;
                }
                if (had) out.getJSONObject("histclear").put(kind, now);
            }
        }
        out.put("hist", hist);
        return out;
    }

    private static boolean newer(JSONObject a, JSONObject b) {
        long ta = a.optLong("t"), tb = b.optLong("t");
        if (ta != tb) return ta > tb;
        return a.toString().compareTo(b.toString()) > 0;
    }

    /** [merged document, Boolean dirty]: dirty = the result differs from remote (save it online). */
    static Object[] merge(JSONObject localIn, JSONObject remoteIn, long now) throws Exception {
        JSONObject local = normDoc(localIn), remote = normDoc(remoteIn), out = emptyDoc();
        boolean dirty = false;
        List<String> all = new ArrayList<>(Arrays.asList(MAPS));
        all.add("hist");
        for (String m : all) {
            JSONObject a = local.getJSONObject(m), b = remote.getJSONObject(m), r = new JSONObject();
            Set<String> ks = new LinkedHashSet<>(keys(a));
            ks.addAll(keys(b));
            for (String k : ks) {
                JSONObject ea = a.optJSONObject(k), eb = b.optJSONObject(k);
                if (eb == null) { r.put(k, ea); dirty = true; continue; }
                if (ea == null) { r.put(k, eb); continue; }
                if (ea.optLong("t") != eb.optLong("t") && newer(ea, eb)) { r.put(k, ea); dirty = true; } else r.put(k, eb);
            }
            out.put(m, r);
        }
        JSONObject hc = out.getJSONObject("histclear");
        for (String kind : new String[]{"live", "vod"}) {
            long l = local.getJSONObject("histclear").optLong(kind), rm = remote.getJSONObject("histclear").optLong(kind);
            hc.put(kind, Math.max(l, rm));
            if (Math.max(l, rm) != rm) dirty = true;
        }
        for (String m : MAPS) {
            JSONObject mm = out.getJSONObject(m);
            for (String k : keys(mm)) {
                JSONObject e = mm.getJSONObject(k);
                if (e.has("x") && e.optLong("t") < now - TOMBSTONE_MS) {
                    mm.remove(k);
                    if (remote.getJSONObject(m).has(k)) dirty = true;
                }
            }
        }
        final JSONObject h = out.getJSONObject("hist");
        for (String kind : new String[]{"live", "vod"}) {
            List<String> ks = new ArrayList<>();
            for (String k : keys(h)) if (histKind(k).equals(kind)) ks.add(k);
            Collections.sort(ks, (x, y) -> Long.compare(h.optJSONObject(y).optLong("t"), h.optJSONObject(x).optLong("t")));
            for (int idx = 0; idx < ks.size(); idx++) {
                String k = ks.get(idx);
                JSONObject e = h.getJSONObject(k);
                if (e.has("x") || e.optLong("t") <= hc.optLong(kind) || idx >= HIST_KEEP) {
                    h.remove(k);
                    if (remote.getJSONObject("hist").has(k)) dirty = true;
                }
            }
        }
        return new Object[]{out, dirty};
    }

    /** What the device should hold: maps without times (fav / queue / hist o = 0, 1, 2…). */
    static JSONObject toState(JSONObject docIn) throws Exception {
        JSONObject doc = normDoc(docIn);
        JSONObject st = new JSONObject();
        for (String m : MAPS) {
            final JSONObject src = doc.getJSONObject(m);
            List<String> ks = new ArrayList<>();
            for (String k : keys(src)) if (!src.getJSONObject(k).has("x")) ks.add(k);
            if (ordered(m)) Collections.sort(ks, (a, b) -> Long.compare(src.optJSONObject(a).optLong("o", 0), src.optJSONObject(b).optLong("o", 0)));
            JSONObject r = new JSONObject();
            for (int i = 0; i < ks.size(); i++) {
                JSONObject e = strip(src.getJSONObject(ks.get(i)));
                if (ordered(m)) e.put("o", i);
                r.put(ks.get(i), e);
            }
            st.put(m, r);
        }
        JSONObject hist = new JSONObject();
        final JSONObject h = doc.getJSONObject("hist");
        for (String kind : new String[]{"live", "vod"}) {
            long clear = doc.getJSONObject("histclear").optLong(kind);
            List<String> ks = new ArrayList<>();
            for (String k : keys(h)) {
                JSONObject e = h.getJSONObject(k);
                if (histKind(k).equals(kind) && !e.has("x") && e.optLong("t") > clear) ks.add(k);
            }
            Collections.sort(ks, (a, b) -> Long.compare(h.optJSONObject(b).optLong("t"), h.optJSONObject(a).optLong("t")));
            for (int i = 0; i < ks.size() && i < HIST_SHOW; i++) {
                hist.put(ks.get(i), new JSONObject().put("o", i).put("i", h.getJSONObject(ks.get(i)).opt("i")));
            }
        }
        st.put("hist", hist);
        return st;
    }

    /** Same contents for map m (order counts for fav / queue / hist; duration for pos). */
    static boolean sameMap(String m, JSONObject a, JSONObject b) {
        if (a == null) a = new JSONObject();
        if (b == null) b = new JSONObject();
        if (a.length() != b.length()) return false;
        for (String k : keys(a)) {
            JSONObject ea = a.optJSONObject(k), eb = b.optJSONObject(k);
            if (eb == null || ea == null) return false;
            if (!same(m, ea, eb)) return false;
            if ((ordered(m) || m.equals("hist")) && ea.optLong("o", 0) != eb.optLong("o", 0)) return false;
            if (m.equals("pos") && ea.optLong("d", 0) != eb.optLong("d", 0)) return false;
        }
        return true;
    }

    static JSONArray arr(String s) {
        try {
            return new JSONArray(s == null ? "[]" : s);
        } catch (Exception e) {
            return new JSONArray();
        }
    }
}
