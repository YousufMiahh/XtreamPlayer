package com.xtreamplayer.tv;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** tvOS-style tab bar: centred pills, the current one tinted, the focused one white. Clock on the right. */
public final class TopBar {
    private TopBar() {}

    public static final String HOME = "home", LIVE = "live", MOVIES = "movie", SERIES = "series",
            SEARCH = "search", SETTINGS = "settings";

    private static final String[][] TABS = {
            {HOME, "Home"}, {LIVE, "Live TV"}, {MOVIES, "Movies"}, {SERIES, "Series"},
            {SEARCH, "Search"}, {SETTINGS, "Settings"},
    };

    public static View build(final Activity a, final String current) {
        FrameLayout bar = new FrameLayout(a);
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        bar.setPadding(Ui.dp(a, 64), Ui.dp(a, 28), Ui.dp(a, 64), Ui.dp(a, 8));

        TextView brand = Ui.title(a, "YM", 22);
        brand.setTextColor(Ui.TEXT);
        bar.addView(brand, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL));

        LinearLayout tabs = new LinearLayout(a);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);
        tabs.setPadding(Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6));
        tabs.setClipChildren(false);
        tabs.setClipToPadding(false);
        final String wanted = a.getIntent() == null ? null : a.getIntent().getStringExtra(EXTRA_TAB);
        tabs.setBackground(Ui.rounded(a, Ui.SURFACE_LO, 0, 28));
        for (final String[] t : TABS) {
            final TextView tab = Ui.medium(a, t[1], 17, Ui.TEXT);
            tab.setTag("tab:" + t[0]);
            boolean on = t[0].equals(current);
            tab.setTextColor(Ui.focusText(on ? Ui.TEXT : Ui.TEXT2));
            tab.setTypeface(on ? Ui.semibold() : Ui.medium());
            tab.setBackground(Ui.pill(a, on ? Ui.SURFACE_HI : 0, 22));
            int px = Ui.dp(a, 22), py = Ui.dp(a, 11);
            tab.setPadding(px, py, px, py);
            tab.setFocusable(true);
            tab.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { go(a, current, t[0], false); }
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
                    if (has && !t[0].equals(current) && !SETTINGS.equals(t[0])) v.postDelayed(open, 450);
                }
            });
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = tlp.rightMargin = Ui.dp(a, 2);
            tabs.addView(tab, tlp);
            if (t[0].equals(current) && t[0].equals(wanted)) {
                tab.post(new Runnable() { public void run() { tab.requestFocus(); } });
            }
        }
        bar.addView(tabs, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // Coming up from the page always lands on the current tab (like tvOS), so moving up
        // never opens a different section by accident.
        final android.view.ViewTreeObserver.OnGlobalFocusChangeListener entry =
                new android.view.ViewTreeObserver.OnGlobalFocusChangeListener() {
                    public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                        if (newFocus == null || oldFocus == null) return;
                        boolean toTab = newFocus.getParent() == tabs;
                        boolean fromTab = oldFocus.getParent() == tabs;
                        if (toTab && !fromTab && !("tab:" + current).equals(newFocus.getTag())) {
                            final View cur = tabs.findViewWithTag("tab:" + current);
                            if (cur != null) cur.post(new Runnable() { public void run() { cur.requestFocus(); } });
                        }
                    }
                };
        tabs.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) { v.getViewTreeObserver().addOnGlobalFocusChangeListener(entry); }
            public void onViewDetachedFromWindow(View v) { v.getViewTreeObserver().removeOnGlobalFocusChangeListener(entry); }
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

    static final String EXTRA_TAB = "focus_tab";
    private static String pendingHomeFocus;

    /** True while one of the top tabs has focus (screens then don't grab focus for their content). */
    public static boolean tabFocused(Activity a) {
        View f = a.getCurrentFocus();
        return f != null && f.getTag() instanceof String && ((String) f.getTag()).startsWith("tab:");
    }

    public static void focusTab(Activity a, String id) {
        View t = a.getWindow().getDecorView().findViewWithTag("tab:" + id);
        if (t != null) t.requestFocus();
    }

    /** Home calls this when it comes back; puts focus on its tab if we got there by hovering. */
    public static void onHomeResumed(Activity home) {
        if (HOME.equals(pendingHomeFocus)) focusTab(home, HOME);
        pendingHomeFocus = null;
    }

    /** Switch section. Home is always at the bottom of the stack. */
    public static void go(Activity from, String current, String target, boolean byHover) {
        if (target.equals(current)) return;
        if (HOME.equals(target)) {
            pendingHomeFocus = byHover ? HOME : null;
            from.finish();
            from.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
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
        from.startActivity(i);
        if (!HOME.equals(current)) from.finish();
        from.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
