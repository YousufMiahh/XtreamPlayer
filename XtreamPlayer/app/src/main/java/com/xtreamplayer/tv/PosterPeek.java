package com.xtreamplayer.tv;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Movies / Series: rest on a poster for a couple of seconds and a panel of its details fades in
 * beside it (name, year and rating, the story). Any key hides it straight away, so moving on keeps
 * scrolling; resting on the next poster shows that one's.
 */
final class PosterPeek {
    static final long AFTER_MS = 2200;
    private static final Pattern BRACKET_YEAR = Pattern.compile("[(\\[]((?:19|20)\\d{2})[)\\]]\\s*$");
    private static final Pattern ANY_YEAR = Pattern.compile("\\b((?:19|20)\\d{2})\\b");

    interface CanShow { boolean now(); }

    private final Activity a;
    private final CanShow canShow;
    private View panel;
    private Runnable pending;

    PosterPeek(Activity a, CanShow canShow) {
        this.a = a;
        this.canShow = canShow;
    }

    /** A poster became the selected one: hide what's showing, show this one's after a moment. */
    void selected(final View cell, final Item it) {
        hide();
        if (cell == null || it == null) return;
        pending = new Runnable() { public void run() { pending = null; show(cell, it); } };
        Async.MAIN.postDelayed(pending, AFTER_MS);
    }

    void hide() {
        if (pending != null) { Async.MAIN.removeCallbacks(pending); pending = null; }
        if (panel != null) {
            final View p = panel;
            panel = null;
            p.animate().alpha(0f).setDuration(150).withEndAction(new Runnable() {
                public void run() { if (p.getParent() instanceof ViewGroup) ((ViewGroup) p.getParent()).removeView(p); }
            }).start();
        }
    }

    /** "Quiet Witness 1987 (1994)" → "1994". */
    static String year(String name) {
        if (name == null) return "";
        Matcher b = BRACKET_YEAR.matcher(name);
        if (b.find()) return b.group(1);
        Matcher m = ANY_YEAR.matcher(name);
        String y = "";
        while (m.find()) y = m.group(1);
        return y;
    }

    static String cleanTitle(String name) {
        if (name == null) return "";
        String t = name.replaceFirst("\\s*[(\\[](19|20)\\d{2}[)\\]]\\s*$", "").trim();
        return t.isEmpty() ? name : t;
    }

    private void show(View cell, Item it) {
        if (a.isFinishing() || !cell.isAttachedToWindow() || !canShow.now()) return;
        final FrameLayout host = a.findViewById(android.R.id.content);
        if (host == null) return;
        LinearLayout p = new LinearLayout(a);
        p.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(a, 24);
        p.setPadding(pad, pad, pad, Ui.dp(a, 20));
        p.setBackground(Ui.rounded(a, Ui.SHEET, 0, 22));
        p.setElevation(Ui.dp(a, 16));
        TextView title = Ui.lines(Ui.title(a, cleanTitle(it.name), 26), 2);
        p.addView(title);
        StringBuilder meta = new StringBuilder(year(it.name));
        if (it.info != null) for (String part : it.info.split("\\s*[·•|]\\s*")) {
            if (part.isEmpty() || meta.toString().contains(part)) continue;
            if (meta.length() > 0) meta.append("  ·  ");
            meta.append(part);
        }
        if (meta.length() > 0) {
            TextView m = Ui.oneLine(Ui.medium(a, meta.toString(), 16, Ui.TEXT2));
            m.setPadding(0, Ui.dp(a, 8), 0, 0);
            p.addView(m);
        }
        if (it.plot != null && !it.plot.trim().isEmpty()) {
            TextView d = Ui.lines(Ui.text(a, it.plot.trim(), 17, Ui.TEXT), 7);
            d.setLineSpacing(0, 1.2f);
            d.setPadding(0, Ui.dp(a, 14), 0, 0);
            p.addView(d);
        }
        TextView hint = Ui.text(a, "OK for more  ·  ◀ ▶ to keep browsing", 14, Ui.TEXT3);
        hint.setPadding(0, Ui.dp(a, 16), 0, 0);
        p.addView(hint);

        int w = Ui.dp(a, 520), gap = Ui.dp(a, 22);
        int[] c = new int[2], h = new int[2];
        cell.getLocationInWindow(c);
        host.getLocationInWindow(h);
        int cx = c[0] - h[0], cy = c[1] - h[1];
        boolean right = cx + cell.getWidth() + gap + w <= host.getWidth() - Ui.dp(a, 8);
        int left = right ? cx + cell.getWidth() + gap : cx - gap - w;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Math.max(0, left);
        lp.topMargin = Math.max(0, cy);
        host.addView(p, lp);
        p.setAlpha(0f);
        p.setScaleX(0.94f);
        p.setScaleY(0.94f);
        p.setPivotX(right ? 0 : w);
        p.setPivotY(0);
        panel = p;
        final LinearLayout fp = p;
        p.post(new Runnable() { // keep it on screen
            public void run() {
                if (panel != fp) return;
                int over = lp.topMargin + fp.getHeight() - (host.getHeight() - Ui.dp(a, 8));
                if (over > 0) { lp.topMargin = Math.max(0, lp.topMargin - over); fp.setLayoutParams(lp); }
                fp.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Ui.ANIM_MS).setInterpolator(Ui.EASE).start();
            }
        });
    }
}
