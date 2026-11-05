package com.xtreamplayer.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Categories on the left, content in the middle (list for Live TV, poster grid for
 * Movies/Series) and, for Live TV, a now/next panel on the right.
 */
public class BrowseActivity extends Activity {
    private static final String FAV = "__fav";
    private static final String ALL = "__all";

    private String type;
    private Prefs prefs;
    private XtreamApi api;

    private ListView catList;
    private AbsListView content;
    private Adapters.TextRows catAdapter;
    private Adapters.ItemAdapter contentAdapter;
    private TextView header, emptyText;
    private ProgressBar spinner;
    private EditText search;

    // live info panel
    private ImageView infoLogo;
    private TextView infoName, infoNow, infoNext;

    private final Map<String, List<Item>> cache = new HashMap<>();
    private Item currentCat;
    private int loadToken = 0;
    private final Runnable pendingCat = new Runnable() { public void run() { openSelectedCategory(); } };
    private final Runnable pendingEpg = new Runnable() { public void run() { loadEpgForSelection(); } };
    private final Runnable pendingSearch = new Runnable() { public void run() { applySearch(); } };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        type = getIntent().getStringExtra("type");
        if (type == null) type = Item.LIVE;
        prefs = new Prefs(this);
        api = new XtreamApi(prefs);
        boolean live = Item.LIVE.equals(type);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        int pad = Ui.dp(this, 20);
        cols.setPadding(pad, pad, pad, pad);
        root.addView(cols, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- left: title, search, categories
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setBackground(Ui.rounded(this, Ui.PANEL, 0, 14));
        int lp = Ui.dp(this, 12);
        left.setPadding(lp, lp, lp, lp);
        String title = live ? "Live TV" : Item.MOVIE.equals(type) ? "Movies" : "Series";
        TextView t = Ui.title(this, title, 26);
        t.setPadding(Ui.dp(this, 6), 0, 0, Ui.dp(this, 10));
        left.addView(t);
        search = Ui.input(this, "Search " + title.toLowerCase(Locale.UK) + "…", InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        left.addView(search);
        catList = new ListView(this);
        catList.setSelector(Ui.listSelector(this));
        catList.setDivider(null);
        catList.setPadding(0, Ui.dp(this, 8), 0, 0);
        catList.setClipToPadding(false);
        catAdapter = new Adapters.TextRows(this);
        catList.setAdapter(catAdapter);
        left.addView(catList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        cols.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, live ? 1f : 1.05f));

        // ---- middle: content
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 4), 0);
        header = Ui.oneLine(Ui.text(this, "", 18, Ui.MUTED));
        header.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), 0, Ui.dp(this, 10));
        mid.addView(header);
        FrameLayout contentFrame = new FrameLayout(this);
        if (live) {
            ListView lv = new ListView(this);
            lv.setSelector(Ui.listSelector(this));
            lv.setDivider(null);
            contentAdapter = new Adapters.Channels(this, prefs);
            lv.setAdapter(contentAdapter);
            content = lv;
        } else {
            GridView g = new GridView(this);
            g.setNumColumns(GridView.AUTO_FIT);
            g.setColumnWidth(Ui.dp(this, 152));
            g.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            g.setVerticalSpacing(Ui.dp(this, 6));
            g.setSelector(Ui.gridSelector(this));
            g.setDrawSelectorOnTop(true);
            contentAdapter = new Adapters.Posters(this, prefs);
            g.setAdapter(contentAdapter);
            content = g;
        }
        contentFrame.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        emptyText = Ui.text(this, "", 18, Ui.MUTED);
        emptyText.setGravity(Gravity.CENTER);
        contentFrame.addView(emptyText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        spinner = new ProgressBar(this);
        contentFrame.addView(spinner, new FrameLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56), Gravity.CENTER));
        mid.addView(contentFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        TextView hint = Ui.oneLine(Ui.text(this, "OK: play   ·   ☰ Menu or hold OK: add/remove favourite   ·   Back: categories", 13, Ui.MUTED));
        hint.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), 0, 0);
        mid.addView(hint);
        cols.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, live ? 1.7f : 3.4f));

        // ---- right: now/next (live only)
        if (live) {
            LinearLayout right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setBackground(Ui.rounded(this, Ui.PANEL, 0, 14));
            int rp = Ui.dp(this, 18);
            right.setPadding(rp, rp, rp, rp);
            infoLogo = new ImageView(this);
            infoLogo.setScaleType(ImageView.ScaleType.FIT_START);
            right.addView(infoLogo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 90)));
            infoName = Ui.title(this, "", 22);
            infoName.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
            right.addView(infoName);
            TextView nowL = Ui.text(this, "NOW", 12, Ui.ACCENT);
            right.addView(nowL);
            infoNow = Ui.text(this, "", 17, Ui.TEXT);
            infoNow.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 14));
            right.addView(infoNow);
            right.addView(Ui.text(this, "NEXT", 12, Ui.ACCENT));
            infoNext = Ui.text(this, "", 17, Ui.MUTED);
            infoNext.setPadding(0, Ui.dp(this, 2), 0, 0);
            right.addView(infoNext);
            cols.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f));
        }

        setContentView(root);
        wire();
        loadCategories();
    }

    private void wire() {
        catList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                Async.MAIN.removeCallbacks(pendingCat);
                Async.MAIN.postDelayed(pendingCat, 400);
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        catList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Async.MAIN.removeCallbacks(pendingCat);
                openCategory(catAdapter.getItem(pos), pos, true);
            }
        });
        content.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) { open(pos); }
        });
        content.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                toggleFav(pos);
                return true;
            }
        });
        if (infoName != null) {
            content.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    Item it = contentAdapter.getItem(pos);
                    infoName.setText(it.name);
                    infoNow.setText("…");
                    infoNext.setText("");
                    ImageLoader.load(infoLogo, it.icon, 300, 180);
                    Async.MAIN.removeCallbacks(pendingEpg);
                    Async.MAIN.postDelayed(pendingEpg, 450);
                }
                public void onNothingSelected(AdapterView<?> p) {}
            });
        }
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                Async.MAIN.removeCallbacks(pendingSearch);
                Async.MAIN.postDelayed(pendingSearch, 600);
            }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int action, KeyEvent e) {
                Async.MAIN.removeCallbacks(pendingSearch);
                applySearch();
                content.requestFocus();
                return false;
            }
        });
    }

    // ------------------------------------------------------------ categories

    private void loadCategories() {
        spinner.setVisibility(View.VISIBLE);
        Async.run(new Async.Work<List<Item>>() {
            public List<Item> run() throws Exception { return api.categories(type); }
        }, new Async.Done<List<Item>>() {
            public void done(List<Item> cats, Exception e) {
                if (isFinishing()) return;
                spinner.setVisibility(View.GONE);
                List<Item> all = new ArrayList<>();
                Item fav = new Item(Item.CATEGORY, FAV, "★ Favourites");
                all.add(fav);
                all.add(new Item(Item.CATEGORY, ALL, "All"));
                if (cats != null) all.addAll(cats);
                catAdapter.set(all);
                if (e != null) {
                    emptyText.setText("Couldn't load categories:\n" + Async.message(e));
                }
                // Start on favourites if there are any, otherwise the first real category.
                int start = !prefs.favs(type).isEmpty() ? 0 : (all.size() > 2 ? 2 : 1);
                catList.setSelection(start);
                catList.requestFocus();
                openCategory(all.get(start), start, false);
            }
        });
    }

    private void openSelectedCategory() {
        int pos = catList.getSelectedItemPosition();
        if (pos < 0 || pos >= catAdapter.getCount()) return;
        Item c = catAdapter.getItem(pos);
        if (currentCat != null && c.id.equals(currentCat.id) && search.getText().length() == 0) return;
        openCategory(c, pos, false);
    }

    private void openCategory(final Item cat, int pos, final boolean focusContent) {
        if (search.getText().length() > 0) {
            search.setText("");
            Async.MAIN.removeCallbacks(pendingSearch);
        }
        currentCat = cat;
        catAdapter.active = pos;
        catAdapter.notifyDataSetChanged();
        header.setText(cat.name);
        loadList(cat.id, new Done() {
            public void done(List<Item> list) {
                show(cat.name, list);
                if (focusContent && !list.isEmpty()) {
                    content.requestFocus();
                    content.setSelection(0);
                }
            }
        });
    }

    interface Done { void done(List<Item> list); }

    private void loadList(final String catId, final Done done) {
        final int token = ++loadToken;
        if (FAV.equals(catId)) {
            done.done(prefs.favs(type));
            return;
        }
        List<Item> cached = cache.get(catId);
        if (cached != null) {
            done.done(cached);
            return;
        }
        contentAdapter.set(null);
        emptyText.setText("");
        spinner.setVisibility(View.VISIBLE);
        Async.run(new Async.Work<List<Item>>() {
            public List<Item> run() throws Exception { return api.streams(type, ALL.equals(catId) ? null : catId); }
        }, new Async.Done<List<Item>>() {
            public void done(List<Item> list, Exception e) {
                if (isFinishing() || token != loadToken) return;
                spinner.setVisibility(View.GONE);
                if (e != null) {
                    contentAdapter.set(null);
                    emptyText.setText("Couldn't load this list:\n" + Async.message(e));
                    return;
                }
                cache.put(catId, list);
                done.done(list);
            }
        });
    }

    private void show(String title, List<Item> list) {
        spinner.setVisibility(View.GONE);
        contentAdapter.set(list);
        header.setText(title + "   ·   " + list.size() + (list.size() == 1 ? " item" : " items"));
        emptyText.setText(list.isEmpty()
                ? (currentCat != null && FAV.equals(currentCat.id) && search.getText().length() == 0
                    ? "No favourites yet.\nPress ☰ Menu (or hold OK) on anything to add it."
                    : "Nothing here")
                : "");
    }

    // ------------------------------------------------------------ search

    private void applySearch() {
        final String q = search.getText().toString().trim().toLowerCase(Locale.UK);
        if (q.isEmpty()) {
            if (currentCat != null) openCategory(currentCat, catAdapter.active, false);
            return;
        }
        catAdapter.active = -1;
        catAdapter.notifyDataSetChanged();
        header.setText("Searching…");
        loadList(ALL, new Done() {
            public void done(List<Item> all) {
                List<Item> hits = new ArrayList<>();
                for (Item it : all) if (it.name.toLowerCase(Locale.UK).contains(q)) hits.add(it);
                show("Search: \"" + q + "\"", hits);
            }
        });
    }

    // ------------------------------------------------------------ actions

    private void open(int pos) {
        List<Item> list = contentAdapter.items();
        if (pos < 0 || pos >= list.size()) return;
        Item it = list.get(pos);
        if (Item.SERIES.equals(it.type)) {
            Intent i = new Intent(this, SeriesActivity.class);
            i.putExtra("series", it.toJson().toString());
            startActivity(i);
            return;
        }
        if (Item.LIVE.equals(it.type)) {
            Queue.set(list);
            startActivity(new Intent(this, PlayerActivity.class).putExtra("index", pos));
        } else {
            List<Item> single = new ArrayList<>();
            single.add(it);
            Queue.set(single);
            startActivity(new Intent(this, PlayerActivity.class).putExtra("index", 0));
        }
    }

    private void toggleFav(int pos) {
        if (pos < 0 || pos >= contentAdapter.getCount()) return;
        Item it = contentAdapter.getItem(pos);
        boolean now = prefs.toggleFav(it);
        Toast.makeText(this, (now ? "Added to favourites: " : "Removed from favourites: ") + it.name, Toast.LENGTH_SHORT).show();
        if (currentCat != null && FAV.equals(currentCat.id) && search.getText().length() == 0) {
            show(currentCat.name, prefs.favs(type));
            if (contentAdapter.getCount() > 0) content.setSelection(Math.min(pos, contentAdapter.getCount() - 1));
        } else {
            contentAdapter.notifyDataSetChanged();
        }
    }

    private void loadEpgForSelection() {
        int pos = content.getSelectedItemPosition();
        if (pos < 0 || pos >= contentAdapter.getCount()) return;
        final Item it = contentAdapter.getItem(pos);
        Async.run(new Async.Work<String[]>() {
            public String[] run() { return api.shortEpg(it.id); }
        }, new Async.Done<String[]>() {
            public void done(String[] r, Exception e) {
                if (isFinishing()) return;
                int cur = content.getSelectedItemPosition();
                if (cur < 0 || cur >= contentAdapter.getCount() || contentAdapter.getItem(cur) != it) return;
                infoNow.setText(r == null || r[0] == null ? "No guide information" : r[0]);
                infoNext.setText(r == null || r[1] == null ? "" : r[1]);
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && content.hasFocus()) {
            toggleFav(content.getSelectedItemPosition());
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (content.hasFocus()) {
            catList.requestFocus();
            return;
        }
        if (search.getText().length() > 0) {
            search.setText("");
            catList.requestFocus();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        contentAdapter.notifyDataSetChanged(); // refresh ★ marks after returning
    }

    @Override
    protected void onDestroy() {
        Async.MAIN.removeCallbacks(pendingCat);
        Async.MAIN.removeCallbacks(pendingEpg);
        Async.MAIN.removeCallbacks(pendingSearch);
        super.onDestroy();
    }
}
