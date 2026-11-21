package com.xtreamplayer.tv;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The launch intro: "YOUSUF MIAH" in big letters, then (like a film studio's ident) the letters fold
 * away into the Y and the M, which glide together and land in the logo as its tile springs up behind
 * them (LogoIntro.lock). Same timings as the Samsung app's name-intro.js.
 *
 *   0–900 ms      the name builds in, letter by letter, slowly pushing in
 *   1000–1500 ms  right to left, letters tuck into the Y or the M and vanish
 *   then          the Y and the M glide into the logo, and it finishes building
 */
final class NameIntro {
    private NameIntro() {}

    static final long TOTAL_MS = 3000;
    static final String NAME = "YOUSUF MIAH";
    private static final float PUSH = 1.05f;

    private static final PathInterpolator OUT = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    private static final PathInterpolator IN_OUT = new PathInterpolator(0.65f, 0f, 0.35f, 1f);

    /** Adds the word to `root` and runs the intro; `text` brings in the name under the logo. */
    static void play(final FrameLayout root, final LogoIntro logo, final Runnable text) {
        final Context c = root.getContext();
        float scale = 1f;
        try { scale = Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f); } catch (Exception ignored) {}
        if (scale == 0f) { logo.lock(text); return; } // animations off: the finished logo

        // As big as the logo's letters, smaller if the whole name wouldn't fit across the screen
        float px = logo.letterPx();
        Paint measure = new Paint();
        measure.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        measure.setTextSize(px);
        float fit = c.getResources().getDisplayMetrics().widthPixels * 0.88f / PUSH;
        float w = measure.measureText(NAME) * 1.04f;
        if (w > fit) px *= fit / w;

        final LinearLayout word = new LinearLayout(c);
        word.setClipChildren(false);
        word.setClipToPadding(false);
        final List<TextView> letters = new ArrayList<>();
        final List<TextView> anchorOf = new ArrayList<>();
        TextView y = null, m = null, anchor = null;
        for (int i = 0; i < NAME.length(); i++) {
            char ch = NAME.charAt(i);
            TextView t = new TextView(c);
            t.setText(ch == ' ' ? " " : String.valueOf(ch));
            t.setTextColor(Color.WHITE);
            t.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
            t.setIncludeFontPadding(false);
            t.setShadowLayer(px * 0.3f, 0, 0, 0x8C8A3FE6);
            t.setAlpha(0f);
            if (i == 0) { y = t; anchor = t; } else if (ch == 'M' && m == null) { m = t; anchor = t; }
            letters.add(t);
            anchorOf.add(anchor);
            word.addView(t);
        }
        root.addView(word, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        for (View v : new View[]{y, m}) v.setTranslationZ(1f); // the others tuck in behind them

        // 1. the name builds in, and slowly pushes in
        for (int i = 0; i < letters.size(); i++) {
            TextView t = letters.get(i);
            t.setTranslationY(px * 0.35f);
            t.setScaleX(0.92f);
            t.setScaleY(0.92f);
            t.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setStartDelay(60 + i * 38L).setDuration(520).setInterpolator(OUT).start();
        }
        word.animate().scaleX(PUSH).scaleY(PUSH).setDuration(1000).setInterpolator(new PathInterpolator(0.3f, 0f, 0.7f, 1f)).start();

        final TextView fy = y, fm = m;
        // 2. right to left, each letter tucks in behind its Y or M and goes
        root.postDelayed(new Runnable() {
            public void run() {
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < letters.size(); i++) if (letters.get(i) != fy && letters.get(i) != fm) order.add(i);
                Collections.reverse(order);
                for (int k = 0; k < order.size(); k++) {
                    TextView t = letters.get(order.get(k));
                    TextView a = anchorOf.get(order.get(k));
                    float dx = a.getX() + a.getWidth() * 0.75f - t.getX(); // its left edge into the anchor's right side
                    t.setPivotX(0);
                    t.animate().translationX(dx).scaleX(0.05f).alpha(0f)
                            .setStartDelay(k * 42L).setDuration(360).setInterpolator(IN_OUT).start();
                }
                // 3. the Y and the M glide onto the logo's letters
                root.postDelayed(new Runnable() {
                    public void run() {
                        glide(fy, logo.y());
                        glide(fm, logo.m());
                        root.postDelayed(new Runnable() {
                            public void run() {
                                logo.lock(text);
                                word.animate().alpha(0f).setStartDelay(90).setDuration(160).withEndAction(new Runnable() {
                                    public void run() { root.removeView(word); }
                                }).start();
                            }
                        }, 560);
                    }
                }, order.size() * 42L + 120);
            }
        }, 1000);
    }

    /** Moves (and sizes) a letter of the name exactly onto a letter of the logo. */
    private static void glide(View from, View to) {
        int[] f = new int[2], t = new int[2];
        from.getLocationOnScreen(f);
        to.getLocationOnScreen(t);
        float k = ((View) from.getParent()).getScaleX();
        float fw = from.getWidth() * k, fh = from.getHeight() * k;
        float s = to.getHeight() * to.getScaleY() / fh;
        float dx = (t[0] + to.getWidth() / 2f - (f[0] + fw / 2f)) / k;
        float dy = (t[1] + to.getHeight() / 2f - (f[1] + fh / 2f)) / k;
        from.setPivotX(from.getWidth() / 2f);
        from.setPivotY(from.getHeight() / 2f);
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(from,
                PropertyValuesHolder.ofFloat(View.TRANSLATION_X, from.getTranslationX(), from.getTranslationX() + dx),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, from.getTranslationY(), from.getTranslationY() + dy),
                PropertyValuesHolder.ofFloat(View.SCALE_X, from.getScaleX(), s),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, from.getScaleY(), s));
        a.setDuration(620);
        a.setInterpolator(IN_OUT);
        a.start();
    }
}
