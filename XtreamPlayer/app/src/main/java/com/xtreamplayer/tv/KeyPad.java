package com.xtreamplayer.tv;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The on-screen keyboard for searching, Netflix-style: Space and Delete across the top, then
 * a–z and 0–9 six to a row. Types straight into a search box, so the TV's own keyboard never
 * comes up. Hold OK on Delete (or press ☰ on it) to clear everything.
 */
final class KeyPad extends LinearLayout {
    static final int COLS = 6;
    private static final String KEYS = "abcdefghijklmnopqrstuvwxyz1234567890";

    private final EditText box;
    private TextView first;

    KeyPad(Context c, EditText target) {
        super(c);
        box = target;
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        // The box only shows what's been typed: it never takes focus, so the system keyboard stays away
        box.setFocusable(false);
        box.setFocusableInTouchMode(false);
        box.setShowSoftInputOnFocus(false);
        box.setCursorVisible(false);

        int gap = Ui.dp(c, 6);
        LinearLayout top = row(c);
        top.addView(key(c, "Space", 17, " "), weighted(3, gap));
        TextView del = key(c, "Delete", 17, null);
        del.setOnClickListener(new OnClickListener() { public void onClick(View v) { backspace(); } });
        del.setOnLongClickListener(new OnLongClickListener() {
            public boolean onLongClick(View v) { clear(); return true; }
        });
        del.setOnKeyListener(new OnKeyListener() {
            public boolean onKey(View v, int code, KeyEvent e) {
                if (code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_DOWN) { clear(); return true; }
                return false;
            }
        });
        top.addView(del, weighted(3, gap));
        addView(top, rowParams(gap));
        LinearLayout r = null;
        for (int i = 0; i < KEYS.length(); i++) {
            if (i % COLS == 0) {
                r = row(c);
                addView(r, rowParams(gap));
            }
            String ch = String.valueOf(KEYS.charAt(i));
            TextView k = key(c, ch, 22, ch);
            if (i == 0) first = k;
            r.addView(k, weighted(1, gap));
        }
    }

    /** Where focus goes when you arrive at the keyboard. */
    void focusFirst() { if (first != null) first.requestFocus(); }

    private static LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        return r;
    }

    private static LayoutParams rowParams(int gap) {
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = gap;
        return lp;
    }

    private static LayoutParams weighted(float w, int gap) {
        LayoutParams lp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
        lp.leftMargin = lp.rightMargin = gap / 2;
        return lp;
    }

    private TextView key(Context c, String label, float sp, final String types) {
        TextView k = Ui.medium(c, label, sp, Ui.TEXT);
        k.setTypeface(Ui.semibold());
        k.setGravity(Gravity.CENTER);
        k.setTextColor(Ui.focusText(Ui.TEXT));
        k.setBackground(Ui.pill(c, Ui.SURFACE_HI, 8));
        k.setFocusable(true);
        k.setMinHeight(Ui.dp(c, 44));
        k.setIncludeFontPadding(false);
        if (types != null) {
            k.setOnClickListener(new OnClickListener() { public void onClick(View v) { type(types); } });
        }
        return k;
    }

    private void type(String s) {
        if (" ".equals(s) && (box.length() == 0 || box.getText().toString().endsWith(" "))) return;
        box.getText().append(s);
    }

    private void backspace() {
        int n = box.length();
        if (n > 0) box.getText().delete(n - 1, n);
    }

    private void clear() { box.getText().clear(); }
}
