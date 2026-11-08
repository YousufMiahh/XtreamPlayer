package com.xtreamplayer.tv;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Home: shelves of Continue Watching, recent and favourite channels, favourites and new arrivals. */
public class HomeActivity extends Activity {
    private Prefs prefs;
    private XtreamApi api;
    private LinearLayout localRows, remoteRows;
    private TextView empty;
    private boolean resumedOnce;
    private List<Item> newMovies, newSeries, topMovies, topSeries;
    private String topSig = "";
    private static final long TOP10_EVERY = 60 * 60_000L;
    private final Runnable top10Tick = new Runnable() {
        public void run() {
            loadTop10();
            Async.MAIN.postDelayed(this, TOP10_EVERY);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        prefs = new Prefs(this);
        api = new XtreamApi(prefs);
        ImageLoader.setUserAgent(prefs.userAgent());

        FrameLayout root = new FrameLayout(this);
        Ambient.install(this, Ambient.HOME);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setSmoothScrollingEnabled(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setPadding(0, 0, 0, Ui.dp(this, 48));
        topBar = TopBar.build(this, TopBar.HOME);
        col.addView(topBar);

        localRows = new LinearLayout(this);
        localRows.setOrientation(LinearLayout.VERTICAL);
        localRows.setClipChildren(false);
        remoteRows = new LinearLayout(this);
        remoteRows.setOrientation(LinearLayout.VERTICAL);
        remoteRows.setClipChildren(false);
        col.addView(localRows);
        col.addView(remoteRows);

        empty = Ui.text(this, "", 18, Ui.TEXT2);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, Ui.dp(this, 80), 0, 0);
        col.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.addView(col);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        buildLocalRows();
        startLibrary();
        showCrashNotice();
        focusFirstCard();
        Async.MAIN.postDelayed(new Runnable() { // once the rows have filled in
            public void run() {
                if (isFinishing()) return;
                Coach.offer(HomeActivity.this, true, new Runnable() { public void run() { homeTour(); } });
            }
        }, 1500);
    }

    private View topBar;

    /** Quick-start tips for Home (Settings → Show Tutorial runs them again). */
    private void homeTour() {
        View rows = localRows.getChildCount() > 0 ? localRows.getChildAt(0) : remoteRows.getChildCount() > 0 ? remoteRows.getChildAt(0) : null;
        Coach.show(this, "home", true,
                new Coach.Step(topBar, "Getting around",
                        "Move along the top bar to switch between Home, Live TV, Movies, Series, Search and Settings. Just pause on one and it opens."),
                new Coach.Step(rows, "Your Home screen",
                        "Continue Watching, Recent Channels, your favourites and the UK Top 10 all live here. "
                                + (Ui.hasMenuKey() ? "Press ☰ (or hold OK)" : "Hold OK") + " on a card to favourite it, or to remove it from Continue Watching."),
                new Coach.Step(null, "Make it yours",
                        "In Settings you can choose which rows Home shows, hide countries and categories you never watch, "
                                + "change subtitle style and switch accounts. You can watch this tour again from Settings too."));
    }

    /** After a crash: explain what happened and show the report (so it can be photographed and sent). */
    private void showCrashNotice() {
        boolean vlc = prefs.takeVlcBrokenNotice();
        boolean fresh = App.crashIsNew(this);
        final String crash = fresh || vlc ? App.lastCrash(this) : null;
        if (!vlc && crash == null) return;
        String msg = vlc
                ? "The VLC player stopped the app on this TV, so YM Player has switched to the Android player for now. "
                  + "You can try VLC again in Settings → Playback → Video Player."
                : "YM Player closed unexpectedly last time. Here's the error report — a photo of it helps fix the problem.";
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this,
                Ui.DARK ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle(vlc ? "Switched to the Android player" : "Sorry about that")
                .setMessage(msg)
                .setPositiveButton("OK", null);
        if (crash != null) {
            b.setNeutralButton("Show error report", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    TextView t = Ui.text(HomeActivity.this, crash, 12, Ui.TEXT);
                    t.setTypeface(android.graphics.Typeface.MONOSPACE);
                    int p = Ui.dp(HomeActivity.this, 20);
                    t.setPadding(p, p, p, p);
                    ScrollView sv = new ScrollView(HomeActivity.this);
                    sv.addView(t);
                    new android.app.AlertDialog.Builder(HomeActivity.this,
                            Ui.DARK ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert)
                            .setTitle("Error report").setView(sv).setPositiveButton("OK", null).show();
                }
            });
        }
        b.show();
    }

    /** Loads the saved library (instant) and refreshes it in the background when it's due. */
    private void startLibrary() {
        Repo.onUpdated = new Runnable() { // Movies/Series finished in the background: refresh the rows
            public void run() { if (!isFinishing()) { loadNewArrivals(); loadTop10(); } }
        };
        if (Repo.isRefreshing()) { // setup is still finishing Movies & Series
            loadNewArrivals();
            return;
        }
        if (Repo.isDue(prefs) && !Repo.hasLibrary(api)) {
            // First run for this account: download everything once, then fill the rows.
            if (localRows.getChildCount() == 0) {
                empty.setText("Getting your channels, movies and series ready…\nThis only takes a while the first time.");
                empty.setVisibility(View.VISIBLE);
            }
            Async.run(new Async.Work<Boolean>() {
                public Boolean run() throws Exception {
                    Repo.refreshAll(api, prefs);
                    return true;
                }
            }, new Async.Done<Boolean>() {
                public void done(Boolean ok, Exception e) {
                    if (isFinishing()) return;
                    loadNewArrivals();
                }
            });
        } else {
            loadNewArrivals();
            Repo.preload(api);
            Repo.refreshIfDue(api, prefs, new Runnable() {
                public void run() {
                    if (!isFinishing()) loadNewArrivals();
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (resumedOnce) {
            View f = getCurrentFocus();
            boolean inLocal = f != null && isInside(f, localRows);
            buildLocalRows();
            buildRemoteRows();
            if (inLocal || getCurrentFocus() == null) focusFirstCard();
        }
        resumedOnce = true;
        TopBar.onHomeResumed(this, localRows, remoteRows, empty);
    }

    private boolean isInside(View v, View parent) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) if (p == parent) return true;
        return false;
    }

    private final Cards.OnPick pick = new Cards.OnPick() {
        public void pick(Item it, View card) {
            List<Item> siblings = null;
            if (Item.LIVE.equals(it.type)) {
                for (List<Item> l : new List[]{prefs.history(true), prefs.favs(Item.LIVE)}) {
                    for (Item x : l) if (x.key().equals(it.key())) { siblings = l; break; }
                    if (siblings != null) break;
                }
            }
            Open.item(HomeActivity.this, it, siblings);
        }
    };

    private final Cards.OnMenu favMenu = new Cards.OnMenu() {
        public void menu(Item it, View card) {
            if (Item.EPISODE.equals(it.type)) return;
            Open.toggleFav(HomeActivity.this, prefs, it);
            refreshKeepingPlace(card);
        }
    };

    /** Menu (☰) on a Continue Watching card: ask, then take it off the list. */
    private final Cards.OnMenu removeMenu = new Cards.OnMenu() {
        public void menu(final Item it, final View card) {
            String name = Item.EPISODE.equals(it.type) && it.parentName != null && !it.parentName.isEmpty()
                    ? it.parentName + " · " + it.name : it.name;
            android.app.AlertDialog d = new android.app.AlertDialog.Builder(HomeActivity.this,
                    Ui.DARK ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle("Remove from Continue Watching?")
                    .setMessage(name)
                    .setPositiveButton("Remove", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface di, int w) {
                            prefs.setPosition(it, 0); // no resume point = off the list
                            refreshKeepingPlace(card);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .create();
            d.show();
            android.widget.Button cancel = d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE);
            if (cancel != null) cancel.requestFocus(); // a stray OK won't remove anything
        }
    };

    /** Rebuild the rows straight away (e.g. a favourite was removed) and keep focus near where it was. */
    private void refreshKeepingPlace(View card) {
        String shelfTitle = null;
        int shelfIndex = -1, cardIndex = 0;
        if (card != null && card.getParent() instanceof ViewGroup) {
            ViewGroup row = (ViewGroup) card.getParent();
            cardIndex = row.indexOfChild(card);
            View box = row.getParent() instanceof View ? (View) ((View) row.getParent()).getParent() : null;
            if (box instanceof ViewGroup && box.getParent() == localRows) {
                shelfIndex = localRows.indexOfChild(box);
                View t = ((ViewGroup) box).getChildAt(0);
                if (t instanceof TextView) shelfTitle = ((TextView) t).getText().toString();
            }
        }
        buildLocalRows();
        if (shelfIndex < 0) return;
        // Same shelf if it still exists, otherwise the one that moved into its place
        ViewGroup target = null;
        for (int i = 0; i < localRows.getChildCount(); i++) {
            View t = ((ViewGroup) localRows.getChildAt(i)).getChildAt(0);
            if (t instanceof TextView && ((TextView) t).getText().toString().equals(shelfTitle)) target = (ViewGroup) localRows.getChildAt(i);
        }
        if (target == null && localRows.getChildCount() > 0) {
            target = (ViewGroup) localRows.getChildAt(Math.min(shelfIndex, localRows.getChildCount() - 1));
            cardIndex = 0;
        }
        if (target == null) {
            focusFirstCard();
            return;
        }
        ViewGroup row = (ViewGroup) ((ViewGroup) target.getChildAt(1)).getChildAt(0);
        if (row.getChildCount() == 0) return;
        row.getChildAt(Math.min(cardIndex, row.getChildCount() - 1)).requestFocus();
    }

    private void buildLocalRows() {
        localRows.removeAllViews();
        List<Item> cw = prefs.homeShows("continue") ? prefs.continueWatching() : new java.util.ArrayList<Item>();
        if (!cw.isEmpty()) {
            LinearLayout shelf = Cards.shelf(this, "Continue Watching", cw, Cards.Shape.LANDSCAPE, prefs, pick, removeMenu);
            ((TextView) shelf.getChildAt(0)).setText(Cards.titleWithMenuHint(this, "Continue Watching", "to remove from list"));
            localRows.addView(shelf);
        }
        if (prefs.homeShows("recent")) add(localRows, "Recent Channels", limit(prefs.history(true), 20), Cards.Shape.CHANNEL);
        if (prefs.homeShows("fav_live")) add(localRows, "Favourite Channels", prefs.favs(Item.LIVE), Cards.Shape.CHANNEL);
        if (prefs.homeShows("fav_movie")) add(localRows, "Favourite Movies", prefs.favs(Item.MOVIE), Cards.Shape.POSTER);
        if (prefs.homeShows("fav_series")) add(localRows, "Favourite Series", prefs.favs(Item.SERIES), Cards.Shape.POSTER);
        updateEmpty();
    }

    private void buildRemoteRows() {
        remoteRows.removeAllViews();
        if (prefs.homeShows("top_movies")) add(remoteRows, "Top 10 Movies on Netflix UK", topMovies, Cards.Shape.POSTER);
        if (prefs.homeShows("top_series")) add(remoteRows, "Top 10 Series on Netflix UK", topSeries, Cards.Shape.POSTER);
        if (newMovies != null && prefs.homeShows("new_movies")) add(remoteRows, "Recently Added Movies", newMovies, Cards.Shape.POSTER);
        if (newSeries != null && prefs.homeShows("new_series")) add(remoteRows, "Recently Added Series", newSeries, Cards.Shape.POSTER);
        updateEmpty();
    }

    private void add(LinearLayout parent, String title, List<Item> items, Cards.Shape shape) {
        if (items == null || items.isEmpty()) return;
        parent.addView(Cards.shelf(this, title, items, shape, prefs, pick, favMenu));
    }

    private void updateEmpty() {
        boolean none = localRows.getChildCount() == 0 && remoteRows.getChildCount() == 0;
        empty.setText(none ? "Welcome to YM Player\nPick Live TV, Movies or Series above to get started." : "");
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
    }

    private static List<Item> limit(List<Item> l, int n) {
        return l.size() > n ? l.subList(0, n) : l;
    }

    /** Newest 24, leaving out hidden categories and countries. */
    private List<Item> recent(String type) throws java.io.IOException {
        List<Item> l = Filters.items(api, prefs, type, Repo.recentlyAdded(api, type, 300));
        return l.size() > 24 ? new java.util.ArrayList<>(l.subList(0, 24)) : l;
    }

    private void loadNewArrivals() {
        Async.run(new Async.Work<List<Item>>() {
            public List<Item> run() throws Exception { return recent(Item.MOVIE); }
        }, new Async.Done<List<Item>>() {
            public void done(List<Item> r, Exception e) {
                if (isFinishing()) return;
                if (r != null) newMovies = r;
                buildRemoteRows();
                if (getCurrentFocus() == null) focusFirstCard();
                Async.run(new Async.Work<List<Item>>() {
                    public List<Item> run() throws Exception { return recent(Item.SERIES); }
                }, new Async.Done<List<Item>>() {
                    public void done(List<Item> r2, Exception e2) {
                        if (isFinishing()) return;
                        if (r2 != null) newSeries = r2;
                        View f = getCurrentFocus();
                        boolean inRemote = f != null && isInside(f, remoteRows);
                        buildRemoteRows();
                        if (inRemote || getCurrentFocus() == null) focusFirstCard();
                    }
                });
            }
        });
    }

    /** Netflix UK Top 10, matched to this provider's library. Checked hourly; only rebuilds the rows if it changed. */
    private void loadTop10() {
        Async.runOn(Top10.WORKER, new Async.Work<List<Item>[]>() {
            @SuppressWarnings("unchecked")
            public List<Item>[] run() throws Exception {
                Top10.Data d = Top10.refresh(getApplicationContext());
                if (d == null) return null;
                List<Item> movies = Repo.cached(Item.MOVIE, null), series = Repo.cached(Item.SERIES, null);
                if (movies == null && Repo.hasLibrary(api)) movies = Repo.streams(api, Item.MOVIE, null);
                if (series == null && Repo.hasLibrary(api)) series = Repo.streams(api, Item.SERIES, null);
                return new List[]{Top10.match(d.films, movies), Top10.match(d.tv, series)};
            }
        }, new Async.Done<List<Item>[]>() {
            public void done(List<Item>[] r, Exception e) {
                if (isFinishing() || r == null) return;
                String sig = sig(r[0]) + "|" + sig(r[1]);
                if (sig.equals(topSig)) return;
                topSig = sig;
                topMovies = r[0];
                topSeries = r[1];
                View f = getCurrentFocus();
                boolean inRemote = f != null && isInside(f, remoteRows);
                buildRemoteRows();
                if (inRemote || getCurrentFocus() == null) focusFirstCard();
            }
        });
    }

    private static String sig(List<Item> l) {
        StringBuilder b = new StringBuilder();
        if (l != null) for (Item it : l) b.append(it.key()).append(',');
        return b.toString();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Async.MAIN.removeCallbacks(top10Tick);
        Async.MAIN.post(top10Tick); // check now (cheap if not due), then every hour while Home is open
    }

    @Override
    protected void onStop() {
        Async.MAIN.removeCallbacks(top10Tick);
        super.onStop();
    }

    private void focusFirstCard() {
        LinearLayout[] groups = {localRows, remoteRows};
        for (LinearLayout g : groups) {
            if (g.getChildCount() > 0) {
                View first = firstFocusable(g.getChildAt(0));
                if (first != null) {
                    first.requestFocus();
                    return;
                }
            }
        }
    }

    private View firstFocusable(View v) {
        if (v.isFocusable() && v.getVisibility() == View.VISIBLE) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = firstFocusable(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }
}
