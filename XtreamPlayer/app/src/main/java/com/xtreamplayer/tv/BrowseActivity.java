package com.xtreamplayer.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;


import java.util.ArrayList;
import java.util.List;

/**
 * Categories on the left, content on the right: a channel list with a Now/Next panel for Live TV,
 * a poster grid for Movies and Series.
 */
@SuppressLint("UnsafeOptInUsageError")
public class BrowseActivity extends Activity implements TopBar.Reselect {
    private static final String FAV = "__fav";
    private static final String ALL = "__all";
    private static final String RECENT = "__recent";
    private static final String CONTINUE = "__continue";
    private static final String SEARCH = "__search";

    // Live TV layout steps (▶ moves right through them, ◀ back)
    private static final int MODE_LIST = 0, MODE_FOCUS = 1, MODE_GUIDE = 2;
    private int mode = MODE_LIST;
    private FrameLayout stage;
    private View catBox, midBox, infoPanel, logoBoxView, textCol;
    private TextView focusLabel;
    private EpgGrid guide;
    private Item guideChannel;
    private XtreamApi.Programme guideProg;

    // Search inside this section
    private android.widget.EditText searchBox;
    private List<Item> searchResults = new ArrayList<>();
    private int searchToken;
    private final Runnable pendingSearch = new Runnable() { public void run() { runSearch(); } };

    private String type;
    private Prefs prefs;
    private XtreamApi api;
    private boolean live;

    private ListView catList;
    private TextView catHint;
    /** Whether you've opened a category yourself (until then the one shown isn't marked in the list). */
    private boolean pickedCategory;
    private AbsListView content;
    private Adapters.TextRows catAdapter;
    private Adapters.ItemAdapter contentAdapter;
    private TextView header, count, emptyText;
    private ProgressBar spinner;

    // live info panel
    private ImageView infoLogo;
    private FrameLayout previewBox;
    private Engine preview;        // the live preview (VLC, like full-screen Live TV)
    private Item previewItem;
    private boolean resumed;
    private final Runnable pendingPreview = new Runnable() { public void run() { startPreview(); } };
    private TextView infoName, infoNowTitle, infoNowTime, infoDesc, infoNext;
    private android.widget.Button infoFav;
    private FrameLayout infoProgress;

    private Item currentCat;
    private int loadToken = 0;
    private final Runnable pendingCat = new Runnable() { public void run() { openSelectedCategory(); } };
    private final Runnable pendingEpg = new Runnable() { public void run() { loadEpgForSelection(); } };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        type = getIntent().getStringExtra("type");
        if (type == null) type = Item.LIVE;
        prefs = new Prefs(this);
        api = new XtreamApi(prefs);
        live = Item.LIVE.equals(type);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Ambient.install(this, live ? Ambient.LIVE : Item.MOVIE.equals(type) ? Ambient.MOVIES : Ambient.SERIES);
        root.addView(TopBar.build(this, live ? TopBar.LIVE : Item.MOVIE.equals(type) ? TopBar.MOVIES : TopBar.SERIES));

        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.setClipChildren(false);
        cols.setPadding(Ui.dp(this, 48), Ui.dp(this, 16), Ui.dp(this, 48), Ui.dp(this, 24));
        if (live) { // Live TV: pieces are positioned by applyMode() so they can glide between layouts
            stage = new FrameLayout(this);
            stage.setClipChildren(false);
            root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            root.addView(cols, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }

        // ---- categories
        catList = new ListView(this);
        catList.setDivider(null);
        catList.setDividerHeight(Ui.dp(this, 2));
        catList.setVerticalScrollBarEnabled(false);
        catList.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        Adapters.prepare(catList, new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                if (has && live && mode != MODE_LIST) setMode(MODE_LIST); // categories always show the full layout
                if (catHint != null) catHint.animate().alpha(has ? 1f : 0f).setDuration(180).start();
            }
        });
        catAdapter = new Adapters.TextRows(this);
        catAdapter.attach(catList);
        // No category marked while you're up in the top bar; the list goes back to the top there
        root.getViewTreeObserver().addOnGlobalFocusChangeListener(new android.view.ViewTreeObserver.OnGlobalFocusChangeListener() {
            public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                boolean tab = TopBar.tabFocused(BrowseActivity.this);
                boolean hide = tab || !pickedCategory;
                if (hide == catAdapter.hideActive) return;
                catAdapter.hideActive = hide;
                if (tab && catAdapter.getCount() > 0) catList.setSelection(0);
                catAdapter.notifyDataSetChanged();
            }
        });
        // the category list with its button hint underneath (shown while you're in the list)
        LinearLayout catCol = new LinearLayout(this);
        catCol.setOrientation(LinearLayout.VERTICAL);
        catCol.setClipChildren(false);
        catCol.setClipToPadding(false);
        final View catWrap = Glider.wrap(this, catList, new Glider[1]);
        // Rows may spill sideways (highlight glow) but not down over the hint underneath
        catWrap.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                int spill = Ui.dp(v.getContext(), 60);
                v.setClipBounds(new android.graphics.Rect(-spill, -spill, (r - l) + spill, b - t));
            }
        });
        catCol.addView(catWrap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        catHint = Ui.text(this, Ui.hasMenuKey() ? "Hold OK  ♥ Favourite      ☰  Hide" : "Hold OK  Favourite or hide", 14, Ui.TEXT2);
        catHint.setGravity(Gravity.CENTER);
        catHint.setAlpha(0f);
        LinearLayout.LayoutParams chl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chl.topMargin = Ui.dp(this, 10);
        catCol.addView(catHint, chl);
        catBox = catCol;
        if (!live) cols.addView(catBox, new LinearLayout.LayoutParams(Ui.dp(this, 300), ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- content column
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setClipChildren(false);
        mid.setPadding(Ui.dp(this, 36), 0, 0, 0);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.BOTTOM);
        header = Ui.oneLine(Ui.title(this, "", 34));
        count = Ui.medium(this, "", 17, Ui.TEXT3);
        count.setPadding(Ui.dp(this, 14), 0, 0, Ui.dp(this, 4));
        head.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));
        head.addView(count);
        head.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), 0, Ui.dp(this, 14));
        head.setVisibility(View.GONE); // the highlighted category on the left already says where you are
        mid.addView(head);

        // Search box (only while "Search" is the chosen category)
        searchBox = Ui.input(this, live ? "Search all channels" : Item.MOVIE.equals(type) ? "Search all movies" : "Search all series",
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchBox.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        searchBox.setImeActionLabel("Search", android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams sbl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbl.bottomMargin = Ui.dp(this, 14);
        mid.addView(searchBox, sbl);

        FrameLayout contentFrame = new FrameLayout(this);
        contentFrame.setClipChildren(false);
        if (live) {
            ListView lv = new ListView(this);
            lv.setDivider(null);
            lv.setDividerHeight(Ui.dp(this, 2));
            lv.setVerticalScrollBarEnabled(false);
            content = lv;
            contentAdapter = new Adapters.Channels(this, prefs);
        } else {
            GridView g = new GridView(this);
            g.setNumColumns(GridView.AUTO_FIT);
            g.setColumnWidth(Ui.dp(this, 178));
            g.setStretchMode(GridView.STRETCH_SPACING_UNIFORM);
            g.setVerticalSpacing(Ui.dp(this, 10));
            g.setVerticalScrollBarEnabled(false);
            g.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 30));
            content = g;
            contentAdapter = new Adapters.Posters(this, prefs);
        }
        Adapters.prepare(content, new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                // Going into the TV guide: the preview keeps playing and just moves to the top
                if (!has && !(live && mode == MODE_GUIDE)) stopPreview();
                else if (live) schedulePreview();
            }
        });
        contentAdapter.attach(content);
        View contentView = live ? Glider.wrap(this, content, new Glider[1]) : content; // channel list glides too
        contentFrame.addView(contentView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        emptyText = Ui.text(this, "", 19, Ui.TEXT2);
        emptyText.setGravity(Gravity.CENTER);
        contentFrame.addView(emptyText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT2));
        contentFrame.addView(spinner, new FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48), Gravity.CENTER));
        mid.addView(contentFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        content.setId(View.generateViewId());

        midBox = mid;
        if (live) {
            infoPanel = buildInfoPanel();
            focusLabel = Ui.oneLine(Ui.medium(this, "", 16, Ui.TEXT2));
            focusLabel.setGravity(Gravity.CENTER_VERTICAL);
            focusLabel.setAlpha(0f);
            guide = new EpgGrid(this);
            guide.setVisibility(View.GONE);
            guide.setListener(guideListener);
            FrameLayout.LayoutParams z = new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.START);
            stage.addView(catBox, new FrameLayout.LayoutParams(z));
            stage.addView(mid, new FrameLayout.LayoutParams(z));
            stage.addView(infoPanel, new FrameLayout.LayoutParams(z));
            stage.addView(focusLabel, new FrameLayout.LayoutParams(z));
            stage.addView(guide, new FrameLayout.LayoutParams(z));
            stage.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    if (r - l != or - ol || b - t != ob - ot) stage.post(new Runnable() { public void run() { applyMode(false); } });
                }
            });
        } else {
            cols.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }

        setContentView(root);
        TopBar.animateIn(this, live ? stage : cols);
        wire();
        loadCategories();
    }

    private LinearLayout.LayoutParams panelParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 400), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.leftMargin = Ui.dp(this, 36);
        return lp;
    }

    private View buildInfoPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackground(Ui.rounded(this, Ui.SURFACE, 0, 22));
        int pad = Ui.dp(this, 28);
        p.setPadding(pad, pad, pad, pad);

        FrameLayout logoBox = new FrameLayout(this);
        logoBox.setBackground(Ui.rounded(this, Ui.SURFACE_LO, 0, 16));
        logoBox.setClipToOutline(true);
        previewBox = logoBox;
        infoLogo = new ImageView(this);
        infoLogo.setBackgroundColor(Ui.DARK ? 0xFF24252C : 0xFFE6E6EB);
        infoLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int lp = Ui.dp(this, 26);
        infoLogo.setPadding(lp, lp, lp, lp);
        logoBox.addView(infoLogo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        p.addView(logoBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 194)));
        logoBoxView = logoBox;
        LinearLayout tc = new LinearLayout(this);
        tc.setOrientation(LinearLayout.VERTICAL);
        textCol = tc;
        p.addView(tc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout pOuter = p;
        p = tc;

        infoName = Ui.lines(Ui.title(this, "", 26), 2);
        infoName.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 20));
        p.addView(infoName);

        TextView nowL = Ui.medium(this, "NOW", 13, Ui.TEXT3);
        nowL.setLetterSpacing(0.12f);
        p.addView(nowL);
        infoNowTitle = Ui.lines(Ui.medium(this, "", 20, Ui.TEXT), 2);
        infoNowTitle.setTypeface(Ui.semibold());
        infoNowTitle.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 8));
        p.addView(infoNowTitle);
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        infoNowTime = Ui.medium(this, "", 15, Ui.TEXT2);
        timeRow.addView(infoNowTime);
        infoProgress = new FrameLayout(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, Ui.dp(this, 4), 1);
        pp.leftMargin = Ui.dp(this, 14);
        timeRow.addView(infoProgress, pp);
        p.addView(timeRow);
        infoDesc = Ui.lines(Ui.text(this, "", 15, Ui.TEXT2), 4);
        infoDesc.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 22));
        p.addView(infoDesc);

        TextView nextL = Ui.medium(this, "UP NEXT", 13, Ui.TEXT3);
        nextL.setLetterSpacing(0.12f);
        p.addView(nextL);
        infoNext = Ui.lines(Ui.medium(this, "", 17, Ui.TEXT2), 2);
        infoNext.setPadding(0, Ui.dp(this, 6), 0, 0);
        p.addView(infoNext);

        // Hints at the bottom of the panel
        p.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        TextView hint = Ui.text(this, "", 14, Ui.TEXT3);
        panelHint = hint;
        setPanelHint(false);
        hint.setPadding(0, Ui.dp(this, 10), 0, 0);
        p.addView(hint);
        return pOuter;
    }

    private void wireSearch() {
        searchBox.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence x, int a, int b, int c) {}
            public void onTextChanged(CharSequence x, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable e) {
                Async.MAIN.removeCallbacks(pendingSearch);
                Async.MAIN.postDelayed(pendingSearch, 300);
            }
        });
        searchBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int action, KeyEvent e) {
                if (e != null && e.getAction() != KeyEvent.ACTION_DOWN) return true;
                Async.MAIN.removeCallbacks(pendingSearch);
                runSearch();
                hideKeyboard();
                if (contentAdapter.getCount() > 0) {
                    content.requestFocus();
                    content.setSelection(0);
                }
                return true;
            }
        });
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
    }

    /** Searches everything in this section (not just the category), best matches first. */
    private void runSearch() {
        final String q = searchBox.getText().toString().trim();
        final int t = ++searchToken;
        if (new Match.Query(q).usable()) spinner.setVisibility(View.VISIBLE);
        Async.run(new Async.Work<List<Item>>() {
            public List<Item> run() throws Exception {
                if (!new Match.Query(q).usable()) return new ArrayList<>();
                List<Item> all = Filters.searchItems(api, prefs, type, Repo.streams(api, type, null));
                return t != searchToken ? null : Match.rank(all, q, 300, null);
            }
        }, new Async.Done<List<Item>>() {
            public void done(List<Item> r, Exception e) {
                if (isFinishing() || t != searchToken || r == null) return;
                spinner.setVisibility(View.GONE);
                searchResults = r;
                if (currentCat != null && SEARCH.equals(currentCat.id)) show(r, false);
            }
        });
    }

    private void wire() {
        wireSearch();
        catList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                Adapters.refresh(catList);
                Async.MAIN.removeCallbacks(pendingCat);
                Async.MAIN.postDelayed(pendingCat, 350);
            }
            public void onNothingSelected(AdapterView<?> p) { Adapters.refresh(catList); }
        });
        catList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                // Hold OK: on Fire TV (☰ hides) it favourites the category straight away;
                // on Google TV / Android TV it asks: favourite or hide.
                if (Ui.hasMenuKey()) toggleFavCategory(pos);
                else categoryMenu(pos);
                return true;
            }
        });
        catList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Async.MAIN.removeCallbacks(pendingCat);
                markPicked();
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
        content.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                Adapters.refresh(content);
                if (pos >= 0 && pos < contentAdapter.getCount()) Ambient.tint(BrowseActivity.this, contentAdapter.getItem(pos).icon);
                if (live) showChannelInfo(pos);
            }
            public void onNothingSelected(AdapterView<?> p) { Adapters.refresh(content); }
        });
    }

    // ------------------------------------------------------------ categories

    private void loadCategories() {
        spinner.setVisibility(View.VISIBLE);
        Async.run(new Async.Work<List<Item>>() {
            public List<Item> run() throws Exception { return Repo.categories(api, type); }
        }, new Async.Done<List<Item>>() {
            public void done(List<Item> cats, Exception e) {
                if (isFinishing()) return;
                spinner.setVisibility(View.GONE);
                rawCats = cats == null ? new ArrayList<Item>() : Filters.categories(prefs, type, cats); // hidden ones left out
                filterVersion = Filters.version();
                List<Item> all = buildCategoryList();
                int firstReal = 4;
                catAdapter.set(all);
                if (e != null) emptyText.setText("Couldn't load categories\n" + Async.message(e));
                int start = !prefs.favs(type).isEmpty() ? 1
                        : !specialList(all.get(2).id).isEmpty() ? 2
                        : (all.size() > firstReal ? firstReal : firstReal - 1);
                // The list starts at the top (Search) whether you arrived by hovering the tab or
                // pressing OK on it; the category shown underneath is still the usual one.
                boolean fromTab = TopBar.tabFocused(BrowseActivity.this);
                catAdapter.hideActive = true; // nothing marked until you pick a category yourself
                if (!fromTab) catList.requestFocus();
                catList.setSelection(0);
                Async.MAIN.postDelayed(new Runnable() { public void run() { if (!isFinishing()) tour(); } }, 600);
                openCategory(all.get(start), start, false);
            }
        });
    }

    private List<Item> rawCats = new ArrayList<>();

    /**
     * Search, Favourites, Recent/Continue, All, then your favourite categories (♥) pinned
     * together, then the rest of the provider's categories.
     */
    private List<Item> buildCategoryList() {
        List<Item> all = new ArrayList<>();
        all.add(new Item(Item.CATEGORY, SEARCH, "Search"));
        all.add(new Item(Item.CATEGORY, FAV, live ? "Favourite Channels" : Item.MOVIE.equals(type) ? "Favourite Movies" : "Favourite Series"));
        if (live) all.add(new Item(Item.CATEGORY, RECENT, "Recent Channels"));
        else all.add(new Item(Item.CATEGORY, CONTINUE, "Continue Watching"));
        all.add(new Item(Item.CATEGORY, ALL, live ? "All Channels" : "All"));
        List<String> favIds = prefs.favCategories(type);
        for (String id : favIds) {
            for (Item c : rawCats) {
                if (!c.id.equals(id)) continue;
                Item pinned = new Item(Item.CATEGORY, c.id, c.name);
                pinned.num = "♥";
                all.add(pinned);
            }
        }
        for (Item c : rawCats) if (!favIds.contains(c.id)) all.add(c);
        return all;
    }

    private int filterVersion;

    /** Quick-start tips for Live TV / Movies & Series (shown once while the tutorial is on). */
    private void tour() {
        String menu = (Ui.hasMenuKey() ? "Press ☰ (or hold OK)" : "Hold OK");
        if (live) {
            Coach.show(this, "live", true,
                    new Coach.Step(catBox, "Categories",
                            "Search is at the top. " + catTip()),
                    new Coach.Step(midBox, "Channels",
                            "Pause on a channel to see a live preview. OK watches it full screen. " + menu + " on a channel to add it to your favourites."),
                    new Coach.Step(infoPanel, "What's on",
                            "The preview, what's on now and what's on next."),
                    new Coach.Step(null, "The TV guide",
                            "Press ▶ from the channel list to open the TV guide. ▲▼ moves through channels, ◀▶ through programmes, "
                                    + "OK watches. ◀ on what's on now, or Back, takes you out again."));
        } else {
            Coach.show(this, "vod", true,
                    new Coach.Step(catBox, "Categories",
                            "Search this section at the top, then your favourites, Continue Watching and everything else. "
                                    + catTip()),
                    new Coach.Step(midBox, "Pick something",
                            "OK opens the details page, with Play, Resume, the trailer and Favourite. " + menu + " here adds it to your favourites."));
        }
    }

    /** ☰ or hold OK on a category: favourite it, or hide it. */
    private void categoryMenu(final int pos) {
        if (pos < 0 || pos >= catAdapter.getCount()) return;
        final Item c = catAdapter.getItem(pos);
        if (c.id.startsWith("__")) return;
        boolean fav = prefs.isFavCategory(type, c.id);
        new android.app.AlertDialog.Builder(this, Ui.DARK ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle(c.name)
                .setItems(new String[]{fav ? "♥  Remove from favourites" : "♡  Favourite",
                        "Hide"}, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) toggleFavCategory(pos);
                        else if (which == 1) hideCategory(c);
                    }
                })
                .show();
    }

    /** What the remote does on a category (Fire TV remotes have ☰, others only hold OK). */
    private static String catTip() {
        return Ui.hasMenuKey()
                ? "Press ☰ on a category to hide it, or hold OK to make it a favourite (it moves near the top with a ♥)."
                : "Hold OK on a category to make it a favourite (it moves near the top with a ♥) or to hide it.";
    }

    /**
     * Focus the search box and bring up the keyboard. The box has only just been made visible,
     * so the keyboard is asked for once it's laid out (and once more shortly after, as some
     * Fire TV keyboards ignore the first request).
     */
    private void openKeyboard() {
        searchBox.requestFocus();
        final android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null) return;
        Runnable show = new Runnable() {
            public void run() {
                if (isFinishing() || searchBox.getVisibility() != View.VISIBLE) return;
                if (!searchBox.hasFocus()) searchBox.requestFocus();
                imm.showSoftInput(searchBox, 0);
            }
        };
        searchBox.post(show);
        searchBox.postDelayed(show, 250);
    }

    private void markPicked() {
        if (pickedCategory) return;
        pickedCategory = true;
        catAdapter.hideActive = TopBar.tabFocused(this);
    }

    /** OK on this section's own tab: to the top of the categories. */
    public void onTabReselected() { focusTopCategory(); }

    private void focusTopCategory() {
        if (catAdapter.getCount() == 0) return;
        Async.MAIN.removeCallbacks(pendingCat);
        catList.setSelection(0);
        catList.requestFocus();
        catList.setSelection(0);
    }

    private void hideAt(int pos) {
        if (pos < 0 || pos >= catAdapter.getCount()) return;
        Item c = catAdapter.getItem(pos);
        if (!c.id.startsWith("__")) hideCategory(c);
    }

    /** No questions asked; it can come back from Settings → Hidden Categories. */
    private void hideCategory(Item c) {
        Filters.setCategoryHidden(prefs, type, c.id, true);
        if (prefs.isFavCategory(type, c.id)) prefs.toggleFavCategory(type, c.id);
        android.widget.Toast.makeText(this, "Hidden: " + c.name + "  (Settings → Hidden Categories to bring it back)", android.widget.Toast.LENGTH_SHORT).show();
        boolean wasOpen = currentCat != null && currentCat.id.equals(c.id);
        reloadCategories(wasOpen);
    }

    /** Re-applies hidden categories / countries (e.g. after changing them in Settings). */
    private void reloadCategories(boolean openAll) {
        String activeId = currentCat == null ? null : currentCat.id;
        int sel = Math.max(0, catList.getSelectedItemPosition());
        try {
            rawCats = Filters.categories(prefs, type, Repo.categories(api, type));
        } catch (Exception ignored) {
        }
        filterVersion = Filters.version();
        List<Item> all = buildCategoryList();
        catAdapter.set(all);
        int active = -1;
        for (int i = 0; i < all.size(); i++) if (activeId != null && all.get(i).id.equals(activeId)) active = i;
        catAdapter.active = active;
        catList.setSelection(Math.min(sel, all.size() - 1));
        if (openAll || active < 0) openCategory(all.get(3), 3, false); // "All"
        else if (ALL.equals(activeId) || SEARCH.equals(activeId)) openCategory(all.get(active), active, false);
        Adapters.refresh(catList);
    }

    private void toggleFavCategory(int pos) {
        if (pos < 0 || pos >= catAdapter.getCount()) return;
        Item c = catAdapter.getItem(pos);
        if (c.id.startsWith("__")) return;
        boolean now = prefs.toggleFavCategory(type, c.id);
        android.widget.Toast.makeText(this, (now ? "Added to favourite categories — " : "Removed from favourite categories — ") + c.name,
                android.widget.Toast.LENGTH_SHORT).show();
        String activeId = currentCat == null ? null : currentCat.id;
        List<Item> all = buildCategoryList();
        catAdapter.set(all);
        int sel = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(c.id)) sel = i;
            if (activeId != null && all.get(i).id.equals(activeId)) catAdapter.active = i;
        }
        catList.setSelection(sel);
        Adapters.refresh(catList);
    }

    private void openSelectedCategory() {
        int pos = catList.getSelectedItemPosition();
        if (pos < 0 || pos >= catAdapter.getCount()) return;
        Item c = catAdapter.getItem(pos);
        if (SEARCH.equals(c.id)) return; // landing on Search does nothing until OK
        markPicked(); // pausing on a category opens it, and from then on it's marked
        if (currentCat != null && c.id.equals(currentCat.id)) {
            catAdapter.notifyDataSetChanged();
            return;
        }
        openCategory(c, pos, false);
    }

    /** Direction of the last category move: 1 = down the list, -1 = up. */
    private int slideDir = 1;

    private void openCategory(final Item cat, int pos, final boolean focusContent) {
        if (catAdapter.active >= 0 && pos != catAdapter.active) slideDir = pos > catAdapter.active ? 1 : -1;
        currentCat = cat;
        catAdapter.active = pos;
        catAdapter.notifyDataSetChanged();
        header.setText(cat.name);
        count.setText("");
        final int token = ++loadToken;
        boolean searching = SEARCH.equals(cat.id);
        searchBox.setVisibility(searching ? View.VISIBLE : View.GONE);
        if (searching) {
            show(searchResults, false);
            if (focusContent) openKeyboard(); // OK or ▶ on "Search": straight into the box with the keyboard
            return;
        }
        if (isSpecial(cat.id)) {
            show(specialList(cat.id), focusContent);
            return;
        }
        contentAdapter.set(null);
        emptyText.setText("");
        spinner.setVisibility(View.VISIBLE);
        Async.run(new Async.Work<List<Item>>() {
            public List<Item> run() throws Exception {
                if (ALL.equals(cat.id)) return Filters.items(api, prefs, type, Repo.streams(api, type, null));
                return Repo.streams(api, type, cat.id);
            }
        }, new Async.Done<List<Item>>() {
            public void done(List<Item> list, Exception e) {
                if (isFinishing() || token != loadToken) return;
                spinner.setVisibility(View.GONE);
                if (e != null) {
                    contentAdapter.set(null);
                    emptyText.setText("Couldn't load this list\n" + Async.message(e));
                    return;
                }
                show(list, focusContent);
            }
        });
    }

    private void show(List<Item> list, boolean focusContent) {
        spinner.setVisibility(View.GONE);
        contentAdapter.set(list);
        // New category slides in from the direction you're moving through the list (like the top bar)
        android.view.animation.DecelerateInterpolator ease = new android.view.animation.DecelerateInterpolator(2f);
        View moving = live ? (View) content.getParent() : content; // live: the list and its highlight move together
        float d = slideDir * Ui.dp(this, 56);
        moving.animate().cancel();
        moving.setAlpha(0f);
        moving.setTranslationY(d);
        moving.animate().alpha(1f).translationY(0f).setDuration(380).setInterpolator(ease).start();
        header.animate().cancel();
        header.setAlpha(0f);
        header.setTranslationY(d * 0.5f);
        header.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(ease).start();
        count.setAlpha(0f);
        count.animate().alpha(1f).setStartDelay(80).setDuration(300).start();
        count.setText(list.size() == 1 ? "1 item" : list.size() + " items");
        boolean fav = currentCat != null && FAV.equals(currentCat.id);
        boolean searching = currentCat != null && SEARCH.equals(currentCat.id);
        String q = searchBox.getText().toString().trim();
        emptyText.setText(list.isEmpty()
                ? (searching ? (q.length() < 2 ? "Type the name of a " + (live ? "channel" : Item.MOVIE.equals(type) ? "film" : "series")
                        : "No results for \"" + q + "\"")
                : (fav ? (Ui.hasMenuKey() ? "No favourites yet\nPress ☰ or hold OK on anything to add it." : "No favourites yet\nHold OK on anything to add it.") : "Nothing here yet")
                ) : "");
        if (live) showChannelInfo(list.isEmpty() ? -1 : 0);
        if (focusContent && !list.isEmpty()) {
            content.requestFocus();
            content.setSelection(0);
        }
    }

    // ------------------------------------------------------------ actions

    private void open(int pos) {
        List<Item> list = contentAdapter.items();
        if (pos < 0 || pos >= list.size()) return;
        Open.item(this, list.get(pos), list);
    }

    private void toggleFav(int pos) {
        if (pos < 0 || pos >= contentAdapter.getCount()) return;
        Item it = contentAdapter.getItem(pos);
        Open.toggleFav(this, prefs, it);
        if (currentCat != null && FAV.equals(currentCat.id)) {
            List<Item> l = prefs.favs(type);
            contentAdapter.set(l);
            count.setText(l.size() + " items");
            if (!l.isEmpty()) content.setSelection(Math.min(pos, l.size() - 1));
        } else {
            contentAdapter.notifyDataSetChanged();
        }
        updateFavButton();
    }

    private void updateFavButton() {
        if (infoFav == null) return;
        int pos = content.getSelectedItemPosition();
        if (pos < 0) pos = 0;
        boolean has = pos < contentAdapter.getCount();
        infoFav.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
        if (has) infoFav.setText(prefs.isFav(contentAdapter.getItem(pos)) ? "♥  In Favourites" : "♡  Add to Favourites");
    }

    private void showChannelInfo(int pos) {
        if (infoName == null) return;
        if (pos < 0 || pos >= contentAdapter.getCount()) {
            infoName.setText("");
            infoNowTitle.setText("");
            infoNowTime.setText("");
            infoDesc.setText("");
            infoNext.setText("");
            infoProgress.removeAllViews();
            infoLogo.setImageDrawable(null);
            return;
        }
        Item it = contentAdapter.getItem(pos);
        infoName.setText(it.name);
        updateFavButton();
        infoNowTitle.setText("");
        infoNowTime.setText("");
        infoDesc.setText("");
        infoNext.setText("");
        infoProgress.removeAllViews();
        ImageLoader.load(infoLogo, it.icon, 400, 240);
        if (previewItem != it) {
            stopPreview();
            if (content.hasFocus()) schedulePreview();
        }
        Async.MAIN.removeCallbacks(pendingEpg);
        Async.MAIN.postDelayed(pendingEpg, 400);
    }

    private void loadEpgForSelection() {
        int pos = content.getSelectedItemPosition();
        if (pos < 0) pos = 0;
        if (pos >= contentAdapter.getCount()) return;
        final Item it = contentAdapter.getItem(pos);
        Async.run(new Async.Work<List<XtreamApi.Programme>>() {
            public List<XtreamApi.Programme> run() { return api.epg(it.id); }
        }, new Async.Done<List<XtreamApi.Programme>>() {
            public void done(List<XtreamApi.Programme> r, Exception e) {
                if (isFinishing()) return;
                int cur = content.getSelectedItemPosition();
                if (cur < 0) cur = 0;
                if (cur >= contentAdapter.getCount() || contentAdapter.getItem(cur) != it) return;
                if (r == null || r.isEmpty()) {
                    infoNowTitle.setText("No guide information");
                    infoNowTitle.setTextColor(Ui.TEXT2);
                    return;
                }
                XtreamApi.Programme now = r.get(0);
                infoNowTitle.setTextColor(Ui.TEXT);
                infoNowTitle.setText(now.title);
                infoNowTime.setText(now.timeRange());
                infoProgress.removeAllViews();
                if (now.start > 0) infoProgress.addView(Cards.progressBar(BrowseActivity.this, now.progress()),
                        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                infoDesc.setText(now.desc == null ? "" : now.desc);
                if (r.size() > 1) {
                    XtreamApi.Programme next = r.get(1);
                    String t = next.timeRange();
                    infoNext.setText((t.isEmpty() ? "" : t.substring(0, Math.min(5, t.length())) + "   ") + next.title);
                }
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && catList.hasFocus()) {
            hideAt(catList.getSelectedItemPosition()); // ☰ hides the category straight away
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU && content.hasFocus()) {
            toggleFav(content.getSelectedItemPosition());
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (Coach.back(this)) return;
        if (live && guide.hasFocus()) {
            exitGuide();
            return;
        }
        if (live && mode == MODE_FOCUS && content.hasFocus()) {
            setMode(MODE_LIST);
            return;
        }
        if (searchBox.hasFocus()) { // close the keyboard and go back to the categories
            hideKeyboard();
            backToCategories();
            return;
        }
        if (content.hasFocus()) {
            backToCategories();
            return;
        }
        TopBar.back(this);
    }

    /**
     * ◀ from the channel list (or the first column of a poster grid) always returns to the
     * categories, landing on the category you're in. Android's automatic focus search
     * didn't always find the list (e.g. from Recent Channels).
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // ▶ from the categories always goes to the top of the list (first channel / poster),
        // not whatever happens to be level with the category
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT && catList.hasFocus()) {
            int pos = catList.getSelectedItemPosition();
            if (pos >= 0 && pos < catAdapter.getCount()) {
                Async.MAIN.removeCallbacks(pendingCat);
                markPicked();
                Item c = catAdapter.getItem(pos);
                if (currentCat == null || !c.id.equals(currentCat.id) || SEARCH.equals(c.id)) {
                    openCategory(c, pos, true); // not open yet (or Search: open the keyboard): open it and go to its first item / the box
                } else if (contentAdapter.getCount() > 0) {
                    content.setSelection(0);
                    content.requestFocus();
                    content.setSelection(0);
                }
                return true;
            }
        }
        // ▼ from the top bar always goes to the top of the categories (Search)
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN && TopBar.tabFocused(this)) {
            focusTopCategory();
            return true;
        }
        // Live TV: ▶ from the channel list = bigger preview, ▶ again = TV guide; ◀ steps back
        if (live && e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && content.hasFocus() && contentAdapter.getCount() > 0) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) {
                enterGuide(); // ▶ from the channel list goes straight to the TV guide
                return true;
            }
            if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT && mode == MODE_FOCUS) {
                setMode(MODE_LIST);
                return true;
            }
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT && content.hasFocus()) {
            int pos = content.getSelectedItemPosition();
            boolean firstColumn = !(content instanceof GridView)
                    || pos < 0 || pos % Math.max(1, ((GridView) content).getNumColumns()) == 0;
            if (firstColumn) {
                backToCategories();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void backToCategories() {
        int a = catAdapter.active;
        if (a >= 0 && a < catAdapter.getCount()) {
            // Select the current category exactly where it sits on screen (setSelection alone
            // scrolls it to the top of the list).
            View row = catList.getChildAt(a - catList.getFirstVisiblePosition());
            int top = row != null ? row.getTop() : catList.getPaddingTop();
            catList.setSelectionFromTop(a, top);
        }
        catList.requestFocus();
        Adapters.refresh(catList);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (filterVersion != 0 && filterVersion != Filters.version() && catAdapter.getCount() > 0) reloadCategories(false); // changed in Settings
        if (currentCat != null && isSpecial(currentCat.id)) {
            contentAdapter.set(specialList(currentCat.id));
        } else {
            contentAdapter.notifyDataSetChanged();
        }
        if (live && (content.hasFocus() || guide.hasFocus())) schedulePreview();
        if (live && guide.getVisibility() == View.VISIBLE) guide.listingsChanged();
    }

    @Override
    protected void onPause() {
        resumed = false;
        releasePreview(); // free the connection before the full-screen player starts
        super.onPause();
    }

    private boolean isSpecial(String id) {
        return FAV.equals(id) || RECENT.equals(id) || CONTINUE.equals(id);
    }

    private List<Item> specialList(String id) {
        if (FAV.equals(id)) return prefs.favs(type);
        if (RECENT.equals(id)) return prefs.history(true);
        if (CONTINUE.equals(id)) return Item.MOVIE.equals(type) ? prefs.continueMovies() : prefs.continueSeries();
        return new ArrayList<>();
    }

    // ------------------------------------------------------------ Live TV layouts and guide

    private TextView panelHint;

    /** The hint under the programme info changes with the layout. */
    private void setPanelHint(boolean inGuide) {
        if (panelHint == null) return;
        String menu = Ui.hasMenuKey() ? "☰" : "Hold OK";
        panelHint.setText(inGuide
                ? "OK  Watch      " + menu + "  Full programme details      ◀  Back to channels"
                : "▶  TV guide      " + menu + "  Favourite");
    }

    /** Full details for a programme (the info above can be cut short). */
    private void showProgrammeDetails(int row, XtreamApi.Programme p) {
        final List<Item> l = contentAdapter.items();
        if (row < 0 || row >= l.size()) return;
        final Item ch = l.get(row);
        StringBuilder b = new StringBuilder();
        b.append(ch.name);
        if (p != null) {
            long now = System.currentTimeMillis() / 1000L;
            b.append("\n").append(p.timeRange());
            if (p.start <= now && now < p.stop) b.append("   ·   On now, ").append((p.stop - now) / 60).append(" min left");
            else if (p.start > now) {
                long m = (p.start - now + 59) / 60;
                b.append("   ·   Starts in ").append(m >= 60 ? (m / 60) + " hr " + (m % 60) + " min" : m + " min");
            }
            if (p.desc != null && !p.desc.isEmpty()) b.append("\n\n").append(p.desc);
        } else {
            b.append("\n\nNo guide information for this time.");
        }
        new android.app.AlertDialog.Builder(this, Ui.DARK ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle(p == null ? ch.name : p.title)
                .setMessage(b.toString())
                .setPositiveButton("Watch " + ch.name, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { Open.item(BrowseActivity.this, ch, l); }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void setMode(int m) {
        if (m == mode) return;
        mode = m;
        applyMode(true);
    }

    /**
     * Positions the pieces for the current step. Bounds changes glide (preview moving to the
     * top, categories folding away to the left, guide sliding over the list).
     */
    private void applyMode(boolean animate) {
        if (stage == null || stage.getWidth() == 0) return;
        int W = stage.getWidth(), H = stage.getHeight();
        int pad = Ui.dp(this, 48), gap = Ui.dp(this, 36), top = Ui.dp(this, 16), bottom = Ui.dp(this, 24);
        int catW = Ui.dp(this, 300), infoW = Ui.dp(this, 400), fullH = H - top - bottom;
        if (animate) {
            android.transition.ChangeBounds cb = new android.transition.ChangeBounds();
            cb.setDuration(420);
            cb.setInterpolator(new android.view.animation.DecelerateInterpolator(2.2f));
            // (TransitionListenerAdapter only exists from Android 8; Fire OS 5/6 sticks are older)
            cb.addListener(new android.transition.Transition.TransitionListener() {
                @Override public void onTransitionEnd(android.transition.Transition t) {
                    Adapters.refresh(content); // re-seat the gliding highlights
                    Adapters.refresh(catList);
                }
                @Override public void onTransitionStart(android.transition.Transition t) {}
                @Override public void onTransitionCancel(android.transition.Transition t) {}
                @Override public void onTransitionPause(android.transition.Transition t) {}
                @Override public void onTransitionResume(android.transition.Transition t) {}
            });
            android.transition.TransitionManager.beginDelayedTransition(stage, cb);
        }
        LinearLayout info = (LinearLayout) infoPanel;
        LinearLayout.LayoutParams logoLp = (LinearLayout.LayoutParams) logoBoxView.getLayoutParams();
        LinearLayout.LayoutParams textLp = (LinearLayout.LayoutParams) textCol.getLayoutParams();
        if (mode == MODE_LIST) {
            place(catBox, pad, top, catW, fullH);
            place(midBox, pad + catW + gap, top, W - 2 * pad - catW - infoW - 2 * gap, fullH);
            place(infoPanel, W - pad - infoW, top, infoW, fullH);
            place(focusLabel, pad, top, W - 2 * pad, Ui.dp(this, 44));
            place(guide, pad, top, W - 2 * pad, fullH);
            info.setOrientation(LinearLayout.VERTICAL);
            logoLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            logoLp.height = Ui.dp(this, 194);
            textLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            textLp.height = 0;
            textLp.weight = 1;
            textLp.leftMargin = 0;
            fade(catBox, true);
            fade(focusLabel, false);
            fade(midBox, true);
            fade(guide, false);
        } else {
            int infoH = Math.round(H * 0.38f);
            int inner = infoH - 2 * Ui.dp(this, 28);
            int labelH = guide.headerHeight();
            int listY = top + infoH + Ui.dp(this, 12);
            place(catBox, pad - catW - gap, top, catW, fullH); // folds away off the left edge
            place(infoPanel, pad, top, W - 2 * pad, infoH);
            boolean g = mode == MODE_GUIDE;
            int hintH = Ui.dp(this, 40);
            if (g) { // the guide, with its button hints in a line along the bottom where they're always visible
                place(guide, pad, listY, W - 2 * pad, H - listY - bottom - hintH);
                place(focusLabel, pad + Ui.dp(this, 8), H - bottom - hintH + Ui.dp(this, 6), W - 2 * pad, hintH);
                String menu = Ui.hasMenuKey() ? "☰" : "Hold OK";
                focusLabel.setText("OK  Watch        " + menu + "  Full programme details        ▲▼  Channels        ◀ ▶  Programmes        Back  Channel list");
            } else {
                place(focusLabel, pad + Ui.dp(this, 8), listY, W - 2 * pad, labelH);
                place(guide, pad, listY, W - 2 * pad, H - listY - bottom);
                focusLabel.setText((currentCat == null ? "" : currentCat.name + "      ") + "◀  Categories      ▶  TV Guide");
            }
            place(midBox, pad, listY + labelH, W - 2 * pad, H - listY - labelH - bottom);
            info.setOrientation(LinearLayout.HORIZONTAL);
            logoLp.width = Math.round(inner * 16f / 9f);
            logoLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            textLp.width = 0;
            textLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            textLp.weight = 1;
            textLp.leftMargin = Ui.dp(this, 32);
            fade(catBox, false);
            fade(focusLabel, true);
            fade(midBox, !g);
            fade(guide, g);
        }
        setPanelHint(mode == MODE_GUIDE);
        logoBoxView.setLayoutParams(logoLp);
        textCol.setLayoutParams(textLp);
        info.requestLayout();
    }

    private void place(View v, int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        lp.leftMargin = x;
        lp.topMargin = y;
        lp.width = Math.max(0, w);
        lp.height = Math.max(0, h);
        v.setLayoutParams(lp);
    }

    private void fade(final View v, final boolean show) {
        v.animate().cancel();
        if (show) {
            if (v.getVisibility() != View.VISIBLE) {
                v.setAlpha(0f);
                v.setVisibility(View.VISIBLE);
            }
            v.animate().alpha(1f).setDuration(320).withEndAction(null).start();
        } else if (v.getVisibility() == View.VISIBLE) {
            v.animate().alpha(0f).setDuration(220).withEndAction(new Runnable() {
                public void run() { if (v.getAlpha() == 0f) v.setVisibility(v == midBox || v == catBox ? View.INVISIBLE : View.GONE); }
            }).start();
        }
    }

    /** The channel list becomes the TV guide, lined up row for row. */
    private void enterGuide() {
        int sel = Math.max(0, content.getSelectedItemPosition());
        View first = content.getChildAt(0);
        int rowH = first == null ? 0 : first.getHeight() + ((ListView) content).getDividerHeight();
        guideChannel = null;
        guideProg = null;
        guide.setChannels(contentAdapter.items(), sel, content.getFirstVisiblePosition(), rowH);
        setMode(MODE_GUIDE);
        guide.setVisibility(View.VISIBLE);
        guide.requestFocus();
    }

    private void exitGuide() {
        int sel = guide.selected();
        int topRow = guide.firstVisible();
        setMode(MODE_LIST); // back to categories + channels + preview
        content.requestFocus();
        View first = content.getChildAt(0);
        int rowH = first == null ? 0 : first.getHeight() + ((ListView) content).getDividerHeight();
        ((ListView) content).setSelectionFromTop(sel, Math.max(0, (sel - topRow) * rowH));
        showChannelInfo(sel);
    }

    private final EpgGrid.Listener guideListener = new EpgGrid.Listener() {
        public void onHighlight(int row, Item ch, XtreamApi.Programme p) {
            boolean newChannel = ch != guideChannel;
            if (!newChannel && p == guideProg) return;
            guideChannel = ch;
            guideProg = p;
            infoName.setText(ch.name);
            infoProgress.removeAllViews();
            if (p == null) {
                infoNowTitle.setText(Guide.get(ch.id) == null ? "Loading guide…" : "No guide information");
                infoNowTitle.setTextColor(Ui.TEXT2);
                infoNowTime.setText("");
                infoDesc.setText("");
                infoNext.setText("");
            } else {
                long now = System.currentTimeMillis() / 1000L;
                boolean onNow = p.start <= now && now < p.stop;
                infoNowTitle.setTextColor(Ui.TEXT);
                infoNowTitle.setText(p.title);
                infoNowTime.setText(p.timeRange() + (onNow ? "" : "   ·   later"));
                if (onNow) infoProgress.addView(Cards.progressBar(BrowseActivity.this, p.progress()),
                        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                infoDesc.setText(p.desc == null ? "" : p.desc);
                infoNext.setText("");
                List<XtreamApi.Programme> l = Guide.get(ch.id);
                if (l != null) {
                    for (int i = 0; i < l.size() - 1; i++) {
                        if (l.get(i) == p) {
                            XtreamApi.Programme n = l.get(i + 1);
                            String t = n.timeRange();
                            infoNext.setText((t.isEmpty() ? "" : t.substring(0, Math.min(5, t.length())) + "   ") + n.title);
                        }
                    }
                }
            }
            if (newChannel) {
                Ambient.tint(BrowseActivity.this, ch.icon);
                boolean alreadyPlaying = previewItem != null && previewItem.key().equals(ch.key());
                if (!alreadyPlaying) { // same channel as the list: carry on without restarting
                    ImageLoader.load(infoLogo, ch.icon, 400, 240);
                    stopPreview();
                    schedulePreview();
                }
            }
        }

        public void onPlay(int row) {
            List<Item> l = contentAdapter.items();
            if (row >= 0 && row < l.size()) Open.item(BrowseActivity.this, l.get(row), l);
        }

        public void onExit() { exitGuide(); }

        public void onDetails(int row, XtreamApi.Programme p) { showProgrammeDetails(row, p); }

        public void onNeedListings(List<Item> channels) {
            Guide.load(api, channels, new Runnable() {
                public void run() { if (!isFinishing()) guide.listingsChanged(); }
            });
        }
    };

    // ------------------------------------------------------------ live preview

    private void schedulePreview() {
        Async.MAIN.removeCallbacks(pendingPreview);
        if (!live || prefs.previewMode() == 0) return;
        Async.MAIN.postDelayed(pendingPreview, 450); // once you pause on a channel (not while scrolling past)
    }

    private final Engine.Listener previewListener = new Engine.Listener() {
        public void onReady() {
            // Give VLC a moment to draw its first picture, then uncover it
            infoLogo.animate().alpha(0f).setStartDelay(250).setDuration(250).start();
        }
        public void onEnded() {}
        public void onError(String what, boolean behind) { stopPreview(); }
        public void onPlayingChanged(boolean playing) {}
        public void onBuffering(boolean buffering) {}
        public void onTracksChanged() {}
    };

    private void startPreview() {
        boolean inGuide = guide != null && guide.hasFocus();
        if (!resumed || !(content.hasFocus() || inGuide)) return;
        Item it;
        if (inGuide) {
            it = guideChannel;
            if (it == null) return;
        } else {
            int pos = content.getSelectedItemPosition();
            if (pos < 0 || pos >= contentAdapter.getCount()) return;
            it = contentAdapter.getItem(pos);
        }
        if (it == previewItem && preview != null) return;
        if (preview == null) {
            // VLC, like full-screen Live TV (copes with the channels Android's player froze on);
            // Android's player only if that was picked for everything in Settings.
            boolean vlc = prefs.engineMode() != 1;
            try {
                preview = vlc ? new VlcEngine(this, prefs, previewListener) : new ExoEngine(this, prefs, previewListener);
            } catch (Throwable t) {
                preview = new ExoEngine(this, prefs, previewListener);
            }
            previewBox.addView(preview.view(), 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        previewItem = it;
        infoLogo.animate().cancel();
        infoLogo.setAlpha(1f);
        preview.setVolume(prefs.previewMode() == 2 ? 1f : 0f);
        try {
            preview.load(api.playUrl(it, prefs.liveFormat()), 0);
        } catch (Throwable t) {
            stopPreview();
        }
    }

    private void stopPreview() {
        Async.MAIN.removeCallbacks(pendingPreview);
        previewItem = null;
        if (preview != null) preview.stopPlayback();
        if (infoLogo != null) {
            infoLogo.animate().cancel();
            infoLogo.setAlpha(1f);
        }
    }

    private void releasePreview() {
        stopPreview();
        if (preview != null) {
            previewBox.removeView(preview.view());
            preview.release();
            preview = null;
        }
    }

    @Override
    protected void onDestroy() {
        Async.MAIN.removeCallbacks(pendingCat);
        Async.MAIN.removeCallbacks(pendingEpg);
        Async.MAIN.removeCallbacks(pendingSearch);
        releasePreview();
        super.onDestroy();
    }
}
