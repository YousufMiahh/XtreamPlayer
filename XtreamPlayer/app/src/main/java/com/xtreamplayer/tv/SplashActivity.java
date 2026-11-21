package com.xtreamplayer.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;

/** A brief branded screen on launch, then on to the app. */
public class SplashActivity extends Activity {
    private static final long SHOW_MS = NameIntro.TOTAL_MS + 300, MAX_WAIT_MS = 6000; // the name-to-logo intro, then on
    private boolean left, shownLongEnough, libraryReady, introStarted;
    private Runnable startIntro;

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has && startIntro != null && !introStarted) getWindow().getDecorView().postDelayed(startIntro, 120);
    }

    private final Runnable next = new Runnable() {
        public void run() {
            if (left || isFinishing()) return;
            left = true;
            // LoginActivity forwards straight to Home when you're already signed in.
            startActivity(new Intent(SplashActivity.this, LoginActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        Ambient.install(this, Ambient.HOME);

        final FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        // dark, with a glow in the middle, for "YOUSUF MIAH"
        android.graphics.drawable.GradientDrawable glow = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF1A1240, 0xFF07060F, 0xFF030308});
        glow.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.62f);
        root.setBackground(glow);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        int s = Ui.dp(this, 190);
        final LogoIntro logo = new LogoIntro(this, s); // "YOUSUF MIAH" folds into its Y and M, which land here
        col.addView(logo, new LinearLayout.LayoutParams(s, s));

        TextView name = Ui.title(this, "YM Player", 40);
        name.setPadding(0, Ui.dp(this, 30), 0, Ui.dp(this, 8));
        name.setGravity(Gravity.CENTER);
        col.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView by = Ui.medium(this, "Developed by Yousuf Miah", 18, Ui.TEXT2);
        name.setTextColor(0xFFFFFFFF); // on the dark intro whatever the theme
        by.setTextColor(0x99FFFFFF);
        by.setGravity(Gravity.CENTER); // full width, so the whole credit always fits on one line
        col.addView(by, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        final DecelerateInterpolator ease = new DecelerateInterpolator(2f);
        name.setAlpha(0f);
        name.setTranslationY(Ui.dp(this, 14));
        by.setAlpha(0f);
        final TextView nameF = name, byF = by;
        // Start once the window is really on screen: TVs show their own launch screen first, which
        // would hide the letters flying in
        startIntro = new Runnable() {
            public void run() {
                if (introStarted) return;
                introStarted = true;
                getWindow().getDecorView().postDelayed(new Runnable() {
                    public void run() {
                        shownLongEnough = true;
                        if (libraryReady) next.run();
                    }
                }, SHOW_MS);
                NameIntro.play(root, logo, new Runnable() {
                    public void run() {
                        nameF.animate().alpha(1f).translationY(0f).setDuration(500).setInterpolator(ease).start();
                        byF.animate().alpha(1f).setStartDelay(250).setDuration(450).start();
                    }
                });
            }
        };
        root.postDelayed(startIntro, 1200); // in case focus never arrives

        // While the logo shows, load Live TV (and every section's categories) from the copy
        // saved on the device, so the first tap on Live TV is instant.
        final Prefs prefs = new Prefs(this);
        if (prefs.hasAccount()) {
            final XtreamApi api = new XtreamApi(prefs);
            Async.run(new Async.Work<Boolean>() {
                public Boolean run() throws Exception {
                    if (!Repo.hasLibrary(api)) return false; // first time: the setup screen handles it
                    Repo.streams(api, Item.LIVE, null);
                    Repo.categories(api, Item.LIVE);
                    Repo.categories(api, Item.MOVIE);
                    Repo.categories(api, Item.SERIES);
                    return true;
                }
            }, new Async.Done<Boolean>() {
                public void done(Boolean r, Exception e) {
                    libraryReady = true;
                    if (shownLongEnough) next.run();
                }
            });
        } else {
            libraryReady = true;
        }

        root.postDelayed(next, MAX_WAIT_MS); // never keep you waiting long
    }

    @Override
    public void onBackPressed() {
        // Let the splash finish rather than closing the app halfway through.
    }
}
