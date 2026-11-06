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
    private static final long SHOW_MS = 1400, MAX_WAIT_MS = 6000;
    private boolean left, shownLongEnough, libraryReady;

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

        FrameLayout root = new FrameLayout(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        ImageView logo = new ImageView(this);
        try (InputStream in = getAssets().open("logo.png")) {
            logo.setImageBitmap(BitmapFactory.decodeStream(in));
        } catch (Exception ignored) {
        }
        logo.setElevation(Ui.dp(this, 18));
        int s = Ui.dp(this, 190);
        col.addView(logo, new LinearLayout.LayoutParams(s, s));

        TextView name = Ui.title(this, "YM Player", 40);
        name.setPadding(0, Ui.dp(this, 30), 0, Ui.dp(this, 8));
        col.addView(name);
        TextView by = Ui.medium(this, "Developed by Yousuf Miah", 18, Ui.TEXT2);
        col.addView(by);

        setContentView(root);

        DecelerateInterpolator ease = new DecelerateInterpolator(2f);
        logo.setAlpha(0f);
        logo.setScaleX(0.86f);
        logo.setScaleY(0.86f);
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(ease).start();
        name.setAlpha(0f);
        name.setTranslationY(Ui.dp(this, 14));
        name.animate().alpha(1f).translationY(0f).setStartDelay(180).setDuration(450).setInterpolator(ease).start();
        by.setAlpha(0f);
        by.animate().alpha(1f).setStartDelay(380).setDuration(450).start();

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
        root.postDelayed(new Runnable() {
            public void run() {
                shownLongEnough = true;
                if (libraryReady) next.run();
            }
        }, SHOW_MS);
        root.postDelayed(next, MAX_WAIT_MS); // never keep you waiting long
    }

    @Override
    public void onBackPressed() {
        // Let the splash finish rather than closing the app halfway through.
    }
}
