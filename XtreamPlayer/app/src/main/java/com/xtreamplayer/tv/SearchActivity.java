package com.xtreamplayer.tv;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Search channels, movies and series at once.
 * Fast because: the library comes from the on-device copy, each section shows as soon as it's
 * ready, names are pre-lower-cased, and matching runs off the UI thread.
 */
public class SearchActivity extends Activity implements TopBar.Reselect {
    private static final String[] TYPES = {Item.LIVE, Item.MOVIE, Item.SERIES};
    private static final String[] TITLES = {"Channels", "Movies", "Series"};
    private static final int MAX = 40;

    private Prefs prefs;
    private XtreamApi api;
    private EditText box;
    private final LinearLayout[] slots = new LinearLayout[3];
    private TextView status;
    private final boolean[] ready = new boolean[3];
    private final int[] found = new int[3];
    private volatile int token;
    private final Runnable pending = new Runnable() { public void run() { search(); } };
    /** 0 = All, 1 = Live TV, 2 = Movies, 3 = Series. */
    private int filter;
    private final TextView[] chips = new TextView[4];
    private static final String[] CHIP_NAMES = {"All", "Live TV", "Movies", "Series"};
    private static final int GRID_MAX = 120;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        prefs = new Prefs(this);
        api = new XtreamApi(prefs);

        ScrollView scroll = new ScrollView(this);
        Ambient.install(this, Ambient.SEARCH);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setPadding(0, 0, 0, Ui.dp(this, 48));
        col.addView(TopBar.build(this, TopBar.SEARCH));

        box = Ui.input(this, "Channels, movies and series", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        box.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.sp(this, 24));
        box.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(this, 820), ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        blp.topMargin = Ui.dp(this, 28);
        col.addView(box, blp);

        // All | Live TV | Movies | Series
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setGravity(Gravity.CENTER_HORIZONTAL);
        chipRow.setClipChildren(false);
        chipRow.setClipToPadding(false);
        chipRow.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 4));
        for (int i = 0; i < 4; i++) {
            final int f = i;
            chips[i] = Ui.medium(this, CHIP_NAMES[i], 18, Ui.TEXT);
            chips[i].setFocusable(true);
            int px = Ui.dp(this, 24), py = Ui.dp(this, 11);
            chips[i].setPadding(px, py, px, py);
            chips[i].setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean has) { Ui.animateFocus(v, has, 1.06f); }
            });
            chips[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { setFilter(f); }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = clp.rightMargin = Ui.dp(this, 6);
            chipRow.addView(chips[i], clp);
        }
        col.addView(chipRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        paintChips();

        status = Ui.text(this, "", 17, Ui.TEXT2);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, Ui.dp(this, 18), 0, 0);
        col.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < 3; i++) {
            slots[i] = new LinearLayout(this);
            slots[i].setOrientation(LinearLayout.VERTICAL);
            slots[i].setClipChildren(false);
            col.addView(slots[i]);
        }
        scroll.addView(col);
        setContentView(scroll);
        TopBar.animateIn(this, box, chipRow, status, slots[0], slots[1], slots[2]);

        box.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                Async.MAIN.removeCallbacks(pending);
                Async.MAIN.postDelayed(pending, 300);
            }
        });
        // The keyboard's action button says "Search" and, whatever it's labelled, searches and closes the keyboard.
        box.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        box.setImeActionLabel("Search", EditorInfo.IME_ACTION_SEARCH);
        box.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int action, KeyEvent e) {
                if (e != null && e.getAction() != KeyEvent.ACTION_DOWN) return true;
                Async.MAIN.removeCallbacks(pending);
                search();
                closeKeyboard();
                return true;
            }
        });
        box.requestFocus();
        loadLibrary();
    }

    /**
     * Search has its own background workers (one for loading lists, one for matching) so it can
     * never hold up Live TV, Movies or Series loading elsewhere in the app.
     */
    private static final java.util.concurrent.ExecutorService LOADER = lowPriority("search-load");
    private static final java.util.concurrent.ExecutorService MATCHER = lowPriority("search-match");

    private static java.util.concurrent.ExecutorService lowPriority(final String name) {
        return java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
            public Thread newThread(final Runnable r) {
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                        r.run();
                    }
                }, name);
                t.setDaemon(true);
                return t;
            }
        });
    }

    private void loadLibrary() {
        boolean all = true;
        for (int i = 0; i < 3; i++) {
            ready[i] = Repo.cached(TYPES[i], null) != null;
            all &= ready[i];
        }
        status.setText(all ? "Type to search everything" : "Getting your library ready…");
        for (int i = 0; i < 3; i++) {
            if (ready[i]) continue;
            final int slot = i;
            Async.runOn(LOADER, new Async.Work<List<Item>>() {
                public List<Item> run() throws Exception { return Repo.streams(api, TYPES[slot], null); }
            }, new Async.Done<List<Item>>() {
                public void done(List<Item> r, Exception e) {
                    if (isFinishing()) return;
                    ready[slot] = true;
                    searchSlot(slot, query(), token); // fill this section as soon as it arrives
                    updateStatus();
                }
            });
        }
    }

    private String query() {
        return box.getText().toString().trim().toLowerCase(Locale.UK);
    }

    private void paintChips() {
        for (int i = 0; i < 4; i++) {
            boolean on = i == filter;
            chips[i].setBackground(Ui.pill(this, on ? Ui.SURFACE_HI : 0, 22));
            chips[i].setTextColor(Ui.focusText(on ? Ui.TEXT : Ui.TEXT2));
            chips[i].setTypeface(on ? Ui.semibold() : Ui.medium());
        }
    }

    /** Pick All / Live TV / Movies / Series. One type shows its results as a full-width grid. */
    private void setFilter(int f) {
        if (f == filter) return;
        filter = f;
        paintChips();
        search();
    }

    private boolean shows(int slot) { return filter == 0 || filter == slot + 1; }

    private void search() {
        token++;
        for (int i = 0; i < 3; i++) {
            found[i] = 0;
            if (query().length() < 2 || !shows(i)) slots[i].removeAllViews();
            else if (ready[i]) searchSlot(i, query(), token);
        }
        updateStatus();
    }

    private void updateStatus() {
        boolean all = ready[0] && ready[1] && ready[2];
        String q = query();
        if (q.length() < 2) {
            status.setText(all ? "Type to search everything" : "Getting your library ready…");
            return;
        }
        int total = found[0] + found[1] + found[2];
        if (!all) status.setText(total == 0 ? "Searching…" : "");
        else status.setText(total == 0 ? "No results for \"" + q + "\"" : "");
    }

    /** Matches in the background, then swaps that section's row. Stale results are dropped. */
    private void searchSlot(final int slot, final String q, final int t) {
        if (q.length() < 2 || !shows(slot)) return;
        final boolean asGrid = filter != 0;
        final int max = asGrid ? GRID_MAX : MAX;
        final List<Item> all = Repo.cached(TYPES[slot], null);
        if (all == null) return;
        Async.runOn(MATCHER, new Async.Work<List<Item>>() {
            public List<Item> run() {
                // Ignore punctuation, spaces and accents: "spiderman", "spider man" and "spiderm"
                // all find "Spider-Man". Several words match in any order ("man spider").
                if (t != token) return null; // you've typed more since: skip this one
                return Match.rank(Filters.items(api, prefs, TYPES[slot], all), q, max, null); // hidden categories left out // best matches first (see Match)
            }
        }, new Async.Done<List<Item>>() {
            public void done(final List<Item> hits, Exception e) {
                if (isFinishing() || t != token || hits == null) return;
                boolean focusInside = slots[slot].findFocus() != null;
                slots[slot].removeAllViews();
                found[slot] = hits.size();
                if (!hits.isEmpty()) {
                    Cards.Shape shape = slot == 0 ? Cards.Shape.CHANNEL : Cards.Shape.POSTER;
                    String heading = TITLES[slot] + "  ·  " + hits.size() + (hits.size() >= max ? "+" : "");
                    Cards.OnPick onPick = new Cards.OnPick() {
                        public void pick(Item it, View card) { Open.item(SearchActivity.this, it, hits); }
                    };
                    Cards.OnMenu onMenu = new Cards.OnMenu() {
                        public void menu(Item it, View card) { Open.toggleFav(SearchActivity.this, prefs, it); }
                    };
                    if (asGrid) slots[slot].addView(Cards.grid(SearchActivity.this, heading, hits, shape, prefs, onPick, onMenu));
                    else slots[slot].addView(Cards.shelf(SearchActivity.this, heading,
                            hits, shape, prefs,
                            new Cards.OnPick() {
                                public void pick(Item it, View card) { Open.item(SearchActivity.this, it, hits); }
                            },
                            new Cards.OnMenu() {
                                public void menu(Item it, View card) { Open.toggleFav(SearchActivity.this, prefs, it); }
                            }));
                }
                if (focusInside) box.requestFocus();
                updateStatus();
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (box.hasFocus()) { // Back while typing: close the keyboard and stay on Search
            closeKeyboard();
            return;
        }
        TopBar.back(this);
    }

    private void closeKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(box.getWindowToken(), 0);
        // Move to the first result if there is one, otherwise the Search tab
        View first = null;
        for (LinearLayout s : slots) {
            if (s.getChildCount() > 0) {
                View f = findFirstFocusable(s);
                if (f != null) { first = f; break; }
            }
        }
        if (first != null) first.requestFocus();
        else TopBar.focusTab(this, TopBar.SEARCH);
    }

    private View findFirstFocusable(View v) {
        if (v.isFocusable() && v.getVisibility() == View.VISIBLE && !(v instanceof android.widget.EditText)) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = findFirstFocusable(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }

    /** OK on the Search tab: jump into the search box with the keyboard open. */
    public void onTabReselected() {
        box.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(box, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT); // not "forced": Back can close it
    }

    @Override
    protected void onDestroy() {
        Async.MAIN.removeCallbacks(pending);
        super.onDestroy();
    }
}
