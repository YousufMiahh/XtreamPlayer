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
    private View playBtn;
    private Button favBtn;
    private String trailer;
    private Preview preview;
    private boolean resumed;

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
        FrameLayout previewHost = new FrameLayout(this); // the muted preview, under the artwork until it starts
        root.addView(previewHost, 0, match());
        preview = new Preview(this, prefs, api, previewHost, backdrop);
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
        col.setClipToPadding(false);
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
        new TitleLogo(this, title, Tmdb.MOVIE, movie.name, movie.tmdb, 560, 150);

        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL); // Netflix: Resume / Play From Beginning, stacked
        buttons.setClipChildren(false);
        buttons.setClipToPadding(false);
        buttons.setPadding(0, Ui.dp(this, 30), 0, 0);
        col.addView(buttons);

        setContentView(root);
        buildButtons();
        loadInfo();
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void buildButtons() {
        // Keep focus on the same button when this is rebuilt
        View f = getCurrentFocus();
        int top = -1, sub = -1;
        if (f != null && f.getParent() == buttons) top = buttons.indexOfChild(f);
        else if (f != null && f.getParent() instanceof View && ((View) f.getParent()).getParent() == buttons) {
            top = buttons.indexOfChild((View) f.getParent());
            sub = ((ViewGroup) f.getParent()).indexOfChild(f);
        }
        buttons.removeAllViews();
        final long pos = prefs.position(movie), dur = prefs.duration(movie);
        boolean resume = pos > 0;
        playBtn = Hero.button(this, resume ? "▶   Resume" : "▶   Play", resume && dur > 0 ? pos / (float) dur : -1);
        playBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { play(false); }
        });
        Hero.add(buttons, playBtn);
        if (resume) {
            View over = Hero.button(this, "↻   Play From Beginning", -1);
            over.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { play(true); }
            });
            Hero.add(buttons, over);
        } else {
            View as = Hero.button(this, "⌨   Audio & Subtitles", -1);
            as.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { Hero.audioSubtitles(MovieActivity.this, prefs); }
            });
            Hero.add(buttons, as);
        }
        LinearLayout small = new LinearLayout(this);
        small.setClipChildren(false);
        small.setClipToPadding(false);
        small.setPadding(0, Ui.dp(this, 8), 0, 0);
        if (trailer != null) {
            Button tr = Ui.button(this, "▶  Trailer");
            tr.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { preview.stop(); TrailerActivity.open(MovieActivity.this, trailer); }
            });
            addButton(small, tr);
        }
        favBtn = Ui.button(this, "");
        updateFav();
        favBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs.toggleFav(movie);
                updateFav();
            }
        });
        addButton(small, favBtn);
        final Button queueBtn = Ui.button(this, prefs.inQueue(movie) ? "✓  In Queue" : "＋  Queue");
        queueBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (prefs.inQueue(movie)) prefs.queueRemove(movie); else prefs.queueAdd(movie);
                queueBtn.setText(prefs.inQueue(movie) ? "✓  In Queue" : "＋  Queue");
            }
        });
        addButton(small, queueBtn);
        final Button watchedBtn = Ui.button(this, prefs.isWatched(movie) ? "✓  Watched" : "○  Mark Watched");
        watchedBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs.setWatched(movie, !prefs.isWatched(movie));
                buildButtons();
            }
        });
        addButton(small, watchedBtn);
        buttons.addView(small);
        // Something you were part way through plays (muted) behind the page after a moment
        View.OnFocusChangeListener later = new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) { if (has) schedulePreview(); }
        };
        for (int i = 0; i < buttons.getChildCount() - 1; i++) chain(buttons.getChildAt(i), later);
        View want = null;
        if (top >= 0 && top < buttons.getChildCount()) {
            want = buttons.getChildAt(top);
            if (sub >= 0 && want instanceof ViewGroup && sub < ((ViewGroup) want).getChildCount()) want = ((ViewGroup) want).getChildAt(sub);
        }
        if (want != null) want.requestFocus();
        else if (!buttons.hasFocus()) playBtn.requestFocus();
        if (!resume) preview.stop();
    }

    private static void chain(View v, final View.OnFocusChangeListener extra) {
        final View.OnFocusChangeListener base = v.getOnFocusChangeListener();
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View x, boolean has) {
                if (base != null) base.onFocusChange(x, has);
                extra.onFocusChange(x, has);
            }
        });
    }

    private void addButton(LinearLayout row, Button b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 14);
        row.addView(b, lp);
    }

    private final Runnable startPreview = new Runnable() {
        public void run() {
            long pos = prefs.position(movie);
            if (!resumed || pos <= 0 || prefs.homePreviews() == 0) return;
            View f = getCurrentFocus();
            if (f == null || f.getParent() != buttons) return;
            preview.play(movie, pos);
        }
    };

    private void schedulePreview() {
        Async.MAIN.removeCallbacks(startPreview);
        if (prefs.position(movie) > 0) Async.MAIN.postDelayed(startPreview, 2000);
    }

    private void updateFav() {
        favBtn.setText(prefs.isFav(movie) ? "♥  In Favourites" : "♡  Favourite");
    }

    private void play(boolean fromStart) {
        preview.stop();
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
                if (movie.tmdb == null) movie.tmdb = str(info, "tmdb_id", str(info, "tmdb", null));
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
                    buildButtons();
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
        resumed = true;
        if (buttons != null && buttons.getChildCount() > 0) buildButtons();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        Async.MAIN.removeCallbacks(startPreview);
        if (preview != null) preview.stop();
    }

    @Override
    protected void onDestroy() {
        Async.MAIN.removeCallbacks(startPreview);
        if (preview != null) preview.release();
        super.onDestroy();
    }
}
