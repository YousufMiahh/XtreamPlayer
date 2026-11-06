package com.xtreamplayer.tv;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

import java.util.WeakHashMap;

/**
 * Apple TV-style ambient colour: each screen's background glows with a colour taken from
 * whatever artwork you're on, fading smoothly as you move around.
 */
public final class Ambient {
    private Ambient() {}

    /** Resting colours per section, so screens have colour even before you pick anything. */
    public static final int HOME = 0xFF2A2F7A, LIVE = 0xFF0F5C66, MOVIES = 0xFF6A1F4A, SERIES = 0xFF1F4E8C,
            SEARCH = 0xFF4A2A80, SETTINGS = 0xFF3A3F52;

    private static class Holder {
        GradientDrawable g;
        int current, base;
        String url;
        ValueAnimator anim;
    }

    private static final WeakHashMap<Activity, Holder> HOLDERS = new WeakHashMap<>();

    /** Colour handed from the screen you just left, so the new one can fade from it. */
    private static Integer handoff;

    /** Gives the window a tinted gradient background. Call after Ui.init and before setContentView. */
    public static void install(Activity a, int base) {
        Holder h = new Holder();
        h.base = base;
        h.current = handoff != null ? handoff : tone(base);
        h.g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors(h.current));
        a.getWindow().setBackgroundDrawable(h.g);
        HOLDERS.put(a, h);
        if (handoff != null) {
            handoff = null;
            animateTo(h, tone(base));
        }
    }

    /** Remember this screen's colour for the next one (called when switching sections). */
    public static void handOff(Activity from) {
        Holder h = HOLDERS.get(from);
        handoff = h == null ? null : h.current;
    }

    /** A screen coming back into view (e.g. Home): fade from the colour handed off to its own. */
    public static void takeHandoff(Activity a) {
        Holder h = HOLDERS.get(a);
        if (h == null || handoff == null) return;
        h.current = handoff;
        h.g.setColors(colors(h.current));
        h.url = null;
        handoff = null;
        animateTo(h, tone(h.base));
    }

    /** Fade the background towards the main colour of this artwork. */
    public static void tint(Context c, String imageUrl) {
        final Activity a = activity(c);
        if (a == null || imageUrl == null) return;
        final Holder h = HOLDERS.get(a);
        if (h == null || imageUrl.equals(h.url)) return;
        h.url = imageUrl;
        ImageLoader.color(imageUrl, new ImageLoader.ColorCallback() {
            public void color(int avg) {
                if (a.isFinishing()) return;
                animateTo(h, tone(avg));
            }
        });
    }

    /** Fade back to a fixed colour. */
    public static void tintColor(Context c, int color) {
        Activity a = activity(c);
        Holder h = a == null ? null : HOLDERS.get(a);
        if (h == null) return;
        h.url = null;
        animateTo(h, tone(color));
    }

    private static void animateTo(final Holder h, int target) {
        if (h.anim != null) h.anim.cancel();
        h.anim = ValueAnimator.ofObject(new ArgbEvaluator(), h.current, target);
        h.anim.setDuration(700);
        h.anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator v) {
                h.current = (Integer) v.getAnimatedValue();
                h.g.setColors(colors(h.current));
            }
        });
        h.anim.start();
    }

    private static int[] colors(int top) {
        return new int[]{top, mix(top, Ui.BG, 0.45f), mix(top, Ui.BG, 0.85f), Ui.BG};
    }

    /** Turns any colour into a rich-but-calm background tone that suits the theme. */
    static int tone(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        if (hsv[1] < 0.12f) { // grey/white/black artwork: use a calm slate blue instead of a muddy tint
            hsv[0] = 228f;
            hsv[1] = 0.30f;
        }
        if (Ui.DARK) {
            hsv[1] = clamp(Math.max(hsv[1] * 1.25f, 0.35f), 0.35f, 0.85f);
            hsv[2] = 0.46f;
        } else {
            hsv[1] = clamp(hsv[1] * 0.55f, 0.10f, 0.32f);
            hsv[2] = 0.97f;
        }
        return Color.HSVToColor(hsv);
    }

    static int mix(int a, int b, float t) {
        int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static Activity activity(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}
