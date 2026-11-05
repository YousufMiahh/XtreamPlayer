package com.xtreamplayer.tv;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Focusable cards and horizontal shelves for the home, search and detail screens. */
public final class Cards {
    private Cards() {}

    public enum Shape { POSTER, LANDSCAPE, CHANNEL }

    public interface OnPick {
        void pick(Item item, View card);
    }

    public interface OnMenu {
        void menu(Item item, View card);
    }

    /** One card: rounded artwork + title (+ optional subtitle and progress). */
    public static LinearLayout card(Context c, Shape shape, String image, String title, String subtitle, float progress) {
        int w, h;
        switch (shape) {
            case POSTER: w = Ui.dp(c, 160); h = Ui.dp(c, 240); break;
            case CHANNEL: w = Ui.dp(c, 220); h = Ui.dp(c, 124); break;
            default: w = Ui.dp(c, 300); h = Ui.dp(c, 169); break;
        }
        final LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setClipChildren(false);

        FrameLayout art = new FrameLayout(c);
        art.setBackground(Ui.rounded(c, Ui.SURFACE, 0, 14));
        art.setClipToOutline(true);
        art.setElevation(Ui.dp(c, 3));
        ImageView img = new ImageView(c);
        if (shape == Shape.CHANNEL) {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int p = Ui.dp(c, 22);
            img.setPadding(p, p, p, p);
        } else {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        }
        art.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (image == null && shape != Shape.POSTER) {
            TextView ph = Ui.title(c, initials(title), 30);
            ph.setTextColor(Ui.TEXT3);
            ph.setGravity(Gravity.CENTER);
            art.addView(ph, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (progress > 0) {
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 5), Gravity.BOTTOM);
            int m = Ui.dp(c, 12);
            bp.setMargins(m, 0, m, m);
            art.addView(progressBar(c, progress), bp);
        }
        card.addView(art, new LinearLayout.LayoutParams(w, h));
        ImageLoader.load(img, image, shape == Shape.POSTER ? 320 : 480, shape == Shape.POSTER ? 480 : 270);

        final TextView t = Ui.oneLine(Ui.medium(c, title, 16, Ui.TEXT2));
        t.setPadding(Ui.dp(c, 2), Ui.dp(c, 12), 0, 0);
        card.addView(t, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        final TextView s = Ui.oneLine(Ui.text(c, subtitle == null ? "" : subtitle, 14, Ui.TEXT3));
        s.setPadding(Ui.dp(c, 2), Ui.dp(c, 4), 0, 0);
        if (subtitle != null && !subtitle.isEmpty()) card.addView(s, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                Ui.animateFocus(v, has, 1.08f);
                t.setTextColor(has ? Ui.TEXT : Ui.TEXT2);
                s.setTextColor(has ? Ui.TEXT2 : Ui.TEXT3);
            }
        });
        return card;
    }

    public static View progressBar(Context c, float progress) {
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(Ui.rounded(c, 0x59FFFFFF, 0, 3));
        bar.setClipToOutline(true);
        View fill = new View(c);
        fill.setBackground(Ui.rounded(c, Color.WHITE, 0, 3));
        float p = Math.max(0.02f, Math.min(1f, progress));
        bar.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, p));
        View rest = new View(c);
        bar.addView(rest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - p + 0.0001f));
        return bar;
    }

    /** A titled horizontal shelf of cards. */
    public static LinearLayout shelf(final Context c, String title, final List<Item> items, final Shape shape,
                                     final Prefs prefs, final OnPick pick, final OnMenu menu) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        TextView h = Ui.text(c, title, 22, Ui.TEXT2);
        h.setTypeface(Ui.semibold());
        h.setPadding(Ui.dp(c, 64), Ui.dp(c, 18), 0, Ui.dp(c, 6));
        box.addView(h);

        HorizontalScrollView sv = new HorizontalScrollView(c);
        sv.setHorizontalScrollBarEnabled(false);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        sv.setFillViewport(false);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        int vp = Ui.dp(c, 22);
        row.setPadding(Ui.dp(c, 64), vp, Ui.dp(c, 64), vp);
        for (final Item it : items) {
            String sub = null;
            String img = it.icon;
            float progress = 0;
            if (shape == Shape.LANDSCAPE) {
                long pos = prefs.position(it), dur = prefs.duration(it);
                if (dur > 0) progress = pos / (float) dur;
                if (Item.EPISODE.equals(it.type)) {
                    sub = it.parentName;
                    if (img == null) img = it.parentIcon;
                } else {
                    sub = dur > 0 ? SeriesActivity.fmt(Math.max(0, dur - pos)) + " left" : null;
                }
            }
            LinearLayout card = card(c, shape, img, it.name, sub, progress);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(c, 32);
            row.addView(card, lp);
            card.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { pick.pick(it, v); }
            });
            if (menu != null) {
                card.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) {
                        menu.menu(it, v);
                        return true;
                    }
                });
                card.setOnKeyListener(new View.OnKeyListener() {
                    public boolean onKey(View v, int code, KeyEvent e) {
                        if (code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_DOWN) {
                            menu.menu(it, v);
                            return true;
                        }
                        return false;
                    }
                });
            }
        }
        sv.addView(row);
        box.addView(sv);
        return box;
    }

    static String initials(String s) {
        if (s == null) return "";
        s = s.replaceAll("[^\\p{L}\\p{N} ]", " ").trim();
        if (s.isEmpty()) return "";
        String[] w = s.split("\\s+");
        String r = w[0].substring(0, 1);
        if (w.length > 1) r += w[1].substring(0, 1);
        return r.toUpperCase(java.util.Locale.UK);
    }
}
