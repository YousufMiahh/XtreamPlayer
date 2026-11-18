package com.xtreamplayer.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Pieces of the Netflix-style film / series page: the stacked buttons and the Audio & Subtitles choice. */
final class Hero {
    private Hero() {}

    /**
     * A wide button for the stack ("▶  Resume S1: Ep. 1" with its progress bar, "Play From
     * Beginning", "Episodes & More"): translucent, white with dark text when focused.
     * @param progress 0…1 to show a progress bar, or a negative number for none
     */
    static LinearLayout button(Context c, String label, float progress) {
        LinearLayout b = new LinearLayout(c);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(Ui.pill(c, 0x14FFFFFF, 12));
        b.setPadding(Ui.dp(c, 22), Ui.dp(c, 13), Ui.dp(c, 22), Ui.dp(c, 13));
        TextView t = Ui.oneLine(Ui.medium(c, label, 19, Ui.TEXT));
        t.setTypeface(Ui.semibold());
        t.setTextColor(Ui.focusText(Ui.TEXT));
        t.setDuplicateParentStateEnabled(true);
        b.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (progress >= 0) {
            LinearLayout bar = new LinearLayout(c);
            bar.setBackground(Ui.rounded(c, 0x59808080, 0, 2));
            View fill = new View(c);
            fill.setBackground(Ui.rounded(c, 0xFFE50914, 0, 2)); // Netflix red
            bar.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0.02f, Math.min(1f, progress))));
            View rest = new View(c);
            bar.addView(rest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0.0001f, 1f - progress)));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Ui.dp(c, 110), Ui.dp(c, 5));
            bl.leftMargin = Ui.dp(c, 20);
            b.addView(bar, bl);
        }
        Ui.lift(b, 1.03f);
        return b;
    }

    /** The stack of buttons, one under another. */
    static void add(LinearLayout stack, View b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(stack.getContext(), 460), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(stack.getContext(), 10);
        stack.addView(b, lp);
    }

    /** Audio & Subtitles before you start: the languages the player picks when it can. */
    static void audioSubtitles(final Activity a, final Prefs prefs) {
        final String[] subLabels = {"Subtitles: Off", "Subtitles: English", "Subtitles: as the stream sets them"};
        final String[] subValues = {"off", "en", ""};
        final String[] audioLabels = {"Audio: English", "Audio: Original (as the stream sets it)"};
        final String[] audioValues = {"en", ""};
        String sl = prefs.subtitleLanguage(), al = prefs.audioLanguage();
        final String[] items = new String[subLabels.length + audioLabels.length];
        for (int i = 0; i < subLabels.length; i++) items[i] = (subValues[i].equals(sl) ? "✓  " : "     ") + subLabels[i];
        for (int i = 0; i < audioLabels.length; i++) items[subLabels.length + i] = (audioValues[i].equals(al) ? "✓  " : "     ") + audioLabels[i];
        new AlertDialog.Builder(a, Ui.DARK ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Audio & Subtitles")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which < subLabels.length) prefs.setSubtitleLanguage(subValues[which]);
                        else prefs.setAudioLanguage(audioValues[which - subLabels.length]);
                        android.widget.Toast.makeText(a, "Used from the next thing you play", android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }
}
