package com.xtreamplayer.tv;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.List;

/**
 * Home, Netflix-style: the film or series card you're on widens straight away into a 16:9 picture
 * (its TMDB backdrop, with the title's logo over it, when there is one), then starts playing
 * (muted) inside the card as soon as it can. Moving on puts it back and widens the next one at
 * once. Settings → Film & Series Previews turns it off.
 */
final class HomePeek {
    private static final long WIDEN_AFTER_MS = 0, PLAY_AFTER_MS = 450; // play once you've stopped on it
    private static final long FILM_START_MS = 5 * 60_000L, EPISODE_START_MS = 3 * 60_000L;

    private final Activity a;
    private final Prefs prefs;
    private final XtreamApi api;
    private final Preview preview;
    private LinearLayout card;     // the one that's widened (or about to)
    private int baseW;
    private FrameLayout host;
    /** Each card's own width animation and normal width: moving on quickly must shrink every one back. */
    private final java.util.WeakHashMap<View, ValueAnimator> anims = new java.util.WeakHashMap<>();
    private final java.util.WeakHashMap<View, Integer> normalW = new java.util.WeakHashMap<>();
    private boolean active = true;

    HomePeek(Activity a, Prefs prefs, XtreamApi api, View root) {
        this.a = a;
        this.prefs = prefs;
        this.api = api;
        FrameLayout dummy = new FrameLayout(a);
        preview = new Preview(a, prefs, api, dummy, null);
        preview.setPicture(Engine.PIC_FILL);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener(new ViewTreeObserver.OnGlobalFocusChangeListener() {
            public void onGlobalFocusChanged(View old, View now) { focusMoved(now); }
        });
    }

    void pause() {
        active = false;
        collapse(false);
    }

    void resume() { active = true; }

    void release() {
        collapse(false);
        preview.release();
    }

    private final Runnable widen = new Runnable() {
        public void run() {
            if (card == null || !active || !card.hasFocus() && !card.isFocused()) return;
            final View art = card.getChildAt(0);
            ViewGroup.LayoutParams lp = art.getLayoutParams();
            Integer known = normalW.get(card);
            baseW = known != null ? known : lp.width; // not a width caught half way through shrinking
            normalW.put(card, baseW);
            int target = Math.round(lp.height * 16f / 9f);
            if (target > baseW + 4) animateWidth(lp.width, target, card);
            dress(card);
            Async.MAIN.postDelayed(play, PLAY_AFTER_MS);
        }
    };

    private final Runnable play = new Runnable() {
        public void run() {
            final LinearLayout c = card;
            if (c == null || !active || !c.isFocused()) return;
            final Item it = (Item) c.getTag();
            if (Item.MOVIE.equals(it.type)) {
                long pos = prefs.position(it);
                start(c, it, pos > 0 ? pos : FILM_START_MS);
            } else if (Item.EPISODE.equals(it.type)) {
                long pos = prefs.position(it);
                start(c, it, pos > 0 ? pos : EPISODE_START_MS);
            } else if (Item.SERIES.equals(it.type)) {
                // The episode you were on, or the first one
                for (Item h : prefs.history(false)) {
                    if (Item.EPISODE.equals(h.type) && it.id.equals(h.parentId)) {
                        long pos = prefs.position(h);
                        start(c, h, pos > 0 ? pos : EPISODE_START_MS);
                        return;
                    }
                }
                final Item probe = new Item(Item.EPISODE, "peek", it.name);
                probe.parentId = it.id;
                probe.parentName = it.name;
                probe.parentIcon = it.icon;
                probe.season = 1;
                Async.run(new Async.Work<List<Item>>() {
                    public List<Item> run() throws Exception { return Episodes.following(api, probe); }
                }, new Async.Done<List<Item>>() {
                    public void done(List<Item> eps, Exception e) {
                        if (eps != null && !eps.isEmpty() && card == c && active && c.isFocused()) start(c, eps.get(0), EPISODE_START_MS);
                    }
                });
            }
        }
    };

    private void start(LinearLayout c, Item what, long fromMs) {
        FrameLayout art = (FrameLayout) c.getChildAt(0);
        host = new FrameLayout(a);
        // over the artwork and backdrop, under the logo
        int at = backdrop != null && backdrop.getParent() == art ? art.indexOfChild(backdrop) + 1 : 1;
        art.addView(host, at, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // The video sits above the artwork, see-through until it's ready
        host.setAlpha(0f);
        preview.moveTo(host, null);
        preview.setOnReady(new Runnable() {
            public void run() { if (host != null) host.animate().alpha(1f).setStartDelay(250).setDuration(450).start(); }
        });
        preview.play(what, fromMs);
    }

    private void focusMoved(View now) {
        if (now == card) return;
        collapse(true);
        if (shownInfo != null && (now == null || Cards.infoBox(now) != shownInfo)) { shownInfo.setVisibility(View.GONE); shownInfo = null; }
        if (!active || now == null || prefs.homePreviews() == 0) return;
        if (!(now instanceof LinearLayout) || !(now.getTag() instanceof Item)) return;
        Item it = (Item) now.getTag();
        if (!Item.MOVIE.equals(it.type) && !Item.SERIES.equals(it.type) && !Item.EPISODE.equals(it.type)) return;
        if (!(((LinearLayout) now).getChildAt(0) instanceof FrameLayout)) return;
        card = (LinearLayout) now;
        Async.MAIN.post(widen); // straight away, like Netflix
    }

    private void collapse(boolean animate) {
        Async.MAIN.removeCallbacks(widen);
        Async.MAIN.removeCallbacks(play);
        preview.stop();
        preview.setOnReady(null);
        if (host != null) {
            final FrameLayout h = host;
            host = null;
            h.animate().cancel();
            if (h.getParent() instanceof ViewGroup) ((ViewGroup) h.getParent()).removeView(h);
        }
        for (View v : new View[]{backdrop, logoLayer}) if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        // the details under the row stay while you move along it (the next card's replace them);
        // moving to another row or off the cards puts them away
        if (shownInfo != null && !animate) { shownInfo.setVisibility(View.GONE); shownInfo = null; }
        backdrop = null;
        logoLayer = null;
        if (card != null) {
            View art = card.getChildAt(0);
            int w = art.getLayoutParams().width;
            ValueAnimator running = anims.remove(card);
            if (running != null) running.cancel();
            if (baseW > 0 && w != baseW) {
                if (animate) animateWidth(w, baseW, card);
                else setWidth(card, baseW);
            }
        }
        card = null;
        baseW = 0;
    }

    // ------------------------------------------------------------ backdrop + logo (TMDB)

    private android.widget.ImageView backdrop;
    private FrameLayout logoLayer;

    /** The widened card's picture: TMDB's wide backdrop and the title's logo, faded in when they're here. */
    private void dress(final LinearLayout c) {
        final Item it = (Item) c.getTag();
        if (it == null) return;
        final FrameLayout art = (FrameLayout) c.getChildAt(0);
        final boolean film = Item.MOVIE.equals(it.type);
        final String name = Item.EPISODE.equals(it.type) && it.parentName != null ? it.parentName : it.name;
        backdrop = new android.widget.ImageView(a);
        backdrop.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0f);
        art.addView(backdrop, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        logoLayer = new FrameLayout(a);
        logoLayer.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xB3000000, 0x00000000}));
        logoLayer.setAlpha(0f);
        final android.widget.ImageView logo = new android.widget.ImageView(a);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(android.widget.ImageView.ScaleType.FIT_START);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM | android.view.Gravity.START);
        int m = Ui.dp(a, 14);
        llp.setMargins(m, m, m, m);
        logoLayer.addView(logo, llp);
        art.addView(logoLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final android.widget.ImageView back = backdrop;
        final FrameLayout layer = logoLayer;
        Async.run(new Async.Work<Tmdb.Details>() {
            public Tmdb.Details run() {
                String kind = film ? Tmdb.MOVIE : Tmdb.TV;
                String id = Tmdb.findId(a, kind, name, it.tmdb);
                return id == null ? null : Tmdb.details(a, kind, id);
            }
        }, new Async.Done<Tmdb.Details>() {
            public void done(Tmdb.Details d, Exception e) {
                if (d == null || card != c || backdrop != back) return;
                int artH = art.getHeight();
                logo.setMaxHeight(Math.max(1, Math.round(artH * 0.38f)));
                logo.setMaxWidth(Math.max(1, Math.round(artH * 16f / 9f * 0.55f)));
                show(d.backdrop, back, 780, 440, back);
                show(d.logo, logo, 500, 200, layer);
                showInfo(c, d, !film);
            }
        });
    }

    private LinearLayout shownInfo;

    /** Under the row, like Netflix: "Series · Thriller · 2026 · 2 Seasons (15)" and the summary. */
    private void showInfo(LinearLayout c, Tmdb.Details d, boolean series) {
        LinearLayout box = Cards.infoBox(c);
        if (box == null) return;
        box.removeAllViews();
        LinearLayout meta = new LinearLayout(a);
        meta.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.TextView line = Ui.oneLine(Ui.medium(a, d.line(series), 17, Ui.TEXT));
        line.setTypeface(Ui.semibold());
        meta.addView(line);
        if (d.cert != null) {
            android.widget.TextView badge = Cards.ratingBadge(a, d.cert);
            int bs = Ui.dp(a, 26);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(bs, bs);
            bl.leftMargin = Ui.dp(a, 12);
            meta.addView(badge, bl);
        }
        box.addView(meta);
        if (d.overview != null) {
            android.widget.TextView sum = Ui.lines(Ui.text(a, d.overview, 17, Ui.TEXT2), 2);
            sum.setPadding(0, Ui.dp(a, 6), 0, 0);
            sum.setLineSpacing(0, 1.15f);
            box.addView(sum, new LinearLayout.LayoutParams(Ui.dp(a, 760), ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (shownInfo != null && shownInfo != box) shownInfo.setVisibility(View.GONE);
        shownInfo = box;
        box.setAlpha(0f);
        box.setVisibility(View.VISIBLE);
        box.animate().alpha(1f).setDuration(220).start();
    }

    /** Puts a picture in (now if it's already loaded) and fades `fade` in. */
    private void show(final String url, final android.widget.ImageView into, int w, int h, final View fade) {
        if (url == null) return;
        android.graphics.Bitmap have = ImageLoader.cached(url);
        if (have != null) {
            into.setImageBitmap(have);
            fade.animate().alpha(1f).setDuration(220).start();
            return;
        }
        ImageLoader.prefetch(url, w, h, new Runnable() {
            public void run() {
                android.graphics.Bitmap bm = ImageLoader.cached(url);
                if (bm == null || fade.getParent() == null) return;
                into.setImageBitmap(bm);
                fade.animate().alpha(1f).setDuration(260).start();
            }
        });
    }

    private void animateWidth(int from, int to, final LinearLayout target) {
        final LinearLayout c = target != null ? target : card;
        if (c == null) return;
        ValueAnimator old = anims.remove(c);
        if (old != null) old.cancel();
        final ValueAnimator anim = ValueAnimator.ofInt(from, to);
        anims.put(c, anim);
        anim.setDuration(Ui.ANIM_MS + 60);
        anim.setInterpolator(Ui.EASE);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator v) {
                setWidth(c, (Integer) v.getAnimatedValue());
                if (c.isFocused()) c.requestRectangleOnScreen(new Rect(0, 0, c.getWidth(), c.getHeight()), true);
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator x) { if (anims.get(c) == anim) anims.remove(c); }
        });
        anim.start();
    }

    private static void setWidth(LinearLayout c, int w) {
        for (int i = 0; i < c.getChildCount(); i++) {
            ViewGroup.LayoutParams lp = c.getChildAt(i).getLayoutParams();
            lp.width = w;
            c.getChildAt(i).setLayoutParams(lp);
        }
    }
}
