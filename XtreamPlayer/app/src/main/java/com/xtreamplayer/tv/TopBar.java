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
        bar.setPadding(Ui.dp(a, 64), Ui.dp(a, 28), Ui.dp(a, 64), Ui.dp(a, 8));

        TextView brand = Ui.title(a, "YM", 22);
        brand.setTextColor(Ui.TEXT);
        bar.addView(brand, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL));

        LinearLayout tabs = new LinearLayout(a);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);
        tabs.setPadding(Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6));
        tabs.setBackground(Ui.rounded(a, Ui.SURFACE_LO, 0, 28));
        for (final String[] t : TABS) {
            TextView tab = Ui.medium(a, t[1], 17, Ui.TEXT);
            boolean on = t[0].equals(current);
            tab.setTextColor(Ui.focusText(on ? Ui.TEXT : Ui.TEXT2));
            tab.setTypeface(on ? Ui.semibold() : Ui.medium());
            tab.setBackground(Ui.pill(a, on ? Ui.SURFACE_HI : 0, 22));
            int px = Ui.dp(a, 22), py = Ui.dp(a, 11);
            tab.setPadding(px, py, px, py);
            tab.setFocusable(true);
            tab.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { go(a, current, t[0]); }
            });
            Ui.lift(tab, 1.05f);
            tabs.addView(tab);
        }
        bar.addView(tabs, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

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

    /** Switch section. Home is always at the bottom of the stack. */
    public static void go(Activity from, String current, String target) {
        if (target.equals(current)) return;
        if (HOME.equals(target)) {
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
        from.startActivity(i);
        if (!HOME.equals(current)) from.finish();
        from.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
