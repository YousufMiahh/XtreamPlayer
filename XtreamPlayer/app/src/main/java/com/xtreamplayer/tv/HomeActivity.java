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
    private List<Item> newMovies, newSeries;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        prefs = new Prefs(this);
        api = new XtreamApi(prefs);
        ImageLoader.setUserAgent(prefs.userAgent());

        FrameLayout root = new FrameLayout(this);
        root.setBackground(Ui.screenBackground());
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setSmoothScrollingEnabled(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setPadding(0, 0, 0, Ui.dp(this, 48));
        col.addView(TopBar.build(this, TopBar.HOME));

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
        focusFirstCard();
    }

    /** Loads the saved library (instant) and refreshes it in the background when it's due. */
    private void startLibrary() {
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
        }
    };

    private void buildLocalRows() {
        localRows.removeAllViews();
        add(localRows, "Continue Watching", prefs.continueWatching(), Cards.Shape.LANDSCAPE);
        add(localRows, "Recent Channels", limit(prefs.history(true), 20), Cards.Shape.CHANNEL);
        add(localRows, "Favourite Channels", prefs.favs(Item.LIVE), Cards.Shape.CHANNEL);
        add(localRows, "Favourite Movies", prefs.favs(Item.MOVIE), Cards.Shape.POSTER);
        add(localRows, "Favourite Series", prefs.favs(Item.SERIES), Cards.Shape.POSTER);
        updateEmpty();
    }

    private void buildRemoteRows() {
        remoteRows.removeAllViews();
        if (newMovies != null) add(remoteRows, "Recently Added Movies", newMovies, Cards.Shape.POSTER);
        if (newSeries != null) add(remoteRows, "Recently Added Series", newSeries, Cards.Shape.POSTER);
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

    private void loadNewArrivals() {
        Async.run(new Async.Work<List<Item>>() {
            public List<Item> run() throws Exception { return Repo.recentlyAdded(api, Item.MOVIE, 24); }
        }, new Async.Done<List<Item>>() {
            public void done(List<Item> r, Exception e) {
                if (isFinishing()) return;
                if (r != null) newMovies = r;
                buildRemoteRows();
                if (getCurrentFocus() == null) focusFirstCard();
                Async.run(new Async.Work<List<Item>>() {
                    public List<Item> run() throws Exception { return Repo.recentlyAdded(api, Item.SERIES, 24); }
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
