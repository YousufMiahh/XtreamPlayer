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
public class SearchActivity extends Activity {
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
    private int token;
    private final Runnable pending = new Runnable() { public void run() { search(); } };

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

        box.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                Async.MAIN.removeCallbacks(pending);
                Async.MAIN.postDelayed(pending, 300);
            }
        });
        box.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int action, KeyEvent e) {
                Async.MAIN.removeCallbacks(pending);
                search();
                return false;
            }
        });
        box.requestFocus();
        loadLibrary();
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
            Async.run(new Async.Work<List<Item>>() {
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

    private void search() {
        token++;
        for (int i = 0; i < 3; i++) {
            found[i] = 0;
            if (query().length() < 2) slots[i].removeAllViews();
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
        if (q.length() < 2) return;
        final List<Item> all = Repo.cached(TYPES[slot], null);
        if (all == null) return;
        Async.run(new Async.Work<List<Item>>() {
            public List<Item> run() {
                // Ignore punctuation, spaces and accents: "spiderman", "spider man" and "spiderm"
                // all find "Spider-Man". Several words match in any order ("man spider").
                String folded = Item.fold(q);
                String qc = folded.replaceAll("[^\\p{L}\\p{N}]+", "");
                List<String> words = new ArrayList<>();
                for (String w : folded.split("[^\\p{L}\\p{N}]+")) if (!w.isEmpty()) words.add(w);
                List<Item> starts = new ArrayList<>(), contains = new ArrayList<>();
                if (qc.length() < 2 || words.isEmpty()) return starts;
                for (Item it : all) {
                    String c = it.compact();
                    boolean hit = c.contains(qc);
                    if (!hit && words.size() > 1) {
                        hit = true;
                        for (String w : words) if (!c.contains(w)) { hit = false; break; }
                    }
                    if (!hit) continue;
                    // A title (or any word in it, e.g. after "UK:") starting with the search ranks first
                    if (c.startsWith(qc) || it.spaced().contains(" " + words.get(0))) starts.add(it);
                    else if (contains.size() < MAX) contains.add(it);
                    if (starts.size() >= MAX) break;
                }
                starts.addAll(contains); // names that start with the search come first
                return starts.size() > MAX ? new ArrayList<>(starts.subList(0, MAX)) : starts;
            }
        }, new Async.Done<List<Item>>() {
            public void done(final List<Item> hits, Exception e) {
                if (isFinishing() || t != token || hits == null) return;
                boolean focusInside = slots[slot].findFocus() != null;
                slots[slot].removeAllViews();
                found[slot] = hits.size();
                if (!hits.isEmpty()) {
                    Cards.Shape shape = slot == 0 ? Cards.Shape.CHANNEL : Cards.Shape.POSTER;
                    slots[slot].addView(Cards.shelf(SearchActivity.this, TITLES[slot] + "  ·  " + hits.size() + (hits.size() >= MAX ? "+" : ""),
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
        super.onBackPressed();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onDestroy() {
        Async.MAIN.removeCallbacks(pending);
        super.onDestroy();
    }
}
