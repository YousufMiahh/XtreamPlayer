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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/** tvOS-style grouped settings list. */
public class SettingsActivity extends Activity {
    static final String[] SUB_SIZES = {"Small", "Medium", "Large", "Extra Large"};

    private Prefs prefs;
    private LinearLayout list;
    private TextView statusValue;

    interface Action { void run(TextView value); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        prefs = new Prefs(this);

        ScrollView scroll = new ScrollView(this);
        Ambient.install(this, Ambient.SETTINGS);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(TopBar.build(this, TopBar.SETTINGS));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        col.setClipChildren(false);
        scroll.setClipChildren(false);
        int side = Ui.dp(this, 16);
        list.setPadding(side, 0, side, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 900), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = Ui.dp(this, 60);
        col.addView(list, lp);
        scroll.addView(col);
        setContentView(scroll);
        build();
        loadStatus();
    }

    private void build() {
        list.removeAllViews();
        section("Accounts");
        final Prefs.Account cur = prefs.current();
        for (final Prefs.Account a : prefs.accounts()) {
            boolean isCur = cur != null && cur.id.equals(a.id);
            row(a.label(), isCur ? "✓  In use" : "Switch", isCur ? null : new Action() {
                public void run(TextView v) { switchTo(a.id); }
            });
        }
        statusValue = row("Subscription", "Checking…", null);
        row("Add Account", "", new Action() {
            public void run(TextView v) {
                startActivity(new Intent(SettingsActivity.this, LoginActivity.class).putExtra("add", true));
            }
        });
        row("Edit This Account", "", new Action() {
            public void run(TextView v) {
                startActivity(new Intent(SettingsActivity.this, LoginActivity.class).putExtra("switch", true));
            }
        });
        if (cur != null) {
            row("Remove This Account", "", new Action() {
                public void run(TextView v) { confirmRemove(cur); }
            });
        }

        section("Appearance");
        row("Theme", prefs.darkTheme() ? "Dark" : "Light", new Action() {
            public void run(TextView v) {
                prefs.setDarkTheme(!prefs.darkTheme());
                restartHome();
            }
        });
        row("Live TV Preview", PREVIEW[prefs.previewMode()], new Action() {
            public void run(TextView v) {
                prefs.setPreviewMode((prefs.previewMode() + 1) % PREVIEW.length);
                v.setText(PREVIEW[prefs.previewMode()]);
            }
        });

        section("Subtitles");
        row("Size", SUB_SIZES[prefs.subtitleSize()], new Action() {
            public void run(TextView v) {
                prefs.setSubtitleSize((prefs.subtitleSize() + 1) % SUB_SIZES.length);
                v.setText(SUB_SIZES[prefs.subtitleSize()]);
                updateSample();
            }
        });
        row("Colour", PlayerActivity.SUB_COLOR_NAMES[prefs.subtitleColor()], new Action() {
            public void run(TextView v) {
                prefs.setSubtitleColor((prefs.subtitleColor() + 1) % PlayerActivity.SUB_COLORS.length);
                v.setText(PlayerActivity.SUB_COLOR_NAMES[prefs.subtitleColor()]);
                updateSample();
            }
        });
        row("Background", prefs.subtitleBackground() ? "On" : "Off", new Action() {
            public void run(TextView v) {
                prefs.setSubtitleBackground(!prefs.subtitleBackground());
                v.setText(prefs.subtitleBackground() ? "On" : "Off");
                updateSample();
            }
        });
        row("Background Opacity", PlayerActivity.SUB_OPACITY_NAMES[prefs.subtitleOpacity()], new Action() {
            public void run(TextView v) {
                prefs.setSubtitleOpacity((prefs.subtitleOpacity() + 1) % PlayerActivity.SUB_OPACITY_ALPHA.length);
                v.setText(PlayerActivity.SUB_OPACITY_NAMES[prefs.subtitleOpacity()]);
                if (!prefs.subtitleBackground()) {
                    Toast.makeText(SettingsActivity.this, "Turn Background on to see this", Toast.LENGTH_SHORT).show();
                }
                updateSample();
            }
        });
        addSample();

        section("Playback");
        row("Live Stream Format", liveLabel(), new Action() {
            public void run(TextView v) {
                prefs.setLiveFormat("ts".equals(prefs.liveFormat()) ? "m3u8" : "ts");
                v.setText(liveLabel());
            }
        });
        row("Fix Frozen Video Automatically", prefs.autoRecover() ? "On" : "Off", new Action() {
            public void run(TextView v) {
                prefs.setAutoRecover(!prefs.autoRecover());
                v.setText(prefs.autoRecover() ? "On" : "Off");
            }
        });
        row("User Agent", prefs.userAgent(), new Action() {
            public void run(TextView v) { editUserAgent(v); }
        });

        section("Library");
        row("Refresh Automatically", refreshLabel(prefs.refreshHours()), new Action() {
            public void run(TextView v) {
                int i = 0;
                for (int k = 0; k < REFRESH_HOURS.length; k++) if (REFRESH_HOURS[k] == prefs.refreshHours()) i = k;
                prefs.setRefreshHours(REFRESH_HOURS[(i + 1) % REFRESH_HOURS.length]);
                v.setText(refreshLabel(prefs.refreshHours()));
            }
        });
        row("Refresh Now", lastUpdated(), new Action() {
            public void run(final TextView v) { refreshNow(v); }
        });
        row("Clear Watch History", "", new Action() {
            public void run(TextView v) {
                prefs.clearHistory();
                Toast.makeText(SettingsActivity.this, "Watch history cleared", Toast.LENGTH_SHORT).show();
            }
        });

        section("About");
        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        row("Version", "YM Player " + ver, null);
    }

    static final String[] PREVIEW = {"Off", "On (muted)", "On with sound"};
    static final int[] REFRESH_HOURS = {12, 24, 6, 0, -1};

    static String refreshLabel(int h) {
        if (h < 0) return "Only when I ask";
        if (h == 0) return "Every time the app opens";
        if (h == 24) return "Once a day";
        return "Every " + h + " hours";
    }

    private String lastUpdated() {
        long t = prefs.lastRefresh();
        if (t == 0) return "Not yet";
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                android.text.format.DateUtils.isToday(t) ? "'Updated today at' HH:mm" : "'Updated' d MMM, HH:mm", java.util.Locale.UK);
        return f.format(new java.util.Date(t));
    }

    private void refreshNow(final TextView v) {
        if (Repo.isRefreshing()) return;
        v.setText("Updating…");
        final XtreamApi api = new XtreamApi(prefs);
        Async.run(new Async.Work<Boolean>() {
            public Boolean run() throws Exception {
                Repo.refreshAll(api, prefs);
                return true;
            }
        }, new Async.Done<Boolean>() {
            public void done(Boolean ok, Exception e) {
                if (isFinishing()) return;
                v.setText(e != null ? "Failed — " + Async.message(e) : lastUpdated());
            }
        });
    }

    private void switchTo(String id) {
        prefs.setCurrent(id);
        Repo.clear();
        ImageLoader.setUserAgent(prefs.userAgent());
        restartHome();
    }

    private void restartHome() {
        Intent i = new Intent(this, prefs.hasAccount() ? HomeActivity.class : LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void confirmRemove(final Prefs.Account a) {
        new AlertDialog.Builder(this, Ui.DARK ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Remove " + a.label() + "?")
                .setMessage("Its favourites and watch history will be deleted from this device.")
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Repo.deleteFiles(a.server, a.user);
                        prefs.removeAccount(a.id);
                        Repo.clear();
                        restartHome();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private TextView sample;

    /** A mock video frame showing how subtitles will look. */
    private void addSample() {
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{0xFF3B5B8C, 0xFF6C4A7E, 0xFFB0705A}));
        frame.setClipToOutline(true);
        frame.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        ((android.graphics.drawable.GradientDrawable) frame.getBackground()).setCornerRadius(Ui.dp(this, 12));
        sample = Ui.text(this, "This is how your subtitles will look.", 22, 0xFFFFFFFF);
        sample.setTypeface(Ui.semibold());
        sample.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        android.widget.FrameLayout.LayoutParams sp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sp.bottomMargin = Ui.dp(this, 22);
        frame.addView(sample, sp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 170));
        lp.topMargin = Ui.dp(this, 6);
        lp.bottomMargin = Ui.dp(this, 6);
        list.addView(frame, lp);
        updateSample();
    }

    private void updateSample() {
        if (sample == null) return;
        float[] sizes = {19, 23, 28, 34};
        sample.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.sp(this, sizes[prefs.subtitleSize()]));
        sample.setTextColor(PlayerActivity.SUB_COLORS[prefs.subtitleColor()]);
        if (prefs.subtitleBackground()) {
            sample.setBackgroundColor(PlayerActivity.SUB_OPACITY_ALPHA[prefs.subtitleOpacity()] << 24);
            sample.setShadowLayer(0, 0, 0, 0);
        } else {
            sample.setBackgroundColor(0);
            sample.setShadowLayer(Ui.dp(this, 4), 0, Ui.dp(this, 1), 0xE6000000);
        }
    }

    private String liveLabel() {
        return "ts".equals(prefs.liveFormat()) ? "MPEG-TS" : "HLS";
    }

    private void section(String name) {
        TextView t = Ui.medium(this, name.toUpperCase(java.util.Locale.UK), 14, Ui.TEXT3);
        t.setLetterSpacing(0.1f);
        t.setPadding(Ui.dp(this, 22), Ui.dp(this, 34), 0, Ui.dp(this, 10));
        list.addView(t);
    }

    private TextView row(String label, String value, final Action action) {
        final LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 22);
        r.setPadding(p, Ui.dp(this, 18), p, Ui.dp(this, 18));
        final TextView l = Ui.medium(this, label, 19, Ui.TEXT);
        final TextView v = Ui.lines(Ui.text(this, value, 18, Ui.TEXT2), 2);
        v.setGravity(Gravity.END);
        r.addView(l, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        vlp.leftMargin = Ui.dp(this, 24);
        r.addView(v, vlp);
        if (action != null) {
            r.setFocusable(true);
            r.setBackground(Ui.pill(this, Ui.SURFACE, 12));
            r.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View view, boolean has) {
                    Ui.animateFocus(view, has, 1.02f);
                    l.setTextColor(has ? Ui.ON_FOCUS : Ui.TEXT);
                    v.setTextColor(has ? Ui.ON_FOCUS2 : Ui.TEXT2);
                }
            });
            r.setOnClickListener(new View.OnClickListener() {
                public void onClick(View view) { action.run(v); }
            });
        } else {
            r.setBackground(Ui.rounded(this, Ui.SURFACE_LO, 0, 12));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 6);
        list.addView(r, lp);
        if (action != null && list.findFocus() == null && getCurrentFocus() == null) r.requestFocus();
        return v;
    }

    private void loadStatus() {
        final XtreamApi api = new XtreamApi(prefs);
        Async.run(new Async.Work<JSONObject>() {
            public JSONObject run() throws Exception { return api.login(); }
        }, new Async.Done<JSONObject>() {
            public void done(JSONObject r, Exception e) {
                if (isFinishing()) return;
                statusValue.setText(e != null ? "Offline — " + Async.message(e) : XtreamApi.describeAccount(r).replace("Status: ", ""));
            }
        });
    }

    private void editUserAgent(final TextView value) {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setText(prefs.userAgent());
        new AlertDialog.Builder(this, Ui.DARK ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("User Agent")
                .setMessage("Some providers only allow certain apps. Try IPTVSmartersPlayer or VLC/3.0.20 if streams fail.")
                .setView(e)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        prefs.setUserAgent(e.getText().toString().trim());
                        ImageLoader.setUserAgent(prefs.userAgent());
                        value.setText(prefs.userAgent());
                    }
                })
                .setNeutralButton("Default", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        prefs.setUserAgent(Prefs.DEFAULT_UA);
                        ImageLoader.setUserAgent(Prefs.DEFAULT_UA);
                        value.setText(prefs.userAgent());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
