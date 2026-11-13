package com.xtreamplayer.tv;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Series page: backdrop and details, season pills, and a shelf of episodes. */
public class SeriesActivity extends Activity {
    private Prefs prefs;
    private XtreamApi api;
    private Item series;

    private ImageView backdrop;
    private TextView title, meta, plot, epLabel;
    private LinearLayout buttons, seasonRow, episodeRow;
    private HorizontalScrollView episodeScroll;
    private Button playBtn, favBtn;
    private String trailer;
    private final TreeMap<Integer, List<Item>> seasons = new TreeMap<>();
    private int activeSeason = Integer.MIN_VALUE;
    private final List<TextView> seasonPills = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        prefs = new Prefs(this);
        api = new XtreamApi(prefs);
        try {
            series = Item.fromJson(new JSONObject(getIntent().getStringExtra("series")));
        } catch (Exception e) {
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0f);
        root.addView(backdrop, match());
        View sl = new View(this);
        sl.setBackground(Ui.scrimLeft());
        root.addView(sl, match());
        View sb = new View(this);
        sb.setBackground(Ui.pageScrimBottom());
        root.addView(sb, match());

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.setPadding(0, Ui.dp(this, 56), 0, Ui.dp(this, 28));
        root.addView(page, match());

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.BOTTOM);
        info.setClipChildren(false);
        info.setClipToPadding(false);
        info.setPadding(Ui.dp(this, 80), 0, 0, 0);
        title = Ui.lines(Ui.title(this, series.name, 44), 2);
        meta = Ui.oneLine(Ui.medium(this, series.info == null ? "" : series.info, 18, Ui.TEXT2));
        meta.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 14));
        epLabel = Ui.oneLine(Ui.medium(this, "", 16, Ui.TEXT));
        epLabel.setTypeface(Ui.semibold());
        epLabel.setVisibility(View.GONE);
        epLabel.setPadding(0, 0, 0, Ui.dp(this, 6));
        plot = Ui.lines(Ui.text(this, series.plot == null ? "" : series.plot, 18, Ui.TEXT), 3);
        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setClipChildren(false);
        buttons.setClipToPadding(false);
        buttons.setPadding(0, Ui.dp(this, 26), 0, 0);
        info.addView(title);
        info.addView(meta);
        info.addView(epLabel);
        info.addView(plot);
        info.addView(buttons);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(this, 960), 0, 1);
        page.addView(info, ilp);

        HorizontalScrollView seasonScroll = new HorizontalScrollView(this);
        seasonScroll.setHorizontalScrollBarEnabled(false);
        seasonScroll.setClipChildren(false);
        seasonScroll.setClipToPadding(false);
        seasonRow = new LinearLayout(this);
        seasonRow.setOrientation(LinearLayout.HORIZONTAL);
        seasonRow.setClipChildren(false);
        seasonRow.setPadding(Ui.dp(this, 76), Ui.dp(this, 26), Ui.dp(this, 64), Ui.dp(this, 4));
        seasonScroll.addView(seasonRow);
        page.addView(seasonScroll);

        episodeScroll = new HorizontalScrollView(this);
        episodeScroll.setHorizontalScrollBarEnabled(false);
        episodeScroll.setClipChildren(false);
        episodeScroll.setClipToPadding(false);
        episodeRow = new LinearLayout(this);
        episodeRow.setOrientation(LinearLayout.HORIZONTAL);
        episodeRow.setClipChildren(false);
        episodeRow.setClipToPadding(false);
        episodeRow.setPadding(Ui.dp(this, 80), Ui.dp(this, 20), Ui.dp(this, 64), Ui.dp(this, 16));
        episodeScroll.addView(episodeRow);
        page.addView(episodeScroll);

        setContentView(root);
        loadBackdrop(series.icon);
        buildButtons();
        load();
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void loadBackdrop(String url) {
        if (url == null) return;
        ImageLoader.load(backdrop, url, 1280, 720);
        backdrop.animate().alpha(1f).setDuration(500).start();
    }

    // ------------------------------------------------------------ buttons

    private Item lastWatched() {
        for (Item h : prefs.history(false)) {
            if (Item.EPISODE.equals(h.type) && series.id.equals(h.parentId)) return h;
        }
        return null;
    }

    private void buildButtons() {
        boolean hadFocus = playBtn != null && playBtn.hasFocus();
        buttons.removeAllViews();
        final Item last = lastWatched();
        String label = "▶  Play";
        if (last != null) {
            Item match = find(last.id);
            label = "▶  " + (prefs.position(last) > 0 ? "Resume " : "Play ") + (match != null ? shortName(match) : "");
        }
        playBtn = Ui.button(this, label.trim());
        playBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Item target = last != null ? find(last.id) : null;
                if (target == null && !seasons.isEmpty()) target = seasons.firstEntry().getValue().get(0);
                if (target != null) playEpisode(target);
            }
        });
        addButton(playBtn);
        if (trailer != null) {
            Button tr = Ui.button(this, "▶  Trailer");
            tr.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { TrailerActivity.open(SeriesActivity.this, trailer); }
            });
            addButton(tr);
        }
        favBtn = Ui.button(this, "");
        favBtn.setText(prefs.isFav(series) ? "♥  In Favourites" : "♡  Favourite");
        favBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs.toggleFav(series);
                favBtn.setText(prefs.isFav(series) ? "♥  In Favourites" : "♡  Favourite");
            }
        });
        addButton(favBtn);
        if (hadFocus || getCurrentFocus() == null) playBtn.requestFocus();
    }

    private void addButton(Button b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 18);
        buttons.addView(b, lp);
    }

    private String shortName(Item ep) {
        for (Map.Entry<Integer, List<Item>> s : seasons.entrySet()) {
            if (s.getValue().contains(ep)) return "S" + s.getKey() + " · E" + ep.num;
        }
        return "E" + ep.num;
    }

    private Item find(String episodeId) {
        for (List<Item> l : seasons.values()) for (Item e : l) if (e.id.equals(episodeId)) return e;
        return null;
    }

    private void playEpisode(Item ep) {
        for (List<Item> l : seasons.values()) {
            int i = l.indexOf(ep);
            if (i >= 0) {
                Open.play(this, l, i);
                return;
            }
        }
    }

    // ------------------------------------------------------------ data

    private void load() {
        meta.setText("Loading episodes…");
        Async.run(new Async.Work<JSONObject>() {
            public JSONObject run() throws Exception { return api.seriesInfo(series.id); }
        }, new Async.Done<JSONObject>() {
            public void done(JSONObject r, Exception e) {
                if (isFinishing()) return;
                if (e != null || r == null) {
                    meta.setText("Couldn't load episodes — " + Async.message(e));
                    return;
                }
                parse(r);
            }
        });
    }

    /** The series' TMDB id when the provider gives one (for Skip Intro). */
    private String seriesTmdb;

    private void parse(JSONObject r) {
        JSONObject info = r.optJSONObject("info");
        if (info != null) {
            seriesTmdb = MovieActivity.str(info, "tmdb", MovieActivity.str(info, "tmdb_id", null));
            String p = MovieActivity.str(info, "plot", null);
            if (p != null) {
                series.plot = p;
                plot.setText(p);
            }
            String year = MovieActivity.str(info, "releaseDate", MovieActivity.str(info, "releasedate", null));
            if (year != null && year.length() >= 4) year = year.substring(0, 4);
            String m = XtreamApi.joinNonEmpty(year, MovieActivity.str(info, "genre", null),
                    XtreamApi.rating(MovieActivity.str(info, "rating", null)));
            meta.setText(m == null ? "" : m);
            JSONArray bd = info.optJSONArray("backdrop_path");
            String bdu = bd != null && bd.length() > 0 ? bd.optString(0, null) : null;
            if (bdu != null && !bdu.isEmpty()) loadBackdrop(bdu);
            else if (series.icon == null) loadBackdrop(MovieActivity.str(info, "cover", null));
            if (series.icon == null) series.icon = MovieActivity.str(info, "cover", null);
            trailer = TrailerActivity.youtubeId(MovieActivity.str(info, "youtube_trailer", MovieActivity.str(info, "trailer", null)));
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

        seasonRow.removeAllViews();
        seasonPills.clear();
        if (seasons.isEmpty()) {
            meta.setText("No episodes found for this series");
            return;
        }
        for (final Map.Entry<Integer, List<Item>> s : seasons.entrySet()) {
            final TextView pill = Ui.medium(this, s.getKey() == 0 ? "Specials" : "Season " + s.getKey(), 17, Ui.TEXT);
            pill.setFocusable(true);
            int px = Ui.dp(this, 22), py = Ui.dp(this, 11);
            pill.setPadding(px, py, px, py);
            pill.setTag(s.getKey());
            pill.setId(View.generateViewId());
            pill.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean has) {
                    Ui.animateFocus(v, has, 1.06f);
                    if (has) showSeason(s.getKey());
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 10);
            seasonRow.addView(pill, lp);
            seasonPills.add(pill);
        }
        Item last = lastWatched();
        Item lastEp = last == null ? null : find(last.id);
        int startSeason = seasons.firstKey();
        if (lastEp != null) {
            for (Map.Entry<Integer, List<Item>> s : seasons.entrySet()) if (s.getValue().contains(lastEp)) startSeason = s.getKey();
        }
        showSeason(startSeason);
        buildButtons();
    }

    private void addEpisodes(JSONArray arr, int fallbackSeason) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            int season = e.has("season") ? e.optInt("season", fallbackSeason) : fallbackSeason;
            Item it = new Item(Item.EPISODE, e.optString("id"), "");
            String num = MovieActivity.str(e, "episode_num", String.valueOf(i + 1));
            String t = MovieActivity.str(e, "title", series.name);
            it.num = num;
            it.name = t;
            it.ext = MovieActivity.str(e, "container_extension", "mp4");
            it.parentId = series.id;
            it.parentName = series.name;
            it.parentIcon = series.icon;
            it.season = season;
            it.tmdb = seriesTmdb;
            JSONObject ei = e.optJSONObject("info");
            if (ei != null) {
                String d = MovieActivity.str(ei, "duration", null);
                if (d != null && d.length() == 8) d = MovieActivity.shortDuration(d);
                it.duration = d;
                it.plot = MovieActivity.str(ei, "plot", null);
                it.icon = MovieActivity.str(ei, "movie_image", null);
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

    private void showSeason(int key) {
        if (key == activeSeason) return;
        activeSeason = key;
        for (TextView p : seasonPills) {
            boolean on = Integer.valueOf(key).equals(p.getTag());
            p.setBackground(Ui.pill(this, on ? Ui.SURFACE_HI : 0, 22));
            p.setTextColor(Ui.focusText(on ? Ui.TEXT : Ui.TEXT2));
            p.setTypeface(on ? Ui.semibold() : Ui.medium());
        }
        buildEpisodes();
    }

    private void buildEpisodes() {
        episodeRow.removeAllViews();
        final List<Item> eps = seasons.get(activeSeason);
        if (eps == null) return;
        int upId = View.NO_ID;
        for (TextView p : seasonPills) if (Integer.valueOf(activeSeason).equals(p.getTag())) upId = p.getId();
        updatePillLabels();
        for (final Item ep : eps) {
            long pos = prefs.position(ep), dur = prefs.duration(ep);
            boolean watched = prefs.isWatched(ep);
            String sub = watched ? "✓ Watched" : pos > 0 && dur > 0 ? fmt(Math.max(0, dur - pos)) + " left" : ep.duration;
            String img = ep.icon != null ? ep.icon : series.icon;
            final LinearLayout card = Cards.card(this, Cards.Shape.LANDSCAPE, img, ep.num + ".  " + ep.name, sub,
                    dur > 0 ? pos / (float) dur : 0);
            if (watched) Cards.markWatched(card);
            card.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    episodeMenu(ep, eps, card);
                    return true;
                }
            });
            card.setOnKeyListener(new View.OnKeyListener() {
                public boolean onKey(View v, int code, KeyEvent e) {
                    if (code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_DOWN) {
                        episodeMenu(ep, eps, card);
                        return true;
                    }
                    return false;
                }
            });
            card.setNextFocusUpId(upId);
            final View.OnFocusChangeListener base = card.getOnFocusChangeListener();
            card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean has) {
                    base.onFocusChange(v, has);
                    if (has) {
                        epLabel.setText("Episode " + ep.num + "  ·  " + ep.name);
                        epLabel.setVisibility(View.VISIBLE);
                        plot.setText(ep.plot != null ? ep.plot : (series.plot == null ? "" : series.plot));
                    }
                }
            });
            card.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { Open.play(SeriesActivity.this, eps, eps.indexOf(ep)); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 30);
            episodeRow.addView(card, lp);
        }
        episodeScroll.scrollTo(0, 0);
    }

    /** ☰ / hold OK on an episode: Add to Queue, Mark as Watched, Mark Season as Watched… */
    private void episodeMenu(Item ep, List<Item> season, final View card) {
        final int idx = episodeRow.indexOfChild(card);
        ItemMenu.show(this, prefs, ep, season, new Runnable() {
            public void run() {
                buildEpisodes();
                buildButtons();
                if (idx >= 0 && idx < episodeRow.getChildCount()) episodeRow.getChildAt(idx).requestFocus();
            }
        });
    }

    /** "Season 2  ✓" when it's all watched, "Season 2  3/10" part way through. */
    private void updatePillLabels() {
        for (TextView p : seasonPills) {
            int key = (Integer) p.getTag();
            List<Item> l = seasons.get(key);
            String base = key == 0 ? "Specials" : "Season " + key;
            int n = l == null ? 0 : prefs.watchedCount(l);
            p.setText(l == null || n == 0 ? base : n == l.size() ? base + "  ✓" : base + "  " + n + "/" + l.size());
        }
    }

    static String fmt(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.UK, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.UK, "%d:%02d", s / 60, s % 60);
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
        if (!seasons.isEmpty()) {
            View f = getCurrentFocus();
            boolean inEpisodes = f != null && f.getParent() == episodeRow;
            int idx = inEpisodes ? episodeRow.indexOfChild(f) : -1;
            buildEpisodes();
            buildButtons();
            if (idx >= 0 && idx < episodeRow.getChildCount()) episodeRow.getChildAt(idx).requestFocus();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            Open.toggleFav(this, prefs, series);
            if (favBtn != null) favBtn.setText(prefs.isFav(series) ? "♥  In Favourites" : "♡  Favourite");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
