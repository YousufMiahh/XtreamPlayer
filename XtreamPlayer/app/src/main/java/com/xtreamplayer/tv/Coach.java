package com.xtreamplayer.tv;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.WeakHashMap;

/**
 * The optional quick-start tutorial: dims the screen except for one highlighted part, with a
 * short caption. OK (or a tap) for the next tip, Back to skip. Each screen has its own short
 * tour, shown the first time you open it while the tutorial is switched on.
 */
public final class Coach {
    private Coach() {}

    /** One tip. A null target shows the caption in the middle with nothing highlighted. */
    public static class Step {
        final View target;
        final String title, body;

        public Step(View target, String title, String body) {
            this.target = target;
            this.title = title;
            this.body = body;
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("coach", Context.MODE_PRIVATE);
    }

    /** Ask once, the first time Home opens. */
    public static void offer(final Activity a, final boolean tv, final Runnable startTour) {
        if (sp(a).getBoolean("asked", false)) {
            startTour.run(); // shows only if switched on and not seen yet
            return;
        }
        sp(a).edit().putBoolean("asked", true).apply();
        new android.app.AlertDialog.Builder(a, Ui.DARK ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Welcome to YM Player")
                .setMessage("Would you like a quick tour of the basics? It takes about a minute, and you can skip it at any time"
                        + (tv ? " with Back." : ".") + "\n\nYou can always watch it later from Settings.")
                .setPositiveButton("Show me", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        sp(a).edit().putBoolean("on", true).apply();
                        startTour.run();
                    }
                })
                .setNegativeButton("No thanks", null)
                .show();
    }

    /** Settings → Show Tutorial: switch it back on and forget which tours were seen. */
    public static void restart(Context c) {
        SharedPreferences.Editor e = sp(c).edit().clear();
        e.putBoolean("asked", true).putBoolean("on", true).apply();
    }

    private static boolean wants(Context c, String screen) {
        SharedPreferences s = sp(c);
        return s.getBoolean("on", false) && !s.getBoolean("seen_" + screen, false);
    }

    private static final WeakHashMap<Activity, Overlay> OPEN = new WeakHashMap<>();

    /** Shows this screen's tour if the tutorial is on and it hasn't been seen. */
    public static void show(final Activity a, final String screen, final boolean tv, final Step... steps) {
        if (!wants(a, screen) || OPEN.containsKey(a) || a.isFinishing()) return;
        final View root = a.findViewById(android.R.id.content);
        root.postDelayed(new Runnable() { // let the screen settle and fill first
            public void run() {
                if (a.isFinishing() || OPEN.containsKey(a) || !wants(a, screen)) return;
                List<Step> usable = new ArrayList<>();
                for (Step s : Arrays.asList(steps)) {
                    if (s.target == null || (s.target.isShown() && s.target.getWidth() > 0)) usable.add(s);
                }
                if (usable.isEmpty()) return;
                sp(a).edit().putBoolean("seen_" + screen, true).apply();
                Overlay o = new Overlay(a, usable, tv);
                OPEN.put(a, o);
                ((ViewGroup) root).addView(o, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                o.start();
            }
        }, 900);
    }

    public static boolean showing(Activity a) { return OPEN.containsKey(a); }

    /** Call from onBackPressed: Back skips the tour. */
    public static boolean back(Activity a) {
        Overlay o = OPEN.get(a);
        if (o == null) return false;
        o.finish();
        return true;
    }

    // ------------------------------------------------------------ the overlay

    private static final class Overlay extends FrameLayout {
        private static final DecelerateInterpolator EASE = new DecelerateInterpolator(2f);
        private final Activity a;
        private final List<Step> steps;
        private final boolean tv;
        private int index = -1;
        private final RectF hole = new RectF(), from = new RectF(), to = new RectF();
        private boolean hasHole;
        private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final LinearLayout card;
        private final TextView title, body, hint, counter;
        private ValueAnimator anim;
        private View previousFocus;

        Overlay(Activity a, List<Step> steps, boolean tv) {
            super(a);
            this.a = a;
            this.steps = steps;
            this.tv = tv;
            setWillNotDraw(false);
            setClickable(true);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setElevation(Ui.dp(a, 40));
            dim.setColor(0xD9000000);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dp(a, tv ? 3 : 2));
            ring.setColor(0xFFFFFFFF);

            card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.rounded(a, Ui.DARK ? 0xFF1C1C22 : 0xFFFFFFFF, 0, tv ? 22 : 16));
            int p = Ui.dp(a, tv ? 28 : 18);
            card.setPadding(p, p, p, p);
            card.setElevation(Ui.dp(a, 12));
            counter = Ui.medium(a, "", tv ? 14 : 11, Ui.TEXT3);
            counter.setLetterSpacing(0.08f);
            title = Ui.title(a, "", tv ? 26 : 18);
            title.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 8));
            body = Ui.text(a, "", tv ? 18 : 14, Ui.TEXT);
            body.setLineSpacing(0, 1.3f);
            hint = Ui.medium(a, tv ? "OK  Next      Back  Skip tour" : "Tap to continue  ·  Back to skip", tv ? 15 : 12, Ui.TEXT2);
            hint.setPadding(0, Ui.dp(a, tv ? 18 : 12), 0, 0);
            card.addView(counter);
            card.addView(title);
            card.addView(body);
            card.addView(hint);
            addView(card, new FrameLayout.LayoutParams(Ui.dp(a, tv ? 620 : 320), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
            if (!tv) {
                TextView skip = Ui.medium(a, "Skip", 14, Ui.W1);
                int sp = Ui.dp(a, 12);
                skip.setPadding(sp * 2, sp, sp * 2, sp);
                skip.setOnClickListener(new OnClickListener() { public void onClick(View v) { finish(); } });
                FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
                sl.topMargin = Ui.dp(a, 30);
                addView(skip, sl);
            }
            setOnKeyListener(new OnKeyListener() {
                public boolean onKey(View v, int code, KeyEvent e) {
                    if (code == KeyEvent.KEYCODE_BACK) {
                        if (e.getAction() == KeyEvent.ACTION_UP) finish();
                        return true;
                    }
                    if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0
                            && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                            || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                        next();
                    }
                    return true; // nothing underneath reacts while the tour is up
                }
            });
            setAlpha(0f);
        }

        void start() {
            previousFocus = a.getCurrentFocus();
            requestFocus();
            animate().alpha(1f).setDuration(250).start();
            next();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) next();
            return true;
        }

        void next() {
            index++;
            if (index >= steps.size()) {
                finish();
                return;
            }
            Step s = steps.get(index);
            counter.setText(("Tip " + (index + 1) + " of " + steps.size()).toUpperCase(java.util.Locale.UK));
            title.setText(s.title);
            body.setText(s.body);
            if (index == steps.size() - 1) hint.setText(tv ? "OK  Done" : "Tap to finish");
            from.set(hole);
            boolean hadHole = hasHole;
            hasHole = s.target != null;
            if (hasHole) {
                int[] t = new int[2], me = new int[2];
                s.target.getLocationInWindow(t);
                getLocationInWindow(me);
                float pad = Ui.dp(a, tv ? 10 : 6);
                to.set(t[0] - me[0] - pad, t[1] - me[1] - pad, t[0] - me[0] + s.target.getWidth() + pad, t[1] - me[1] + s.target.getHeight() + pad);
                to.intersect(Ui.dp(a, 4), Ui.dp(a, 4), getWidth() - Ui.dp(a, 4), getHeight() - Ui.dp(a, 4));
            } else {
                to.set(getWidth() / 2f, getHeight() / 2f, getWidth() / 2f, getHeight() / 2f);
            }
            if (!hadHole) from.set(to.centerX(), to.centerY(), to.centerX(), to.centerY());
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(380);
            anim.setInterpolator(EASE);
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                public void onAnimationUpdate(ValueAnimator v) {
                    float f = (Float) v.getAnimatedValue();
                    hole.set(from.left + (to.left - from.left) * f, from.top + (to.top - from.top) * f,
                            from.right + (to.right - from.right) * f, from.bottom + (to.bottom - from.bottom) * f);
                    invalidate();
                }
            });
            anim.start();
            placeCard();
        }

        /** Caption below the highlight if there's room, otherwise above it (or centred). */
        private void placeCard() {
            card.measure(MeasureSpec.makeMeasureSpec(((LayoutParams) card.getLayoutParams()).width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(getHeight(), MeasureSpec.AT_MOST));
            int cw = card.getMeasuredWidth(), ch = card.getMeasuredHeight(), gap = Ui.dp(a, tv ? 24 : 14);
            int W = getWidth(), H = getHeight();
            float x, y;
            if (!hasHole) {
                x = (W - cw) / 2f;
                y = (H - ch) / 2f;
            } else {
                x = Math.max(gap, Math.min(W - cw - gap, to.centerX() - cw / 2f));
                if (to.bottom + gap + ch < H - gap) y = to.bottom + gap;
                else if (to.top - gap - ch > gap) y = to.top - gap - ch;
                else { // highlight fills the height: put the card beside it
                    y = (H - ch) / 2f;
                    x = to.right + gap + cw < W ? to.right + gap : Math.max(gap, to.left - gap - cw);
                }
            }
            card.animate().cancel();
            card.setAlpha(0f);
            card.setTranslationX(x);
            card.setTranslationY(y + Ui.dp(a, 12));
            card.animate().alpha(1f).translationY(y).setStartDelay(120).setDuration(280).setInterpolator(EASE).start();
        }

        @Override
        protected void onDraw(Canvas c) {
            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
            float r = Ui.dp(a, tv ? 18 : 12);
            if (hole.width() > 1) path.addRoundRect(hole, r, r, Path.Direction.CW);
            c.drawPath(path, dim);
            if (hole.width() > 1) c.drawRoundRect(hole, r, r, ring);
        }

        void finish() {
            OPEN.remove(a);
            if (anim != null) anim.cancel();
            animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
                public void run() {
                    ViewGroup p = (ViewGroup) getParent();
                    if (p != null) p.removeView(Overlay.this);
                    if (previousFocus != null && previousFocus.isShown()) previousFocus.requestFocus();
                }
            }).start();
        }
    }
}
