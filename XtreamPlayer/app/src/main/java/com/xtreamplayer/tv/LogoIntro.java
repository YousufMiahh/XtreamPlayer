package com.xtreamplayer.tv;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The launch logo. NameIntro brings "YOUSUF MIAH" down to its Y and M and lands them here, then
 * lock() builds the rest: the tile springs up behind them with a punch and a ring, the play button
 * pops up and a sweep of light crosses the tile. (play() is the older fly-in version.)
 */
final class LogoIntro extends FrameLayout {
    static final long TEXT_AT_MS = 1050; // when the name underneath can come in
    private static final int[] GRADIENT = {0xFF5153FB, 0xFF8A3FE6, 0xFFF35E80};

    private final int size;
    private final FrameLayout tile;
    private final View shine, ring, play;
    private final TextView y, m, yTrail, mTrail;

    LogoIntro(Context c, int sizePx) {
        super(c);
        size = sizePx;
        setClipChildren(false);
        setClipToPadding(false);

        ring = new View(c);
        GradientDrawable rd = new GradientDrawable();
        rd.setShape(GradientDrawable.OVAL);
        rd.setStroke(Math.max(2, size / 48), 0xE6FFFFFF);
        ring.setBackground(rd);
        ring.setAlpha(0f);
        int rs = size / 3;
        LayoutParams rl = new LayoutParams(rs, rs, Gravity.CENTER);
        addView(ring, rl);

        tile = new FrameLayout(c);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, GRADIENT);
        g.setCornerRadius(size * 0.23f);
        tile.setBackground(g);
        tile.setClipToOutline(true);
        tile.setElevation(size / 10f);
        shine = new View(c);
        shine.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x8CFFFFFF, 0x00FFFFFF}));
        shine.setRotation(25f);
        tile.addView(shine, new LayoutParams((int) (size * 0.4f), size * 2, Gravity.CENTER_VERTICAL));
        addView(tile, new LayoutParams(size, size, Gravity.CENTER));

        // The letters sit above the tile so they can fly in from off-screen
        FrameLayout word = new FrameLayout(c);
        word.setClipChildren(false);
        LinearLayout row = new LinearLayout(c);
        row.setClipChildren(false);
        row.setGravity(Gravity.CENTER);
        y = letter(c, "Y");
        m = letter(c, "M");
        yTrail = letter(c, "Y");
        mTrail = letter(c, "M");
        FrameLayout ys = stack(c, yTrail, y), ms = stack(c, mTrail, m);
        row.addView(ys);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.leftMargin = size / 48;
        row.addView(ms, mlp);
        LayoutParams wl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        wl.bottomMargin = (int) (size * 0.08f); // a little above the middle, as in the icon
        word.addView(row);
        word.setTranslationZ(size / 9f);
        addView(word, wl);

        play = new Triangle(c);
        play.setAlpha(0f);
        int pw = (int) (size * 0.085f);
        LayoutParams pl = new LayoutParams(pw, (int) (pw * 1.1f), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        pl.topMargin = (int) (size * 0.72f);
        play.setTranslationZ(size / 9f);
        addView(play, pl);

        tile.setAlpha(0f);
        for (View v : new View[]{y, m, yTrail, mTrail}) v.setAlpha(0f);
    }

    private TextView letter(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.5f);
        t.setIncludeFontPadding(false);
        t.setLetterSpacing(-0.02f);
        t.setShadowLayer(size / 14f, 0, size / 32f, 0x592A0A78);
        return t;
    }

    private static FrameLayout stack(Context c, View back, View front) {
        FrameLayout f = new FrameLayout(c);
        f.setClipChildren(false);
        f.addView(back);
        f.addView(front);
        return f;
    }

    /** Runs the intro; `text` is called when the name underneath should come in. */
    void play(final Runnable text) {
        int screen = getResources().getDisplayMetrics().widthPixels;
        float far = screen * 0.6f;

        tile.setScaleX(0.35f);
        tile.setScaleY(0.35f);
        tile.setRotation(-14f);
        ObjectAnimator tileIn = ObjectAnimator.ofPropertyValuesHolder(tile,
                PropertyValuesHolder.ofFloat(ALPHA, 0f, 1f),
                PropertyValuesHolder.ofFloat(SCALE_X, 0.35f, 1f),
                PropertyValuesHolder.ofFloat(SCALE_Y, 0.35f, 1f),
                PropertyValuesHolder.ofFloat(ROTATION, -14f, 0f));
        tileIn.setDuration(620);
        tileIn.setInterpolator(new OvershootInterpolator(1.6f));

        AnimatorSet set = new AnimatorSet();
        set.play(tileIn);
        set.play(fly(y, -far, 160, false)).with(tileIn);
        set.play(fly(m, far, 160, false)).with(tileIn);
        set.play(fly(yTrail, -far, 200, true)).with(tileIn);
        set.play(fly(mTrail, far, 200, true)).with(tileIn);

        // Impact: a punch of the tile, a brief flash and a ring going out
        ObjectAnimator punch = ObjectAnimator.ofPropertyValuesHolder(tile,
                PropertyValuesHolder.ofFloat(SCALE_X, 1f, 1.08f, 1f),
                PropertyValuesHolder.ofFloat(SCALE_Y, 1f, 1.08f, 1f));
        punch.setDuration(360);
        punch.setStartDelay(640);
        set.play(punch).with(tileIn);
        ObjectAnimator ringOut = ObjectAnimator.ofPropertyValuesHolder(ring,
                PropertyValuesHolder.ofFloat(ALPHA, 0.95f, 0f),
                PropertyValuesHolder.ofFloat(SCALE_X, 0.4f, 5f),
                PropertyValuesHolder.ofFloat(SCALE_Y, 0.4f, 5f));
        ringOut.setDuration(620);
        ringOut.setStartDelay(650);
        ringOut.setInterpolator(new DecelerateInterpolator(1.6f));
        set.play(ringOut).with(tileIn);

        play.setScaleX(0f);
        play.setScaleY(0f);
        play.setTranslationY(size / 19f);
        ObjectAnimator pop = ObjectAnimator.ofPropertyValuesHolder(play,
                PropertyValuesHolder.ofFloat(ALPHA, 0f, 1f),
                PropertyValuesHolder.ofFloat(SCALE_X, 0f, 1f),
                PropertyValuesHolder.ofFloat(SCALE_Y, 0f, 1f),
                PropertyValuesHolder.ofFloat(TRANSLATION_Y, size / 19f, 0f));
        pop.setDuration(480);
        pop.setStartDelay(820);
        pop.setInterpolator(new OvershootInterpolator(3f));
        set.play(pop).with(tileIn);

        float sw = size * 0.4f;
        ObjectAnimator sweep = ObjectAnimator.ofFloat(shine, TRANSLATION_X, -sw * 1.6f, size + sw);
        sweep.setDuration(800);
        sweep.setStartDelay(1000);
        sweep.setInterpolator(new PathInterpolator(0.45f, 0f, 0.55f, 1f));
        shine.setTranslationX(-sw * 1.6f);
        set.play(sweep).with(tileIn);
        set.start();

        if (text != null) postDelayed(text, TEXT_AT_MS);
    }

    float letterPx() { return size * 0.5f; }
    View y() { return y; }
    View m() { return m; }

    /**
     * The name's Y and M have just landed on this logo's letters (NameIntro): show them, spring the
     * tile up behind them with a punch, a ring of light, the play button and a sweep of shine.
     * `text` brings in the name underneath.
     */
    void lock(final Runnable text) {
        y.setAlpha(1f);
        m.setAlpha(1f);
        tile.setScaleX(0.35f);
        tile.setScaleY(0.35f);
        tile.setRotation(-14f);
        ObjectAnimator tileIn = ObjectAnimator.ofPropertyValuesHolder(tile,
                PropertyValuesHolder.ofFloat(ALPHA, 0f, 1f),
                PropertyValuesHolder.ofFloat(SCALE_X, 0.35f, 1f),
                PropertyValuesHolder.ofFloat(SCALE_Y, 0.35f, 1f),
                PropertyValuesHolder.ofFloat(ROTATION, -14f, 0f));
        tileIn.setDuration(620);
        tileIn.setInterpolator(new OvershootInterpolator(1.6f));
        AnimatorSet set = new AnimatorSet();
        set.play(tileIn);
        ObjectAnimator punch = ObjectAnimator.ofPropertyValuesHolder(tile,
                PropertyValuesHolder.ofFloat(SCALE_X, 1f, 1.08f, 1f),
                PropertyValuesHolder.ofFloat(SCALE_Y, 1f, 1.08f, 1f));
        punch.setDuration(360);
        punch.setStartDelay(640);
        set.play(punch).with(tileIn);
        ObjectAnimator ringOut = ObjectAnimator.ofPropertyValuesHolder(ring,
                PropertyValuesHolder.ofFloat(ALPHA, 0.95f, 0f),
                PropertyValuesHolder.ofFloat(SCALE_X, 0.4f, 5f),
                PropertyValuesHolder.ofFloat(SCALE_Y, 0.4f, 5f));
        ringOut.setDuration(620);
        ringOut.setStartDelay(380);
        ringOut.setInterpolator(new DecelerateInterpolator(1.6f));
        set.play(ringOut).with(tileIn);
        play.setScaleX(0f);
        play.setScaleY(0f);
        ObjectAnimator pop = ObjectAnimator.ofPropertyValuesHolder(play,
                PropertyValuesHolder.ofFloat(ALPHA, 0f, 1f),
                PropertyValuesHolder.ofFloat(SCALE_X, 0f, 1f),
                PropertyValuesHolder.ofFloat(SCALE_Y, 0f, 1f),
                PropertyValuesHolder.ofFloat(TRANSLATION_Y, size / 19f, 0f));
        pop.setDuration(480);
        pop.setStartDelay(420);
        pop.setInterpolator(new OvershootInterpolator(3f));
        set.play(pop).with(tileIn);
        float sw = size * 0.4f;
        shine.setTranslationX(-sw * 1.6f);
        ObjectAnimator sweep = ObjectAnimator.ofFloat(shine, TRANSLATION_X, -sw * 1.6f, size + sw);
        sweep.setDuration(800);
        sweep.setStartDelay(650);
        sweep.setInterpolator(new PathInterpolator(0.45f, 0f, 0.55f, 1f));
        set.play(sweep).with(tileIn);
        set.start();
        if (text != null) postDelayed(text, 450);
    }

    /** A letter (or its trail) flying in from `from`, overshooting a touch, then settling. */
    private Animator fly(final View v, float from, long delay, boolean trail) {
        float over = Math.signum(from) * -size / 19f;
        v.setTranslationX(from);
        v.setScaleX(1.6f);
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat(TRANSLATION_X, from, over, 0f),
                PropertyValuesHolder.ofFloat(SCALE_X, 1.6f, 0.94f, 1f),
                PropertyValuesHolder.ofFloat(ALPHA, trail ? new float[]{0f, 0.45f, 0f} : new float[]{0f, 1f, 1f}));
        a.setDuration(720);
        a.setStartDelay(delay);
        a.setInterpolator(new PathInterpolator(0.16f, 1f, 0.3f, 1f));
        if (trail) {
            a.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator x) { v.setVisibility(INVISIBLE); }
            });
        }
        return a;
    }

    /** The small white play triangle under the letters. */
    private static final class Triangle extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Triangle(Context c) {
            super(c);
            p.setColor(Color.WHITE);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            path.reset();
            path.moveTo(0, 0);
            path.lineTo(w, h / 2f);
            path.lineTo(0, h);
            path.close();
            cv.drawPath(path, p);
        }
    }
}
