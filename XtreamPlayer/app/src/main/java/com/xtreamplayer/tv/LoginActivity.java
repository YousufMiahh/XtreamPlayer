package com.xtreamplayer.tv;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

public class LoginActivity extends Activity {
    private EditText server, user, pass;
    private TextView status;
    private Button login;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        if (prefs.hasAccount() && !getIntent().getBooleanExtra("switch", false)) {
            startActivity(new Intent(this, HomeActivity.class));
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 32);
        col.setPadding(pad, pad, pad, pad);
        col.setBackground(Ui.rounded(this, Ui.PANEL, 0, 18));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, 620), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(col, lp);

        col.addView(Ui.title(this, "Xtream Player", 34));
        TextView sub = Ui.text(this, "Enter the Xtream Codes details from your provider. "
                + "You can also paste a full get.php / M3U link into the server box.", 15, Ui.MUTED);
        sub.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 20));
        col.addView(sub);

        server = Ui.input(this, "Server URL  (e.g. http://example.com:8080)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        user = Ui.input(this, "Username", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        pass = Ui.input(this, "Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        server.setText(prefs.server());
        user.setText(prefs.user());
        pass.setText(prefs.pass());
        addSpaced(col, server);
        addSpaced(col, user);
        addSpaced(col, pass);

        login = Ui.button(this, "Log in");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 8);
        col.addView(login, blp);
        login.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { attempt(); }
        });

        status = Ui.text(this, "", 15, Ui.MUTED);
        status.setPadding(0, Ui.dp(this, 14), 0, 0);
        col.addView(status);

        setContentView(root);
        server.requestFocus();
    }

    private void addSpaced(LinearLayout col, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 12);
        col.addView(v, lp);
    }

    private void attempt() {
        String s = server.getText().toString().trim();
        String u = user.getText().toString().trim();
        String p = pass.getText().toString().trim();

        // Accept a full M3U / get.php link and pull the login out of it.
        if (s.contains("username=") && s.contains("password=")) {
            try {
                Uri uri = Uri.parse(s.startsWith("http") ? s : "http://" + s);
                if (u.isEmpty()) u = nz(uri.getQueryParameter("username"));
                if (p.isEmpty()) p = nz(uri.getQueryParameter("password"));
                user.setText(u);
                pass.setText(p);
            } catch (Exception ignored) {
            }
        }
        s = XtreamApi.normalize(s);
        if (s.isEmpty() || u.isEmpty() || p.isEmpty()) {
            status.setTextColor(0xFFFF7A7A);
            status.setText("Please fill in the server, username and password.");
            return;
        }
        server.setText(s);
        final String fs = s, fu = u, fp = p;
        prefs.setAccount(fs, fu, fp);
        prefs.markLoggedIn(false);
        status.setTextColor(Ui.MUTED);
        status.setText("Connecting…");
        login.setEnabled(false);
        final XtreamApi api = new XtreamApi(prefs);
        Async.run(new Async.Work<JSONObject>() {
            public JSONObject run() throws Exception { return api.login(); }
        }, new Async.Done<JSONObject>() {
            public void done(JSONObject r, Exception e) {
                if (isFinishing()) return;
                login.setEnabled(true);
                JSONObject ui = r == null ? null : r.optJSONObject("user_info");
                if (e != null || ui == null || ui.optInt("auth", 0) != 1) {
                    prefs.markLoggedIn(false);
                    status.setTextColor(0xFFFF7A7A);
                    status.setText(e != null ? "Couldn't log in: " + Async.message(e)
                            : "Login rejected — check your username and password.");
                    return;
                }
                prefs.markLoggedIn(true);
                startActivity(new Intent(LoginActivity.this, HomeActivity.class));
                finish();
            }
        });
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
