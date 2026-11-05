package com.xtreamplayer.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Series details: poster + plot on top, seasons and episodes below. */
public class SeriesActivity extends Activity {
    private Prefs prefs;
    private XtreamApi api;
    private Item series;

    private ImageView poster;
    private TextView title, meta, plot;
    private ListView seasonList, episodeList;
    private Adapters.TextRows seasonAdapter, episodeAdapter;
    private final TreeMap<Integer, List<Item>> seasons = new TreeMap<>();
    private int activeSeason = -1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        api = new XtreamApi(prefs);
        try {
            series = Item.fromJson(new JSONObject(getIntent().getStringExtra("series")));
        } catch (Exception e) {
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 24);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(Ui.rounded(this, Ui.CARD, 0, 10));
        poster.setClipToOutline(true);
        top.addView(poster, new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 225)));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(Ui.dp(this, 24), 0, 0, 0);
        title = Ui.title(this, series.name, 30);
        meta = Ui.text(this, series.info == null ? "" : series.info, 15, Ui.MUTED);
        meta.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        plot = Ui.text(this, series.plot == null ? "" : series.plot, 16, Ui.TEXT);
        plot.setMaxLines(6);
        plot.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(title);
        txt.addView(meta);
        txt.addView(plot);
        top.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(top);

        LinearLayout lists = new LinearLayout(this);
        lists.setOrientation(LinearLayout.HORIZONTAL);
        lists.setPadding(0, Ui.dp(this, 18), 0, 0);
        seasonList = makeList();
        seasonAdapter = new Adapters.TextRows(this);
        seasonList.setAdapter(seasonAdapter);
        episodeList = makeList();
        episodeAdapter = new Adapters.TextRows(this);
        episodeList.setAdapter(episodeAdapter);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        sl.rightMargin = Ui.dp(this, 16);
        lists.addView(seasonList, sl);
        lists.addView(episodeList, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3));
        root.addView(lists, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        ImageLoader.load(poster, series.icon, 300, 450);
        wire();
        load();
    }

    private ListView makeList() {
        ListView l = new ListView(this);
        l.setSelector(Ui.listSelector(this));
        l.setDivider(null);
        l.setBackground(Ui.rounded(this, Ui.PANEL, 0, 14));
        int p = Ui.dp(this, 8);
        l.setPadding(p, p, p, p);
        l.setClipToPadding(false);
        return l;
    }

    private void wire() {
        seasonList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { showSeason(pos); }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        seasonList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                showSeason(pos);
                episodeList.requestFocus();
            }
        });
        episodeList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                Item e = episodeAdapter.getItem(pos);
                plot.setText(e.plot != null ? e.plot : (series.plot == null ? "" : series.plot));
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        episodeList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Queue.set(episodeAdapter.items());
                startActivity(new Intent(SeriesActivity.this, PlayerActivity.class).putExtra("index", pos));
            }
        });
    }

    private void load() {
        meta.setText("Loading episodes…");
        Async.run(new Async.Work<JSONObject>() {
            public JSONObject run() throws Exception { return api.seriesInfo(series.id); }
        }, new Async.Done<JSONObject>() {
            public void done(JSONObject r, Exception e) {
                if (isFinishing()) return;
                if (e != null || r == null) {
                    meta.setText("Couldn't load episodes: " + Async.message(e));
                    return;
                }
                parse(r);
            }
        });
    }

    private void parse(JSONObject r) {
        JSONObject info = r.optJSONObject("info");
        if (info != null) {
            String p = info.optString("plot", "");
            if (!p.isEmpty() && !"null".equals(p)) {
                series.plot = p;
                plot.setText(p);
            }
            String m = XtreamApi.joinNonEmpty(info.optString("genre", null),
                    info.optString("releaseDate", info.optString("releasedate", null)),
                    XtreamApi.rating(info.optString("rating", null)));
            meta.setText(m == null ? "" : m);
            String cover = info.optString("cover", "");
            if (series.icon == null && !cover.isEmpty()) ImageLoader.load(poster, cover, 300, 450);
        } else {
            meta.setText(series.info == null ? "" : series.info);
        }

        Object eps = r.opt("episodes");
        if (eps instanceof JSONObject) {
            JSONObject o = (JSONObject) eps;
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                addEpisodes(o.optJSONArray(k), parseInt(k, 0));
            }
        } else if (eps instanceof JSONArray) {
            JSONArray a = (JSONArray) eps;
            for (int i = 0; i < a.length(); i++) {
                Object x = a.opt(i);
                if (x instanceof JSONArray) addEpisodes((JSONArray) x, i + 1);
                else if (x instanceof JSONObject) {
                    JSONArray one = new JSONArray();
                    one.put(x);
                    addEpisodes(one, 0);
                }
            }
        }

        List<Item> rows = new ArrayList<>();
        for (Map.Entry<Integer, List<Item>> s : seasons.entrySet()) {
            Item row = new Item(Item.CATEGORY, String.valueOf(s.getKey()), s.getKey() == 0 ? "Specials" : "Season " + s.getKey());
            row.info = s.getValue().size() + " episodes";
            rows.add(row);
        }
        seasonAdapter.set(rows);
        if (rows.isEmpty()) {
            Toast.makeText(this, "No episodes found for this series", Toast.LENGTH_LONG).show();
            return;
        }
        seasonList.requestFocus();
        seasonList.setSelection(0);
        showSeason(0);
    }

    private void addEpisodes(JSONArray arr, int fallbackSeason) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            int season = e.has("season") ? e.optInt("season", fallbackSeason) : fallbackSeason;
            Item it = new Item(Item.EPISODE, e.optString("id"), "");
            String num = e.optString("episode_num", String.valueOf(i + 1));
            String t = e.optString("title", "");
            it.num = num;
            it.name = "E" + num + "  ·  " + (t.isEmpty() ? series.name : t);
            it.ext = e.optString("container_extension", "mp4");
            JSONObject ei = e.optJSONObject("info");
            if (ei != null) {
                String d = ei.optString("duration", "");
                it.duration = d.isEmpty() || "null".equals(d) ? null : d;
                it.info = it.duration;
                String p = ei.optString("plot", "");
                it.plot = p.isEmpty() || "null".equals(p) ? null : p;
                String img = ei.optString("movie_image", "");
                it.icon = img.isEmpty() ? null : img;
            }
            if (it.id.isEmpty()) continue;
            List<Item> list = seasons.get(season);
            if (list == null) {
                list = new ArrayList<>();
                seasons.put(season, list);
            }
            list.add(it);
        }
    }

    private void showSeason(int pos) {
        if (pos < 0 || pos >= seasonAdapter.getCount() || pos == activeSeason) return;
        activeSeason = pos;
        seasonAdapter.active = pos;
        seasonAdapter.notifyDataSetChanged();
        int key = parseInt(seasonAdapter.getItem(pos).id, 0);
        List<Item> eps = seasons.get(key);
        markResume(eps);
        episodeAdapter.set(eps);
    }

    private void markResume(List<Item> eps) {
        if (eps == null) return;
        for (Item e : eps) {
            String base = e.duration;
            long pos = prefs.position(e);
            e.info = pos > 0 ? XtreamApi.joinNonEmpty(base, "▶ resume from " + fmt(pos)) : base;
        }
    }

    static String fmt(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(java.util.Locale.UK, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(java.util.Locale.UK, "%d:%02d", s / 60, s % 60);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (episodeAdapter != null) {
            markResume(episodeAdapter.items());
            episodeAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            boolean now = prefs.toggleFav(series);
            Toast.makeText(this, now ? "Series added to favourites" : "Series removed from favourites", Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
