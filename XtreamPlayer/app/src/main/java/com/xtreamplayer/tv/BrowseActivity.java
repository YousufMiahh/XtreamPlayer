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

import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Categories on the left, content on the right: a channel list with a Now/Next panel for Live TV,
 * a poster grid for Movies and Series.
 */
@SuppressLint("UnsafeOptInUsageError")
public class BrowseActivity extends Activity {
    private static final String FAV = "__fav";
    private static final String ALL = "__all";
    private static final String RECENT = "__recent";
    private static final String CONTINUE = "__continue";

    private String type;
    private Prefs prefs;
    private XtreamApi api;
    private boolean live;

    private ListView catList;
    private AbsListView content;
    private Adapters.TextRows catAdapter;
    private Adapters.ItemAdapter contentAdapter;
    private TextView header, count, emptyText;
    private ProgressBar spinner;

    // live info panel
    private ImageView infoLogo;
    private PlayerView previewView;
    private ExoPlayer preview;
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
        root.addView(cols, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // ---- categories
        catList = new ListView(this);
        catList.setDivider(null);
        catList.setDividerHeight(Ui.dp(this, 2));
        catList.setVerticalScrollBarEnabled(false);
        catList.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        Adapters.prepare(catList);
        catAdapter = new Adapters.TextRows(this);
        catAdapter.attach(catList);
        cols.addView(Glider.wrap(this, catList, new Glider[1]), new LinearLayout.LayoutParams(Ui.dp(this, 300), ViewGroup.LayoutParams.MATCH_PARENT));

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
                if (!has) stopPreview();
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

        cols.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        if (live) cols.addView(buildInfoPanel(), panelParams());

        setContentView(root);
        TopBar.animateIn(this, cols);
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
        previewView = new PlayerView(this);
        previewView.setUseController(false);
        previewView.setShutterBackgroundColor(0x00000000);
        previewView.setVisibility(View.INVISIBLE);
        logoBox.addView(previewView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        infoLogo = new ImageView(this);
        infoLogo.setBackgroundColor(Ui.DARK ? 0xFF24252C : 0xFFE6E6EB);
        infoLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int lp = Ui.dp(this, 26);
        infoLogo.setPadding(lp, lp, lp, lp);
        logoBox.addView(infoLogo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        p.addView(logoBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 194)));

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

        // Push the button to the bottom of the panel
        p.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        infoFav = Ui.button(this, "♡  Add to Favourites");
        infoFav.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.sp(this, 16));
        infoFav.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int pos = content.getSelectedItemPosition();
                toggleFav(pos);
                updateFavButton();
            }
        });
        infoFav.setNextFocusLeftId(content.getId());
        p.addView(infoFav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return p;
    }

    private void wire() {
        catList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                Adapters.refresh(catList);
                Async.MAIN.removeCallbacks(pendingCat);
                Async.MAIN.postDelayed(pendingCat, 350);
            }
            public void onNothingSelected(AdapterView<?> p) { Adapters.refresh(catList); }
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
                List<Item> all = new ArrayList<>();
                all.add(new Item(Item.CATEGORY, FAV, "Favourites"));
                if (live) all.add(new Item(Item.CATEGORY, RECENT, "Recent Channels"));
                else all.add(new Item(Item.CATEGORY, CONTINUE, "Continue Watching"));
                all.add(new Item(Item.CATEGORY, ALL, live ? "All Channels" : "All"));
                int firstReal = all.size();
                if (cats != null) all.addAll(cats);
                catAdapter.set(all);
                if (e != null) emptyText.setText("Couldn't load categories\n" + Async.message(e));
                int start = !prefs.favs(type).isEmpty() ? 0
                        : !specialList(all.get(1).id).isEmpty() ? 1
                        : (all.size() > firstReal ? firstReal : firstReal - 1);
                if (!TopBar.tabFocused(BrowseActivity.this)) catList.requestFocus();
                catList.setSelection(start);
                openCategory(all.get(start), start, false);
            }
        });
    }

    private void openSelectedCategory() {
        int pos = catList.getSelectedItemPosition();
        if (pos < 0 || pos >= catAdapter.getCount()) return;
        Item c = catAdapter.getItem(pos);
        if (currentCat != null && c.id.equals(currentCat.id)) return;
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
        if (isSpecial(cat.id)) {
            show(specialList(cat.id), focusContent);
            return;
        }
        contentAdapter.set(null);
        emptyText.setText("");
        spinner.setVisibility(View.VISIBLE);
        Async.run(new Async.Work<List<Item>>() {
            public List<Item> run() throws Exception { return Repo.streams(api, type, ALL.equals(cat.id) ? null : cat.id); }
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
        emptyText.setText(list.isEmpty()
                ? (fav ? "No favourites yet\nPress ☰ or hold OK on anything to add it." : "Nothing here yet")
                : "");
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
        if (keyCode == KeyEvent.KEYCODE_MENU && content.hasFocus()) {
            toggleFav(content.getSelectedItemPosition());
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
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
        if (currentCat != null && isSpecial(currentCat.id)) {
            contentAdapter.set(specialList(currentCat.id));
        } else {
            contentAdapter.notifyDataSetChanged();
        }
        if (live && content.hasFocus()) schedulePreview();
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

    // ------------------------------------------------------------ live preview

    private void schedulePreview() {
        Async.MAIN.removeCallbacks(pendingPreview);
        if (!live || prefs.previewMode() == 0) return;
        Async.MAIN.postDelayed(pendingPreview, 1100); // only once you pause on a channel
    }

    private void startPreview() {
        if (!resumed || !content.hasFocus()) return;
        int pos = content.getSelectedItemPosition();
        if (pos < 0 || pos >= contentAdapter.getCount()) return;
        Item it = contentAdapter.getItem(pos);
        if (it == previewItem && preview != null) return;
        if (preview == null) {
            preview = PlayerFactory.create(this, prefs);
            preview.addListener(new Player.Listener() {
                @Override
                public void onRenderedFirstFrame() {
                    infoLogo.animate().alpha(0f).setDuration(250).start();
                }

                @Override
                public void onPlayerError(PlaybackException error) { stopPreview(); }
            });
            previewView.setPlayer(preview);
        }
        previewItem = it;
        previewView.setVisibility(View.VISIBLE); // the video surface only exists while visible
        preview.setVolume(prefs.previewMode() == 2 ? 1f : 0f);
        preview.setMediaItem(PlayerFactory.media(api.playUrl(it, prefs.liveFormat())));
        preview.prepare();
        preview.setPlayWhenReady(true);
    }

    private void stopPreview() {
        Async.MAIN.removeCallbacks(pendingPreview);
        previewItem = null;
        if (preview != null) {
            preview.stop();
            preview.clearMediaItems();
        }
        if (previewView != null) previewView.setVisibility(View.INVISIBLE);
        if (infoLogo != null) {
            infoLogo.animate().cancel();
            infoLogo.setAlpha(1f);
        }
    }

    private void releasePreview() {
        stopPreview();
        if (preview != null) {
            previewView.setPlayer(null);
            preview.release();
            preview = null;
        }
    }

    @Override
    protected void onDestroy() {
        Async.MAIN.removeCallbacks(pendingCat);
        Async.MAIN.removeCallbacks(pendingEpg);
        releasePreview();
        super.onDestroy();
    }
}
