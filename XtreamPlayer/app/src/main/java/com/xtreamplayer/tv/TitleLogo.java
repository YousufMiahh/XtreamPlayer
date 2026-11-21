package com.xtreamplayer.tv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A film's or series' logo in place of its name on its page (the artwork Netflix and Apple TV
 * show), when TMDB has one. The name stays until the logo has loaded, and if there isn't one.
 */
final class TitleLogo {
    private final Activity a;
    private final TextView title;
    final ImageView view;
    private boolean small;

    /** Puts an (empty, hidden) logo next to the title in its parent and starts looking it up. */
    TitleLogo(Activity a, TextView title, String kind, String name, String tmdb, int maxWdp, int maxHdp) {
        this.a = a;
        this.title = title;
        view = new ImageView(a);
        view.setAdjustViewBounds(true);
        view.setScaleType(ImageView.ScaleType.FIT_START);
        view.setMaxWidth(Ui.dp(a, maxWdp));
        view.setMaxHeight(Ui.dp(a, maxHdp));
        view.setVisibility(View.GONE);
        ViewGroup parent = (ViewGroup) title.getParent();
        parent.addView(view, parent.indexOfChild(title), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (new Prefs(a).darkTheme()) load(kind, name, tmdb); // logos are made for dark backgrounds
    }

    private void load(final String kind, final String name, final String tmdb) {
        Async.run(new Async.Work<String>() {
            public String run() {
                String id = Tmdb.findId(a, kind, name, tmdb);
                return id == null ? null : Tmdb.logo(a, kind, id);
            }
        }, new Async.Done<String>() {
            public void done(final String url, Exception e) {
                if (url == null || a.isFinishing()) return;
                Bitmap have = ImageLoader.cached(url);
                if (have != null) { show(have); return; }
                ImageLoader.prefetch(url, 1000, 400, new Runnable() {
                    public void run() {
                        Bitmap bm = ImageLoader.cached(url);
                        if (bm != null && !a.isFinishing()) show(bm);
                    }
                });
            }
        });
    }

    private void show(Bitmap bm) {
        view.setImageBitmap(bm);
        view.setPivotX(0);
        view.setAlpha(0f);
        view.setVisibility(View.VISIBLE);
        title.setVisibility(View.GONE);
        view.post(new Runnable() {
            public void run() {
                view.setPivotY(view.getHeight());
                float s = small ? 0.5f : 1f;
                view.setScaleX(s);
                view.setScaleY(s);
                view.animate().alpha(1f).setDuration(400).setInterpolator(Ui.EASE).start();
            }
        });
    }

    /** Smaller while the seasons and episodes are up (like the header on Netflix), full size again after. */
    void setSmall(boolean on) {
        small = on;
        if (view.getVisibility() != View.VISIBLE) return;
        view.setPivotX(0);
        view.setPivotY(view.getHeight());
        float s = on ? 0.5f : 1f;
        view.animate().scaleX(s).scaleY(s).setDuration(Ui.ANIM_MS + 120).setInterpolator(Ui.EASE).start();
    }
}
