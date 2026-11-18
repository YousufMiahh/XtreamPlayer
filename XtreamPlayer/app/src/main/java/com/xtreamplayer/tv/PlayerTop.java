package com.xtreamplayer.tv;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Netflix-style top of the player for films and episodes: Back, Play From Beginning, Next Episode,
 * Episodes and Settings along the top left (▲ to reach them, ◀ ▶ to move, OK to choose; the
 * chosen one's name shows underneath), and the title and episode at the top right while paused.
 */
final class PlayerTop extends FrameLayout {
    static final int BACK = 0, RESTART = 1, NEXT = 2, EPISODES = 3, SETTINGS = 4;
    private static final Icon.Kind[] ICONS = {Icon.Kind.BACK, Icon.Kind.RESTART, Icon.Kind.NEXT, Icon.Kind.LIST, Icon.Kind.SETTINGS};
    private static final String[] NAMES = {"Back", "Play From Beginning", "Next Episode", "Episodes", "Settings"};

    interface Listener { void onTopAction(int which); }

    private final Listener listener;
    private final LinearLayout row;
    private final TextView label, title, sub;
    private final LinearLayout info;
    private final List<Integer> shown = new ArrayList<>();
    private final List<FrameLayout> circles = new ArrayList<>();
    private final List<Icon> icons = new ArrayList<>();
    private int focus = -1;
    private String nextName = "Next Episode";

    PlayerTop(Context c, Listener l) {
        super(c);
        listener = l;
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xD9000000, 0x80000000, 0x00000000}));
        setPadding(Ui.dp(c, 72), Ui.dp(c, 44), Ui.dp(c, 72), Ui.dp(c, 70));
        setVisibility(GONE);

        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        left.addView(row);
        label = Ui.medium(c, "", 15, Ui.W1);
        label.setTypeface(Ui.semibold());
        label.setLetterSpacing(0.04f);
        label.setPadding(Ui.dp(c, 2), Ui.dp(c, 10), 0, 0);
        left.addView(label);
        addView(left, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.TOP));

        info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.END);
        title = Ui.oneLine(Ui.title(c, "", 24));
        title.setTextColor(Ui.W1);
        title.setGravity(Gravity.END);
        sub = Ui.oneLine(Ui.medium(c, "", 17, Ui.W1));
        sub.setGravity(Gravity.END);
        sub.setPadding(0, Ui.dp(c, 4), 0, 0);
        info.addView(title);
        info.addView(sub);
        info.setVisibility(GONE);
        LayoutParams il = new LayoutParams(Ui.dp(c, 760), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
        il.topMargin = Ui.dp(c, 6);
        addView(info, il);
        setButtons(false, false);
    }

    /** Which buttons this film / episode gets. */
    void setButtons(boolean hasNext, boolean isEpisode) {
        shown.clear();
        shown.add(BACK);
        shown.add(RESTART);
        if (hasNext) shown.add(NEXT);
        if (isEpisode) shown.add(EPISODES);
        shown.add(SETTINGS);
        row.removeAllViews();
        circles.clear();
        icons.clear();
        Context c = getContext();
        for (int i = 0; i < shown.size(); i++) {
            FrameLayout b = new FrameLayout(c);
            Icon ic = new Icon(c, ICONS[shown.get(i)], Ui.W1);
            int pad = Ui.dp(c, 13);
            ic.setPadding(pad, pad, pad, pad);
            b.addView(ic, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, 58), Ui.dp(c, 58));
            lp.rightMargin = Ui.dp(c, 18);
            row.addView(b, lp);
            circles.add(b);
            icons.add(ic);
        }
        if (focus >= shown.size()) focus = shown.size() - 1;
        paint();
    }

    void setNextName(String s) { nextName = s; paint(); }

    void setInfo(String t, String s) {
        title.setText(t);
        sub.setText(s == null ? "" : s);
        sub.setVisibility(s == null || s.isEmpty() ? GONE : VISIBLE);
    }

    /** The title shows at the top right only while paused (like Netflix). */
    void setPaused(boolean paused) { info.setVisibility(paused ? VISIBLE : GONE); }

    boolean focused() { return focus >= 0; }

    /** ▲ from the progress bar: onto Play From Beginning (Netflix's default). */
    void focusDefault() {
        focus = Math.min(1, shown.size() - 1);
        paint();
    }

    void leave() {
        focus = -1;
        paint();
    }

    void move(int dir) {
        if (focus < 0) return;
        focus = Math.max(0, Math.min(shown.size() - 1, focus + dir));
        paint();
    }

    void activate() {
        if (focus >= 0 && focus < shown.size() && listener != null) listener.onTopAction(shown.get(focus));
    }

    private void paint() {
        for (int i = 0; i < circles.size(); i++) {
            boolean on = i == focus;
            circles.get(i).setBackground(on ? Ui.rounded(getContext(), 0xFFFFFFFF, 0, 29) : null);
            icons.get(i).setColor(on ? 0xFF0B0B0F : Ui.W1);
            circles.get(i).animate().scaleX(on ? 1.1f : 1f).scaleY(on ? 1.1f : 1f).setDuration(150).start();
        }
        if (focus >= 0 && focus < shown.size()) {
            int w = shown.get(focus);
            label.setText((w == NEXT ? nextName : NAMES[w]).toUpperCase(Locale.UK));
            label.setVisibility(VISIBLE);
        } else {
            label.setText("");
            label.setVisibility(INVISIBLE);
        }
    }
}
