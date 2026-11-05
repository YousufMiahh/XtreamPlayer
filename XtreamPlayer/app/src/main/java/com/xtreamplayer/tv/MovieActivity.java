package com.xtreamplayer.tv;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Full-bleed movie page: backdrop, title, details, Play / Resume / Favourite. */
public class MovieActivity extends Activity {
    private Prefs prefs;
    private XtreamApi api;
    private Item movie;

    private ImageView backdrop;
    private TextView title, meta, plot, credits;
    private LinearLayout buttons;
    private Button playBtn, favBtn;
    private String trailer;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        prefs = new Prefs(this);
        api = new XtreamApi(prefs);
        try {
            movie = Item.fromJson(new JSONObject(getIntent().getStringExtra("item")));
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
        View l = new View(this);
        l.setBackground(Ui.scrimLeft());
        root.addView(l, match());
        View bt = new View(this);
        bt.setBackground(Ui.pageScrimBottom());
        root.addView(bt, match());

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.BOTTOM);
        col.setClipChildren(false);
        int pad = Ui.dp(this, 80);
        col.setPadding(pad, pad, pad, Ui.dp(this, 72));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(Ui.dp(this, 900), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        root.addView(col, clp);

        title = Ui.lines(Ui.title(this, movie.name, 48), 2);
        meta = Ui.oneLine(Ui.medium(this, movie.info == null ? "" : movie.info, 18, Ui.TEXT2));
        meta.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 18));
        plot = Ui.lines(Ui.text(this, "", 19, Ui.TEXT), 5);
        credits = Ui.lines(Ui.text(this, "", 15, Ui.TEXT3), 2);
        credits.setPadding(0, Ui.dp(this, 14), 0, 0);
        col.addView(title);
        col.addView(meta);
        col.addView(plot);
        col.addView(credits);

        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setClipChildren(false);
        buttons.setPadding(0, Ui.dp(this, 36), 0, 0);
        col.addView(buttons);

        setContentView(root);
        buildButtons();
        loadInfo();
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void buildButtons() {
        buttons.removeAllViews();
        final long pos = prefs.position(movie);
        playBtn = Ui.button(this, pos > 0 ? "▶  Resume  " + SeriesActivity.fmt(pos) : "▶  Play");
        playBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { play(false); }
        });
        addButton(playBtn);
        if (pos > 0) {
            Button over = Ui.button(this, "↺  Start Over");
            over.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { play(true); }
            });
            addButton(over);
        }
        if (trailer != null) {
            Button tr = Ui.button(this, "▶  Trailer");
            tr.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { TrailerActivity.open(MovieActivity.this, trailer); }
            });
            addButton(tr);
        }
        favBtn = Ui.button(this, "");
        updateFav();
        favBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs.toggleFav(movie);
                updateFav();
            }
        });
        addButton(favBtn);
        if (!buttons.hasFocus()) playBtn.requestFocus();
    }

    private void addButton(Button b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 18);
        buttons.addView(b, lp);
    }

    private void updateFav() {
        favBtn.setText(prefs.isFav(movie) ? "♥  In Favourites" : "♡  Favourite");
    }

    private void play(boolean fromStart) {
        if (fromStart) prefs.setPosition(movie, 0);
        List<Item> q = new ArrayList<>();
        q.add(movie);
        Open.play(this, q, 0);
    }

    private void loadInfo() {
        if (movie.icon != null) loadBackdrop(movie.icon);
        Async.run(new Async.Work<JSONObject>() {
            public JSONObject run() throws Exception { return api.vodInfo(movie.id); }
        }, new Async.Done<JSONObject>() {
            public void done(JSONObject r, Exception e) {
                if (isFinishing() || r == null) return;
                JSONObject info = r.optJSONObject("info");
                if (info == null) return;
                String p = str(info, "plot", str(info, "description", null));
                if (p != null) {
                    plot.setText(p);
                    movie.plot = p;
                }
                String year = str(info, "releasedate", str(info, "release_date", null));
                if (year != null && year.length() >= 4) year = year.substring(0, 4);
                String dur = str(info, "duration", null);
                if (dur != null && dur.startsWith("0") && dur.length() == 8) dur = shortDuration(dur);
                String m = XtreamApi.joinNonEmpty(year, dur, str(info, "genre", null),
                        XtreamApi.rating(str(info, "rating", null)));
                if (m != null) meta.setText(m);
                String cr = XtreamApi.joinNonEmpty(
                        str(info, "director", null) == null ? null : "Director: " + str(info, "director", null),
                        str(info, "cast", str(info, "actors", null)) == null ? null : "Cast: " + str(info, "cast", str(info, "actors", null)));
                credits.setText(cr == null ? "" : cr);
                String bd = null;
                JSONArray arr = info.optJSONArray("backdrop_path");
                if (arr != null && arr.length() > 0) bd = arr.optString(0, null);
                if (bd == null || bd.isEmpty()) bd = str(info, "movie_image", movie.icon);
                if (bd != null) loadBackdrop(bd);
                String t = TrailerActivity.youtubeId(str(info, "youtube_trailer", str(info, "trailer", null)));
                if (t != null && trailer == null) {
                    trailer = t;
                    View f = getCurrentFocus();
                    int idx = f != null && f.getParent() == buttons ? buttons.indexOfChild(f) : 0;
                    buildButtons();
                    if (idx < buttons.getChildCount()) buttons.getChildAt(idx).requestFocus();
                }
                JSONObject data = r.optJSONObject("movie_data");
                if (data != null && movie.ext == null) movie.ext = str(data, "container_extension", null);
            }
        });
    }

    private void loadBackdrop(String url) {
        ImageLoader.load(backdrop, url, 1280, 720);
        backdrop.animate().alpha(1f).setDuration(500).start();
    }

    static String shortDuration(String hms) {
        try {
            String[] p = hms.split(":");
            int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
            return h > 0 ? h + " hr " + m + " min" : m + " min";
        } catch (Exception e) {
            return hms;
        }
    }

    static String str(JSONObject o, String k, String def) {
        if (o == null || o.isNull(k)) return def;
        String s = o.optString(k, "").trim();
        return s.isEmpty() || "null".equals(s) ? def : s;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (buttons != null && buttons.getChildCount() > 0) buildButtons();
    }
}
