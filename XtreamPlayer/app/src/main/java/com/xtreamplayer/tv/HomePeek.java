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
 * Home, Netflix-style: rest on a film or series card and it widens into a 16:9 picture, then a
 * moment later starts playing (muted) inside the card. Moving off puts it back. Settings → Film &
 * Series Previews turns it off.
 */
final class HomePeek {
    private static final long WIDEN_AFTER_MS = 1200, PLAY_AFTER_MS = 900;
    private static final long FILM_START_MS = 5 * 60_000L, EPISODE_START_MS = 3 * 60_000L;

    private final Activity a;
    private final Prefs prefs;
    private final XtreamApi api;
    private final Preview preview;
    private LinearLayout card;     // the one that's widened (or about to)
    private int baseW;
    private FrameLayout host;
    private ValueAnimator anim;
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
            baseW = lp.width;
            int target = Math.round(lp.height * 16f / 9f);
            if (target > baseW + 4) animateWidth(baseW, target, null);
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
        art.addView(host, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
        if (!active || now == null || prefs.homePreviews() == 0) return;
        if (!(now instanceof LinearLayout) || !(now.getTag() instanceof Item)) return;
        Item it = (Item) now.getTag();
        if (!Item.MOVIE.equals(it.type) && !Item.SERIES.equals(it.type) && !Item.EPISODE.equals(it.type)) return;
        if (!(((LinearLayout) now).getChildAt(0) instanceof FrameLayout)) return;
        card = (LinearLayout) now;
        Async.MAIN.postDelayed(widen, WIDEN_AFTER_MS);
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
        if (card != null) {
            View art = card.getChildAt(0);
            int w = art.getLayoutParams().width;
            if (baseW > 0 && w != baseW) {
                if (anim != null) anim.cancel();
                if (animate) animateWidth(w, baseW, card);
                else setWidth(card, baseW);
            }
        }
        card = null;
        baseW = 0;
    }

    private void animateWidth(int from, int to, final LinearLayout target) {
        final LinearLayout c = target != null ? target : card;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofInt(from, to);
        anim.setDuration(Ui.ANIM_MS + 60);
        anim.setInterpolator(Ui.EASE);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator v) {
                setWidth(c, (Integer) v.getAnimatedValue());
                if (c.isFocused()) c.requestRectangleOnScreen(new Rect(0, 0, c.getWidth(), c.getHeight()), true);
            }
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
