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

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class LoginActivity extends Activity {
    /** App names some providers insist on. Tried in order if the server refuses us. */
    private static final String[] USER_AGENTS = {
            null, // whatever is saved in Settings
            "IPTVSmartersPlayer",
            "okhttp/4.9.2",
            "VLC/3.0.20 LibVLC/3.0.20",
            "Mozilla/5.0 (Linux; Android 9; AFTMM) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
    };

    private EditText name, server, user, pass;
    private boolean adding;
    private TextView status;
    private Button login;
    private Prefs prefs;

    // "Add from your phone"
    private PhoneLink link;
    private android.widget.ImageView qrImage;
    private TextView qrUrl, phoneStatus;
    private PhoneLink.Reply phoneReply;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        adding = getIntent().getBooleanExtra("add", false);
        if (prefs.hasAccount() && !adding && !getIntent().getBooleanExtra("switch", false)) {
            startActivity(new Intent(this, HomeActivity.class));
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        Ui.init(this);
        Ambient.install(this, Ambient.HOME);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 44);
        col.setPadding(pad, pad, pad, pad);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setBackground(Ui.rounded(this, Ui.SURFACE_LO, 0, 28));
        // Manual form on the left, "use your phone" QR code on the right
        LinearLayout pair = new LinearLayout(this);
        pair.setOrientation(LinearLayout.HORIZONTAL);
        pair.setGravity(Gravity.CENTER_VERTICAL);
        pair.setClipChildren(false);
        root.addView(pair, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        pair.addView(col, new LinearLayout.LayoutParams(Ui.dp(this, 620), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(Ui.dp(this, 440), ViewGroup.LayoutParams.WRAP_CONTENT);
        qlp.leftMargin = Ui.dp(this, 36);
        pair.addView(buildPhonePanel(), qlp);

        col.addView(Ui.title(this, adding ? "Add Account" : "YM Player", 40));
        TextView sub = Ui.text(this, "Sign in with the details from your TV provider. "
                + "You can also paste a full get.php / M3U link into the server box.", 15, Ui.MUTED);
        sub.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 20));
        col.addView(sub);

        name = Ui.input(this, "Account name (optional, e.g. Home or Sports)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        server = Ui.input(this, "Server URL  (e.g. http://example.com:8080)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        user = Ui.input(this, "Username", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        pass = Ui.input(this, "Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        if (!adding && prefs.current() != null) {
            name.setText(prefs.current().name);
            server.setText(prefs.server());
            user.setText(prefs.user());
            pass.setText(prefs.pass());
        }
        addSpaced(col, name);
        addSpaced(col, server);
        addSpaced(col, user);
        addSpaced(col, pass);

        login = Ui.button(this, adding ? "Add Account" : "Log in");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 8);
        col.addView(login, blp);
        login.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { attempt(); }
        });

        status = Ui.text(this, "", 15, Ui.TEXT2);
        status.setPadding(0, Ui.dp(this, 14), 0, 0);
        col.addView(status);

        setContentView(root);
        (adding ? name : server).requestFocus();
    }

    private View buildPhonePanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(this, 40);
        p.setPadding(pad, pad, pad, pad);
        p.setBackground(Ui.rounded(this, Ui.SURFACE_LO, 0, 28));

        TextView t = Ui.title(this, "Use your phone", 28);
        t.setGravity(Gravity.CENTER);
        p.addView(t);
        TextView s = Ui.text(this, "Scan with your phone's camera and type your details there — much quicker than the remote. "
                + "Your phone needs to be on the same Wi-Fi as this TV.", 15, Ui.TEXT2);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 22));
        p.addView(s);

        android.widget.FrameLayout qrBox = new android.widget.FrameLayout(this);
        qrBox.setBackground(Ui.rounded(this, 0xFFFFFFFF, 0, 20));
        int qp = Ui.dp(this, 14);
        qrBox.setPadding(qp, qp, qp, qp);
        qrImage = new android.widget.ImageView(this);
        qrImage.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int qs = Ui.dp(this, 250);
        qrBox.addView(qrImage, new FrameLayout.LayoutParams(qs, qs));
        p.addView(qrBox);

        qrUrl = Ui.text(this, "", 14, Ui.TEXT3);
        qrUrl.setGravity(Gravity.CENTER);
        qrUrl.setPadding(0, Ui.dp(this, 16), 0, 0);
        p.addView(qrUrl);
        phoneStatus = Ui.medium(this, "", 16, Ui.TEXT);
        phoneStatus.setGravity(Gravity.CENTER);
        phoneStatus.setPadding(0, Ui.dp(this, 10), 0, 0);
        p.addView(phoneStatus);
        return p;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (qrImage == null) return;
        link = new PhoneLink(new PhoneLink.Handler() {
            public void onDetails(Map<String, String> f, PhoneLink.Reply reply) {
                if (phoneReply != null) { // already checking one
                    reply.done(false, "Your TV is still checking the last details — try again in a moment.");
                    return;
                }
                name.setText(nzs(f.get("name")));
                server.setText(nzs(f.get("server")));
                user.setText(nzs(f.get("user")));
                pass.setText(nzs(f.get("pass")));
                phoneReply = reply;
                phoneStatus.setText("Details received from your phone…");
                attempt();
            }
        });
        final PhoneLink starting = link;
        Async.run(new Async.Work<String>() {
            public String run() { return starting.start(); }
        }, new Async.Done<String>() {
            public void done(String url, Exception e) {
                if (link != starting) { // left the screen meanwhile
                    starting.stop();
                    return;
                }
                if (url == null) {
                    qrImage.setImageDrawable(null);
                    qrUrl.setText("Connect this TV to your home network to use this.");
                    return;
                }
                qrImage.setImageBitmap(PhoneLink.qr(url, 600));
                qrUrl.setText("Or open  " + url);
            }
        });
    }

    @Override
    protected void onPause() {
        if (link != null) link.stop();
        link = null;
        super.onPause();
    }

    private void replyPhone(boolean ok, String msg) {
        if (phoneReply == null) return;
        PhoneLink.Reply r = phoneReply;
        phoneReply = null;
        r.done(ok, msg);
        if (phoneStatus != null) phoneStatus.setText(ok ? "Signed in from your phone ✓" : "That didn't work — see the details on the left.");
    }

    private static String nzs(String s) { return s == null ? "" : s; }

    private void addSpaced(LinearLayout col, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 12);
        col.addView(v, lp);
    }

    /** Result of trying every server/user-agent combination. */
    private static class Outcome {
        JSONObject response;
        String base, ua;
        Exception error;
        String lastUrl;
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
        final String typed = XtreamApi.normalize(s);
        if (typed.isEmpty() || u.isEmpty() || p.isEmpty()) {
            status.setTextColor(0xFFFF7A7A);
            status.setText("Please fill in the server, username and password.");
            replyPhone(false, "Please fill in the server, username and password (or paste a full M3U link).");
            return;
        }
        final String fu = u, fp = p;
        final String savedUa = adding ? Prefs.DEFAULT_UA : prefs.userAgent();
        final String fname = name.getText().toString().trim();
        status.setTextColor(Ui.MUTED);
        status.setText("Connecting…");
        login.setEnabled(false);

        Async.run(new Async.Work<Outcome>() {
            public Outcome run() { return tryAll(typed, fu, fp, savedUa); }
        }, new Async.Done<Outcome>() {
            public void done(Outcome o, Exception unused) {
                if (isFinishing()) return;
                login.setEnabled(true);
                JSONObject ui = o.response == null ? null : o.response.optJSONObject("user_info");
                if (ui == null) {
                    status.setTextColor(0xFFFF7A7A);
                    status.setText("Couldn't log in: " + Async.message(o.error)
                            + "\nLast address tried: " + o.lastUrl
                            + "\nCheck the server address with your provider — it should be just http://host:port");
                    replyPhone(false, "Couldn't reach that server: " + Async.message(o.error) + ". Check the address with your provider.");
                    return;
                }
                if (ui.optInt("auth", 0) != 1) {
                    status.setTextColor(0xFFFF7A7A);
                    status.setText("The server rejected the login — check your username and password"
                            + (ui.has("status") ? " (account status: " + ui.optString("status") + ")" : "") + ".");
                    replyPhone(false, "The server rejected the username or password.");
                    return;
                }
                Prefs.Account acc = adding || prefs.current() == null ? prefs.newAccount() : prefs.current();
                acc.name = fname;
                acc.server = o.base;
                acc.user = fu;
                acc.pass = fp;
                if (o.ua != null) acc.ua = o.ua;
                else if (adding) acc.ua = "";
                acc.ok = true;
                prefs.saveAccount(acc);
                prefs.setCurrent(acc.id);
                replyPhone(true, "Added " + acc.label() + ". Your TV is loading it now.");
                Repo.clear();
                ImageLoader.setUserAgent(prefs.userAgent());
                Intent home = new Intent(LoginActivity.this, HomeActivity.class);
                home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(home);
                finish();
            }
        });
    }

    /** Tries the address as typed, then without any extra path, then http/https swapped, each with several app names. */
    private Outcome tryAll(String typed, String u, String p, String savedUa) {
        Outcome o = new Outcome();
        for (String base : candidateBases(typed)) {
            for (String ua : USER_AGENTS) {
                String useUa = ua == null ? savedUa : ua;
                o.lastUrl = base + "/player_api.php";
                try {
                    JSONObject r = loginOnce(base, u, p, useUa);
                    if (r.optJSONObject("user_info") != null) {
                        o.response = r;
                        o.base = base;
                        o.ua = ua; // null = keep the saved one
                        return o;
                    }
                    o.error = new IOException("Server answered, but not like an Xtream server");
                } catch (java.net.UnknownHostException | java.net.ConnectException | javax.net.ssl.SSLException e) {
                    o.error = e;
                    break; // can't reach this address at all; other app names won't help
                } catch (Exception e) {
                    o.error = e;
                }
            }
        }
        return o;
    }

    private static JSONObject loginOnce(String base, String u, String p, String ua) throws Exception {
        String url = base + "/player_api.php?username=" + Uri.encode(u) + "&password=" + Uri.encode(p);
        String body = Http.string(url, ua).trim();
        if (!body.startsWith("{")) throw new IOException("This doesn't look like an Xtream server");
        return new JSONObject(body);
    }

    private static List<String> candidateBases(String typed) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add(typed);
        String hostOnly = typed;
        try {
            Uri uri = Uri.parse(typed);
            if (uri.getScheme() != null && uri.getEncodedAuthority() != null) {
                hostOnly = uri.getScheme() + "://" + uri.getEncodedAuthority();
            }
        } catch (Exception ignored) {
        }
        set.add(hostOnly);
        set.add(swapScheme(typed));
        set.add(swapScheme(hostOnly));
        return new ArrayList<>(set);
    }

    private static String swapScheme(String s) {
        if (s.startsWith("https://")) return "http://" + s.substring(8);
        if (s.startsWith("http://")) return "https://" + s.substring(7);
        return s;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
