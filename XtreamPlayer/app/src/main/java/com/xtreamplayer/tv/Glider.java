package com.xtreamplayer.tv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.AbsListView;
import android.widget.FrameLayout;

/**
 * A single highlight that glides between rows of a list as you move (like the top bar),
 * instead of each row lighting up on its own. Lives behind the list in a FrameLayout.
 */
public class Glider {
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(2f);

    private final View pill;
    private final AbsListView list;
    private float y = -1, h;
    private int lastSel = -1;
    private boolean shown;
    private ValueAnimator anim;

    /** Wraps the list: returns the frame to add to the layout in place of the list. */
    public static FrameLayout wrap(Context c, AbsListView list, Glider[] out) {
        FrameLayout frame = new FrameLayout(c);
        frame.setClipChildren(false);
        Glider g = new Glider(c, frame, list);
        frame.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Adapters.setGlider(list, g);
        out[0] = g;
        return frame;
    }

    private Glider(Context c, FrameLayout frame, AbsListView list) {
        this.list = list;
        pill = new View(c);
        pill.setBackground(Ui.rounded(c, Ui.FOCUS_BG, 0, 12));
        pill.setAlpha(0f);
        frame.addView(pill, new FrameLayout.LayoutParams(0, 0));
    }

    /** Move the highlight to the selected row (glides if the selection changed). */
    void update() {
        int sel = list.getSelectedItemPosition();
        View ch = sel < 0 ? null : list.getChildAt(sel - list.getFirstVisiblePosition());
        if (!list.hasFocus() || ch == null) {
            if (shown) {
                shown = false;
                pill.animate().alpha(0f).setDuration(140).start();
            }
            lastSel = -1;
            return;
        }
        final float ty = list.getTop() + ch.getTop(), th = ch.getHeight();
        final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pill.getLayoutParams();
        lp.leftMargin = list.getLeft() + ch.getLeft();
        lp.width = ch.getWidth();
        boolean glide = shown && sel != lastSel && y >= 0;
        lastSel = sel;
        if (anim != null) anim.cancel();
        if (!shown) {
            shown = true;
            pill.animate().cancel();
            pill.animate().alpha(1f).setDuration(140).start();
        }
        if (!glide) {
            apply(lp, ty, th);
            return;
        }
        final float fy = y, fh = h;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(200);
        anim.setInterpolator(EASE);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator v) {
                float f = (Float) v.getAnimatedValue();
                apply(lp, fy + (ty - fy) * f, fh + (th - fh) * f);
            }
        });
        anim.start();
    }

    private void apply(FrameLayout.LayoutParams lp, float ny, float nh) {
        y = ny;
        h = nh;
        lp.height = Math.round(nh);
        pill.setTranslationY(ny);
        pill.setLayoutParams(lp);
    }
}
