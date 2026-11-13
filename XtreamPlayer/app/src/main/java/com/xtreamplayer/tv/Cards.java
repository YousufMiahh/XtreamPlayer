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
        final FrameLayout artF = art;
        final String imageF = image;
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
                artF.setForeground(has ? Ui.outline(v.getContext(), 14) : null);
                if (has) Ambient.tint(v.getContext(), imageF);
                t.setTextColor(has ? Ui.TEXT : Ui.TEXT2);
                s.setTextColor(has ? Ui.TEXT2 : Ui.TEXT3);
            }
        });
        return card;
    }

    /** Small "✓" in the corner of a card you've already watched. */
    public static TextView watchedBadge(Context c) {
        TextView b = Ui.medium(c, "✓", 15, 0xFF000000);
        b.setTypeface(Ui.semibold());
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.rounded(c, 0xF2FFFFFF, 0, 14));
        return b;
    }

    static void markWatched(LinearLayout card) {
        if (!(card.getChildAt(0) instanceof FrameLayout)) return;
        Context c = card.getContext();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28), Gravity.TOP | Gravity.END);
        lp.topMargin = lp.rightMargin = Ui.dp(c, 10);
        ((FrameLayout) card.getChildAt(0)).addView(watchedBadge(c), lp);
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

    /** A titled grid of cards that fills the width of the screen (search filtered to one type). */
    public static LinearLayout grid(final Context c, String title, final List<Item> items, final Shape shape,
                                    final Prefs prefs, final OnPick pick, final OnMenu menu) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        TextView h = Ui.text(c, title, 22, Ui.TEXT2);
        h.setTypeface(Ui.semibold());
        h.setPadding(Ui.dp(c, 64), Ui.dp(c, 18), 0, Ui.dp(c, 6));
        box.addView(h);
        int cardW = shape == Shape.POSTER ? Ui.dp(c, 160) : shape == Shape.CHANNEL ? Ui.dp(c, 220) : Ui.dp(c, 300);
        int gap = Ui.dp(c, 32);
        int usable = c.getResources().getDisplayMetrics().widthPixels - Ui.dp(c, 128);
        int cols = Math.max(1, (usable + gap) / (cardW + gap));
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                row.setPadding(Ui.dp(c, 64), Ui.dp(c, 16), Ui.dp(c, 64), Ui.dp(c, 4));
                box.addView(row);
            }
            final Item it = items.get(i);
            LinearLayout card = card(c, shape, it.icon, it.name, null, 0);
            if (prefs != null && prefs.isWatched(it)) markWatched(card);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = gap;
            row.addView(card, lp);
            wire(card, it, pick, menu);
        }
        return box;
    }

    private static void wire(View card, final Item it, final OnPick pick, final OnMenu menu) {
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pick.pick(it, v); }
        });
        if (menu == null) return;
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
                    String left = pos > 0 && dur > 0 ? SeriesActivity.fmt(Math.max(0, dur - pos)) + " left"
                            : prefs.isUpNext(it) ? "Next episode" : null;
                    sub = XtreamApi.joinNonEmpty(it.parentName, left);
                    if (img == null) img = it.parentIcon;
                } else {
                    sub = dur > 0 ? SeriesActivity.fmt(Math.max(0, dur - pos)) + " left" : null;
                }
            }
            LinearLayout card = card(c, shape, img, it.name, sub, progress);
            if (prefs != null && prefs.isWatched(it)) markWatched(card);
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

    /**
     * Shelf title followed by a small hint with the remote's Menu (three lines) button drawn in,
     * e.g. "Continue Watching    ☰ to remove from list".
     */
    public static CharSequence titleWithMenuHint(Context c, String title, String hint) {
        if (!Ui.hasMenuKey()) { // no ☰ on this remote: "Hold OK to remove from list"
            android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(title).append("      ");
            int start = b.length();
            b.append("Hold OK ").append(hint);
            b.setSpan(new android.text.style.RelativeSizeSpan(15f / 22f), start, b.length(), 0);
            b.setSpan(new android.text.style.ForegroundColorSpan(Ui.TEXT3), start, b.length(), 0);
            b.setSpan(new android.text.style.TypefaceSpan("sans-serif"), start, b.length(), 0);
            return b;
        }
        String gap = "      ";
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(title).append(gap);
        int start = b.length();
        b.append("\u2630").append("  ").append(hint);
        int end = b.length();
        float scale = 15f / 22f;
        b.setSpan(new android.text.style.RelativeSizeSpan(scale), start, end, 0);
        b.setSpan(new android.text.style.ForegroundColorSpan(Ui.TEXT3), start, end, 0);
        b.setSpan(new android.text.style.TypefaceSpan("sans-serif"), start, end, 0);
        int gw = Math.round(Ui.sp(c, 14)), gh = Math.round(Ui.sp(c, 11)); // about the height of the hint's capitals
        android.graphics.drawable.Drawable d = new MenuGlyph(Ui.TEXT3);
        d.setBounds(0, 0, gw, gh);
        b.setSpan(new android.text.style.ImageSpan(d, android.text.style.DynamicDrawableSpan.ALIGN_BASELINE),
                start, start + 1, 0);
        return b;
    }

    /** The remote's Menu button: three short rounded lines. */
    static final class MenuGlyph extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        MenuGlyph(int color) { paint.setColor(color); }
        @Override public void draw(android.graphics.Canvas cv) {
            android.graphics.Rect r = getBounds();
            float w = r.width(), h = r.height(), t = Math.max(2f, h * 0.14f), rad = t / 2f;
            for (int i = 0; i < 3; i++) {
                float y = r.top + t / 2f + (h - t) * 0.5f * i - t / 2f;
                cv.drawRoundRect(new android.graphics.RectF(r.left, y, r.left + w, y + t), rad, rad, paint);
            }
        }
        @Override public void setAlpha(int a) { paint.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { paint.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
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
