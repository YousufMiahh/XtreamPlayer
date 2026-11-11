package com.xtreamplayer.tv;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * tvOS-style tab bar. A single highlight glides between tabs as you move, and switching
 * sections keeps the bar still while the page below fades and slides in from the side
 * you're moving towards (and the background colour blends across).
 */
public final class TopBar {
    private TopBar() {}

    public static final String HOME = "home", LIVE = "live", MOVIES = "movie", SERIES = "series",
            SEARCH = "search", SETTINGS = "settings";

    private static final String[][] TABS = {
            {HOME, "Home"}, {LIVE, "Live TV"}, {MOVIES, "Movies"}, {SERIES, "Series"},
            {SEARCH, "Search"}, {SETTINGS, "Settings"},
    };

    /** Screens that do something when OK is pressed on their own tab (Search opens the keyboard). */
    public interface Reselect {
        void onTabReselected();
    }

    static final String EXTRA_TAB = "focus_tab";
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(2f);

    private static String pendingHomeFocus;
    /** -1 = the new page comes in from the left, 1 = from the right, 0 = no transition pending. */
    private static int enterDirection;
    /** Where the highlight was on the screen we just left, so it can glide on from there. */
    private static float lastX = -1, lastW = -1;
    /** Set when we switched by hovering: the next bar's highlight glides on from lastX. */
    private static boolean glideHandoff;

    /** Highlight state for one bar. */
    private static class Glide {
        View pill;
        float x, w;
        boolean shown;
        ValueAnimator anim;
    }

    public static View build(final Activity a, final String current) {
        FrameLayout bar = new FrameLayout(a);
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        bar.setPadding(Ui.dp(a, 64), Ui.dp(a, 28), Ui.dp(a, 64), Ui.dp(a, 8));

        TextView brand = Ui.title(a, "YM", 22);
        brand.setTextColor(Ui.TEXT);
        bar.addView(brand, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL));

        // Rounded track holding the gliding highlight underneath the tab labels
        final FrameLayout track = new FrameLayout(a);
        int tp = Ui.dp(a, 6);
        track.setPadding(tp, tp, tp, tp);
        track.setBackground(Ui.rounded(a, Ui.SURFACE_LO, 0, 28));
        track.setClipChildren(false);
        track.setClipToPadding(false);

        final Glide glide = new Glide();
        glide.pill = new View(a);
        glide.pill.setBackground(Ui.rounded(a, Ui.FOCUS_BG, 0, 22));
        glide.pill.setAlpha(0f);
        glide.pill.setElevation(Ui.dp(a, 6));
        track.addView(glide.pill, new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.START));

        final LinearLayout tabs = new TabRow(a, current);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);
        tabs.setClipChildren(false);
        tabs.setTranslationZ(Ui.dp(a, 8)); // labels draw above the highlight
        track.addView(tabs, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final String wanted = a.getIntent() == null ? null : a.getIntent().getStringExtra(EXTRA_TAB);
        for (final String[] t : TABS) {
            final TextView tab = Ui.medium(a, t[1], 17, Ui.TEXT);
            tab.setTag("tab:" + t[0]);
            boolean on = t[0].equals(current);
            tab.setTextColor(Ui.focusText(on ? Ui.TEXT : Ui.TEXT2));
            tab.setTypeface(on ? Ui.semibold() : Ui.medium());
            // The current section keeps a soft pill; the focused one is shown by the gliding highlight.
            android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_focused}, new android.graphics.drawable.ColorDrawable(0));
            bg.addState(new int[]{}, on ? Ui.rounded(a, Ui.SURFACE_HI, 0, 22) : new android.graphics.drawable.ColorDrawable(0));
            tab.setBackground(bg);
            int px = Ui.dp(a, 22), py = Ui.dp(a, 11);
            tab.setPadding(px, py, px, py);
            tab.setFocusable(true);
            tab.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (t[0].equals(current)) { // OK on the tab you're already on
                        if (a instanceof Reselect) ((Reselect) a).onTabReselected();
                        return;
                    }
                    go(a, current, t[0], false);
                }
            });
            // Hovering a tab opens that section after a short pause (Settings still needs OK).
            final Runnable open = new Runnable() {
                public void run() {
                    if (tab.hasFocus() && !a.isFinishing()) go(a, current, t[0], true);
                }
            };
            tab.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean has) {
                    v.removeCallbacks(open);
                    if (has) {
                        glideTo(glide, tabs, tab);
                        if (!t[0].equals(current) && !SETTINGS.equals(t[0])) v.postDelayed(open, 450);
                    }
                }
            });
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = tlp.rightMargin = Ui.dp(a, 2);
            tabs.addView(tab, tlp);
            if (t[0].equals(current) && t[0].equals(wanted)) {
                tab.post(new Runnable() { public void run() { tab.requestFocus(); } });
            }
        }
        bar.addView(track, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // Coming up from the page always lands on the current tab (like tvOS), and the
        // highlight fades away when you move down into the page.
        // Safety net: the highlight always matches where focus actually is. Focus can land on a
        // tab without the usual focus callback (e.g. when the screen comes back or is still
        // being laid out while you move quickly along the bar), which left a tab with dark text
        // but no highlight behind it.
        final Runnable sync = new Runnable() {
            public void run() {
                View f = tabs.getRootView().findFocus();
                boolean onTab = f != null && f.getParent() == tabs;
                if (onTab) {
                    if (!glide.shown || glide.pill.getAlpha() < 0.99f || Math.abs(glide.x - (tabs.getLeft() + f.getLeft())) > 1) {
                        glideTo(glide, tabs, f);
                    }
                } else if (glide.shown) {
                    glide.shown = false;
                    glide.pill.animate().cancel();
                    glide.pill.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(160).start();
                }
            }
        };
        final ViewTreeObserver.OnGlobalFocusChangeListener entry = new ViewTreeObserver.OnGlobalFocusChangeListener() {
            public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                boolean toTab = newFocus != null && newFocus.getParent() == tabs;
                if (!toTab && glide.shown) {
                    glide.shown = false;
                    glide.pill.animate().cancel();
                    glide.pill.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(160).start();
                }
                tabs.removeCallbacks(sync);
                tabs.postDelayed(sync, 320); // after the glide has had time to finish
            }
        };
        final ViewTreeObserver.OnWindowFocusChangeListener windowFocus = new ViewTreeObserver.OnWindowFocusChangeListener() {
            public void onWindowFocusChanged(boolean has) {
                if (has) {
                    tabs.removeCallbacks(sync);
                    tabs.postDelayed(sync, 60);
                }
            }
        };
        tabs.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                v.removeCallbacks(sync);
                v.postDelayed(sync, 60);
            }
        });
        tabs.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) {
                v.getViewTreeObserver().addOnGlobalFocusChangeListener(entry);
                v.getViewTreeObserver().addOnWindowFocusChangeListener(windowFocus);
            }
            public void onViewDetachedFromWindow(View v) {
                v.removeCallbacks(sync);
                v.getViewTreeObserver().removeOnGlobalFocusChangeListener(entry);
                v.getViewTreeObserver().removeOnWindowFocusChangeListener(windowFocus);
            }
        });

        final TextView clock = Ui.medium(a, "", 20, Ui.TEXT2);
        bar.addView(clock, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL));
        final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.UK);
        final Runnable tick = new Runnable() {
            public void run() {
                clock.setText(hm.format(new Date()));
                clock.postDelayed(this, 15000);
            }
        };
        clock.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) { tick.run(); }
            public void onViewDetachedFromWindow(View v) { v.removeCallbacks(tick); }
        });
        return bar;
    }

    /**
     * The row of tabs. When focus arrives from the page below, only the current section's tab
     * is offered as a target, so moving up lands straight on it (no flash of another tab).
     */
    private static class TabRow extends LinearLayout {
        private final String current;

        TabRow(android.content.Context c, String current) {
            super(c);
            this.current = current;
        }

        @Override
        public void addFocusables(java.util.ArrayList<View> views, int direction, int mode) {
            View focused = getRootView().findFocus();
            boolean inside = focused != null && focused.getParent() == this;
            if (!inside && focused != null) {
                View cur = findViewWithTag("tab:" + current);
                if (cur != null) {
                    views.add(cur);
                    return;
                }
            }
            super.addFocusables(views, direction, mode);
        }
    }

    /** Slide (and stretch) the highlight to sit behind this tab. */
    private static void glideTo(final Glide g, final LinearLayout tabs, final View tab) {
        if (tab.getWidth() == 0) { // not laid out yet
            tab.post(new Runnable() { public void run() { if (tab.hasFocus()) glideTo(g, tabs, tab); } });
            return;
        }
        final float tx = tabs.getLeft() + tab.getLeft(), tw = tab.getWidth();
        final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) g.pill.getLayoutParams();
        lp.height = tab.getHeight();
        lp.topMargin = tabs.getTop() + tab.getTop() - ((View) tabs.getParent()).getPaddingTop();
        if (g.anim != null) g.anim.cancel();

        float fromX, fromW;
        if (g.shown) {
            fromX = g.x;
            fromW = g.w;
        } else if (lastX >= 0 && glideHandoff) { // continue from where it was on the last screen
            glideHandoff = false;
            fromX = lastX;
            fromW = lastW;
        } else { // arriving from the page: appear in place
            fromX = tx;
            fromW = tw;
        }
        g.pill.animate().cancel();
        if (!g.shown || g.pill.getAlpha() < 0.99f) {
            g.pill.setScaleX(fromX == tx ? 0.92f : 1f);
            g.pill.setScaleY(fromX == tx ? 0.92f : 1f);
            g.pill.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(EASE).start();
        }
        g.shown = true;
        final float fx = fromX, fw = fromW;
        apply(g, lp, fx, fw);
        g.anim = ValueAnimator.ofFloat(0f, 1f);
        g.anim.setDuration(280);
        g.anim.setInterpolator(EASE);
        g.anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator v) {
                float f = (Float) v.getAnimatedValue();
                apply(g, lp, fx + (tx - fx) * f, fw + (tw - fw) * f);
            }
        });
        g.anim.start();
    }

    private static void apply(Glide g, FrameLayout.LayoutParams lp, float x, float w) {
        g.x = x;
        g.w = w;
        lastX = x;
        lastW = w;
        lp.width = Math.round(w);
        g.pill.setTranslationX(x - ((View) g.pill.getParent()).getPaddingLeft());
        g.pill.setLayoutParams(lp);
    }

    // ------------------------------------------------------------ section switching

    /** True while one of the top tabs has focus (screens then don't grab focus for their content). */
    public static boolean tabFocused(Activity a) {
        View f = a.getCurrentFocus();
        return f != null && f.getTag() instanceof String && ((String) f.getTag()).startsWith("tab:");
    }

    public static void focusTab(Activity a, String id) {
        View t = a.getWindow().getDecorView().findViewWithTag("tab:" + id);
        if (t != null) t.requestFocus();
    }

    /** Home calls this when it comes back; animates its page in and focuses its tab if we hovered there. */
    public static void onHomeResumed(final Activity home, View... content) {
        if (enterDirection != 0) {
            Ambient.takeHandoff(home);
            animateIn(home, content);
        }
        // Back on Home: if its tab bar still had another tab focused (the one you left from),
        // move the highlight to Home.
        View f = home.getCurrentFocus();
        boolean otherTab = f != null && f.getTag() instanceof String
                && ((String) f.getTag()).startsWith("tab:") && !("tab:" + HOME).equals(f.getTag());
        if (HOME.equals(pendingHomeFocus) || otherTab) focusTab(home, HOME);
        pendingHomeFocus = null;
    }

    /** Back from a section to Home, with the same slide as the tabs. */
    public static void back(Activity from) {
        enterDirection = -1;
        glideHandoff = false;
        Ambient.handOff(from);
        from.finish();
        from.overridePendingTransition(0, 0);
    }

    /** Fade and slide a screen's content in after a tab switch (the bar itself stays still). */
    public static void animateIn(Activity a, View... content) {
        int dir = enterDirection;
        enterDirection = 0;
        if (dir == 0) return;
        float dx = dir * Ui.dp(a, 64);
        for (View v : content) {
            if (v == null) continue;
            v.animate().cancel();
            v.setAlpha(0f);
            v.setTranslationX(dx);
            v.animate().alpha(1f).translationX(0f).setStartDelay(60).setDuration(420).setInterpolator(EASE).start();
        }
    }

    private static int order(String id) {
        for (int i = 0; i < TABS.length; i++) if (TABS[i][0].equals(id)) return i;
        return 0;
    }

    /** Switch section. Home is always at the bottom of the stack. */
    public static void go(Activity from, String current, String target, boolean byHover) {
        if (target.equals(current)) return;
        enterDirection = order(target) > order(current) ? 1 : -1;
        glideHandoff = byHover;
        Ambient.handOff(from);
        if (HOME.equals(target)) {
            pendingHomeFocus = byHover ? HOME : null;
            from.finish();
            from.overridePendingTransition(0, 0);
            return;
        }
        Intent i;
        if (LIVE.equals(target) || MOVIES.equals(target) || SERIES.equals(target)) {
            i = new Intent(from, BrowseActivity.class).putExtra("type", target);
        } else if (SEARCH.equals(target)) {
            i = new Intent(from, SearchActivity.class);
        } else {
            i = new Intent(from, SettingsActivity.class);
        }
        if (byHover) i.putExtra(EXTRA_TAB, target);
        i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        from.startActivity(i);
        if (!HOME.equals(current)) from.finish();
        from.overridePendingTransition(0, 0);
    }
}
