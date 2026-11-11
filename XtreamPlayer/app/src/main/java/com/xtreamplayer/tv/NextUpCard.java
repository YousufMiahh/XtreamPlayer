package com.xtreamplayer.tv;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Netflix-style "Watch credits  |  ▶ Next Episode" near the end of an episode. The Next Episode
 * button fills up like a timer; when it's full the next episode plays. Pressing it plays straight
 * away; Watch credits hides the card and lets this one finish.
 */
public class NextUpCard extends LinearLayout {
    static final long COUNTDOWN_MS = 10_000;

    final TextView credits;
    final FillButton next;
    private ValueAnimator timer;
    private Runnable onNext;

    public NextUpCard(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setClipChildren(false);

        credits = Ui.medium(c, "Watch credits", 20, Ui.W1);
        credits.setTypeface(Ui.semibold());
        credits.setGravity(Gravity.CENTER);
        int px = Ui.dp(c, 28);
        credits.setPadding(px, 0, px, 0);
        credits.setFocusable(true);
        paintCredits(false);
        credits.setOnFocusChangeListener(new OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) { paintCredits(has); }
        });
        addView(credits, new LayoutParams(LayoutParams.WRAP_CONTENT, Ui.dp(c, 60)));

        next = new FillButton(c);
        next.setFocusable(true);
        LayoutParams nl = new LayoutParams(Ui.dp(c, 250), Ui.dp(c, 60));
        nl.leftMargin = Ui.dp(c, 16);
        addView(next, nl);
    }

    private void paintCredits(boolean focused) {
        credits.setBackground(Ui.rounded(getContext(), focused ? 0xFFFFFFFF : 0x59FFFFFF, 0, 6));
        credits.setTextColor(focused ? 0xFF000000 : Ui.W1);
    }

    /** Shows the card and starts the countdown (paused while the video is). */
    public void start(Runnable onNext) {
        this.onNext = onNext;
        stopTimer();
        next.fill = 0;
        next.invalidate();
        timer = ValueAnimator.ofFloat(0f, 1f);
        timer.setDuration(COUNTDOWN_MS);
        timer.setInterpolator(new LinearInterpolator());
        timer.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                next.fill = (Float) a.getAnimatedValue();
                next.invalidate();
            }
        });
        timer.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) {
                if (!cancelled && NextUpCard.this.onNext != null) NextUpCard.this.onNext.run();
            }
        });
        timer.start();
    }

    public void setPaused(boolean paused) {
        if (timer == null) return;
        if (paused && timer.isRunning() && !timer.isPaused()) timer.pause();
        else if (!paused && timer.isPaused()) timer.resume();
    }

    public void stopTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    /** "▶ Next Episode" with a white fill sweeping across it as the timer runs. */
    public static class FillButton extends View {
        float fill;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), white = new Paint(Paint.ANTI_ALIAS_FLAG),
                text = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path tri = new Path();
        private final String label = "Next Episode";

        FillButton(Context c) {
            super(c);
            bg.setColor(0x59FFFFFF);
            white.setColor(0xFFFFFFFF);
            text.setTextSize(Ui.sp(c, 20));
            Typeface t = Ui.semibold();
            if (t != null) text.setTypeface(t);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dp(c, 3));
            ring.setColor(0xFFFFFFFF);
            setOnFocusChangeListener(new OnFocusChangeListener() {
                public void onFocusChange(View v, boolean has) {
                    v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(150).start();
                    v.invalidate();
                }
            });
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = Ui.dp(getContext(), 6);
            r.set(0, 0, w, h);
            c.drawRoundRect(r, rad, rad, bg);
            float fx = w * Math.max(0, Math.min(1, fill));
            c.save();
            c.clipRect(0, 0, fx, h);
            c.drawRoundRect(r, rad, rad, white);
            c.restore();

            // Play triangle + label, black over the filled part and white over the rest
            float tw = text.measureText(label), ts = h * 0.36f, gap = h * 0.22f;
            float total = ts + gap + tw, x0 = (w - total) / 2f, cy = h / 2f;
            tri.reset();
            tri.moveTo(x0, cy - ts / 2f);
            tri.lineTo(x0 + ts * 0.9f, cy);
            tri.lineTo(x0, cy + ts / 2f);
            tri.close();
            float ty = cy - (text.descent() + text.ascent()) / 2f;
            for (int pass = 0; pass < 2; pass++) {
                c.save();
                if (pass == 0) c.clipRect(0, 0, fx, h); else c.clipRect(fx, 0, w, h);
                text.setColor(pass == 0 ? 0xFF000000 : 0xFFFFFFFF);
                c.drawPath(tri, text);
                c.drawText(label, x0 + ts + gap, ty, text);
                c.restore();
            }
            if (isFocused()) {
                float in = ring.getStrokeWidth() / 2f;
                r.set(in, in, w - in, h - in);
                c.drawRoundRect(r, rad, rad, ring);
            }
        }
    }
}
