package com.xtreamplayer.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.InputStream;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * "Getting everything ready": downloads Live TV, Movies and Series once, one after another,
 * right after a playlist is added. From then on every screen opens instantly from the copy
 * saved on the device, and updates happen quietly in the background.
 */
public class SetupActivity extends Activity {
    private static final String[] TYPES = {Item.LIVE, Item.MOVIE, Item.SERIES};
    private static final String[] NAMES = {"Live TV", "Movies", "Series"};
    private static final String[] UNITS = {"channels", "movies", "series"};

    private Prefs prefs;
    private XtreamApi api;
    private final ProgressBar[] spinners = new ProgressBar[3];
    private final TextView[] marks = new TextView[3], counts = new TextView[3];
    private TextView status;
    private LinearLayout actions;
    private volatile boolean stopped;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        Ambient.install(this, Ambient.HOME);
        prefs = new Prefs(this);
        api = new XtreamApi(prefs);

        FrameLayout root = new FrameLayout(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        root.addView(col, new FrameLayout.LayoutParams(Ui.dp(this, 720), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        ImageView logo = new ImageView(this);
        try (InputStream in = getAssets().open("logo.png")) {
            logo.setImageBitmap(BitmapFactory.decodeStream(in));
        } catch (Exception ignored) {
        }
        int s = Ui.dp(this, 110);
        col.addView(logo, new LinearLayout.LayoutParams(s, s));

        TextView title = Ui.title(this, "Getting everything ready", 36);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, Ui.dp(this, 26), 0, Ui.dp(this, 8));
        col.addView(title);
        TextView sub = Ui.text(this, "This only happens when you add a playlist. After this, everything opens instantly.", 17, Ui.TEXT2);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 0, 0, Ui.dp(this, 30));
        col.addView(sub);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(Ui.rounded(this, Ui.SURFACE_LO, 0, 22));
        int lp = Ui.dp(this, 10);
        list.setPadding(lp, lp, lp, lp);
        for (int i = 0; i < 3; i++) list.addView(row(i));
        col.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = Ui.text(this, "", 16, Ui.TEXT2);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, Ui.dp(this, 20), 0, 0);
        col.addView(status);

        actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.setClipChildren(false);
        actions.setPadding(0, Ui.dp(this, 18), 0, 0);
        col.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        run(0);
    }

    private View row(int i) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 16);
        r.setPadding(p + Ui.dp(this, 4), p, p + Ui.dp(this, 4), p);
        FrameLayout icon = new FrameLayout(this);
        spinners[i] = new ProgressBar(this);
        spinners[i].setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT));
        spinners[i].setVisibility(View.INVISIBLE);
        icon.addView(spinners[i], new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        marks[i] = Ui.title(this, "", 22);
        marks[i].setGravity(Gravity.CENTER);
        icon.addView(marks[i], new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34));
        ilp.rightMargin = Ui.dp(this, 18);
        r.addView(icon, ilp);
        TextView n = Ui.medium(this, NAMES[i], 21, Ui.TEXT);
        n.setTypeface(Ui.semibold());
        r.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        counts[i] = Ui.medium(this, "Waiting", 17, Ui.TEXT3);
        r.addView(counts[i]);
        return r;
    }

    /** Downloads section i, then the next, on a background thread. */
    private void run(final int i) {
        if (i >= TYPES.length) {
            finished();
            return;
        }
        spinners[i].setVisibility(View.VISIBLE);
        marks[i].setText("");
        counts[i].setText("Downloading…");
        counts[i].setTextColor(Ui.TEXT2);
        status.setText("");
        Async.run(new Async.Work<Integer>() {
            public Integer run() throws Exception { return Repo.downloadType(api, TYPES[i]); }
        }, new Async.Done<Integer>() {
            public void done(Integer n, Exception e) {
                if (stopped || isFinishing()) return;
                spinners[i].setVisibility(View.INVISIBLE);
                if (e != null || n == null) {
                    marks[i].setText("✕");
                    marks[i].setTextColor(Ui.RED);
                    counts[i].setText("Didn't load");
                    failed(i, e);
                    return;
                }
                marks[i].setText("✓");
                marks[i].setTextColor(0xFF34C759);
                counts[i].setText(NumberFormat.getIntegerInstance(Locale.UK).format(n) + " " + UNITS[i]);
                run(i + 1);
            }
        });
    }

    private void failed(final int i, Exception e) {
        status.setText("Couldn't download " + NAMES[i] + ": " + Async.message(e));
        actions.removeAllViews();
        Button retry = Ui.button(this, "Try Again");
        retry.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                actions.removeAllViews();
                run(i);
            }
        });
        Button skip = Ui.button(this, "Continue Anyway");
        skip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { goHome(); }
        });
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.leftMargin = l.rightMargin = Ui.dp(this, 10);
        actions.addView(retry, l);
        actions.addView(skip, l);
        retry.requestFocus();
    }

    private void finished() {
        Repo.markRefreshed(prefs);
        status.setText("All set");
        status.setTextColor(Ui.TEXT);
        status.postDelayed(new Runnable() {
            public void run() { goHome(); }
        }, 700);
    }

    private void goHome() {
        if (isFinishing()) return;
        Intent home = new Intent(this, HomeActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        stopped = true;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Keep going: the download is needed for the app to be fast.
    }
}
