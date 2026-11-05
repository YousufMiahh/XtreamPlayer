package com.xtreamplayer.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

public class HomeActivity extends Activity {
    private Prefs prefs;
    private TextView account;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        ImageLoader.setUserAgent(prefs.userAgent());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 40);
        root.setPadding(pad, pad, pad, pad);

        root.addView(Ui.title(this, "Xtream Player", 40));
        account = Ui.text(this, prefs.user() + " @ " + prefs.server(), 16, Ui.MUTED);
        account.setGravity(Gravity.CENTER);
        account.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 36));
        root.addView(account);

        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.setGravity(Gravity.CENTER);
        Button live = tile("Live TV", Item.LIVE);
        tiles.addView(live);
        tiles.addView(tile("Movies", Item.MOVIE));
        tiles.addView(tile("Series", Item.SERIES));
        root.addView(tiles);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, Ui.dp(this, 36), 0, 0);
        Button settings = Ui.button(this, "Settings");
        settings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showSettings(); }
        });
        Button sw = Ui.button(this, "Switch account");
        sw.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(HomeActivity.this, LoginActivity.class);
                i.putExtra("switch", true);
                startActivity(i);
                finish();
            }
        });
        row.addView(settings, margins());
        row.addView(sw, margins());
        root.addView(row);

        setContentView(root);
        live.requestFocus();
        refreshAccount();
    }

    private LinearLayout.LayoutParams margins() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = lp.rightMargin = Ui.dp(this, 10);
        return lp;
    }

    private Button tile(String label, final String type) {
        Button t = Ui.button(this, label);
        t.setTextSize(26);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 260), Ui.dp(this, 160));
        lp.leftMargin = lp.rightMargin = Ui.dp(this, 14);
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(HomeActivity.this, BrowseActivity.class);
                i.putExtra("type", type);
                startActivity(i);
            }
        });
        return t;
    }

    private void refreshAccount() {
        final XtreamApi api = new XtreamApi(prefs);
        Async.run(new Async.Work<JSONObject>() {
            public JSONObject run() throws Exception { return api.login(); }
        }, new Async.Done<JSONObject>() {
            public void done(JSONObject r, Exception e) {
                if (isFinishing()) return;
                String base = prefs.user() + " @ " + prefs.server();
                if (e != null) account.setText(base + "\nOffline: " + Async.message(e));
                else account.setText(base + "\n" + XtreamApi.describeAccount(r));
            }
        });
    }

    private void showSettings() {
        final String fmt = "ts".equals(prefs.liveFormat()) ? "MPEG-TS (.ts)" : "HLS (.m3u8)";
        String[] items = {
                "Live stream format: " + fmt,
                "User agent: " + prefs.userAgent(),
                "Reset user agent to default"
        };
        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            prefs.setLiveFormat("ts".equals(prefs.liveFormat()) ? "m3u8" : "ts");
                            showSettings();
                        } else if (which == 1) {
                            editUserAgent();
                        } else {
                            prefs.setUserAgent(Prefs.DEFAULT_UA);
                            ImageLoader.setUserAgent(Prefs.DEFAULT_UA);
                            showSettings();
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void editUserAgent() {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setText(prefs.userAgent());
        new AlertDialog.Builder(this)
                .setTitle("User agent")
                .setMessage("Some providers only allow certain apps. If streams fail, try e.g. IPTVSmartersPlayer or VLC/3.0.20")
                .setView(e)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        prefs.setUserAgent(e.getText().toString().trim());
                        ImageLoader.setUserAgent(prefs.userAgent());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
