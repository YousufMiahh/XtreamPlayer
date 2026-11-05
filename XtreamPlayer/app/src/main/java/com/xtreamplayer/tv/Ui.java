package com.xtreamplayer.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/** Shared look & feel, built in code so the remote's focus is always clearly visible. */
public final class Ui {
    private Ui() {}

    public static final int BG = 0xFF0F1115;
    public static final int PANEL = 0xFF171A21;
    public static final int CARD = 0xFF1E222B;
    public static final int FOCUS = 0xFF26324A;
    public static final int ACCENT = 0xFF3D8BFF;
    public static final int TEXT = 0xFFF2F4F8;
    public static final int MUTED = 0xFF9AA3B2;

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    public static GradientDrawable rounded(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(dp(c, 3), stroke);
        return g;
    }

    /** Background that lights up when focused/selected/pressed. */
    public static Drawable focusable(Context c, int normalFill) {
        StateListDrawable s = new StateListDrawable();
        Drawable on = rounded(c, FOCUS, ACCENT, 10);
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{android.R.attr.state_pressed}, on);
        s.addState(new int[]{android.R.attr.state_selected}, on);
        s.addState(new int[]{}, normalFill == 0 ? new ColorDrawable(Color.TRANSPARENT) : rounded(c, normalFill, 0, 10));
        return s;
    }

    public static Drawable listSelector(Context c) {
        return rounded(c, FOCUS, ACCENT, 8);
    }

    public static Drawable gridSelector(Context c) {
        return rounded(c, 0x223D8BFF, ACCENT, 10);
    }

    public static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    public static TextView title(Context c, String s, float sp) {
        TextView t = text(c, s, sp, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static TextView oneLine(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    public static Button button(Context c, String s) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setBackground(focusable(c, CARD));
        b.setFocusable(true);
        b.setGravity(Gravity.CENTER);
        int p = dp(c, 14);
        b.setPadding(p * 2, p, p * 2, p);
        return b;
    }

    public static EditText input(Context c, String hint, int inputType) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        e.setBackground(focusable(c, CARD));
        int p = dp(c, 14);
        e.setPadding(p, p, p, p);
        return e;
    }
}
