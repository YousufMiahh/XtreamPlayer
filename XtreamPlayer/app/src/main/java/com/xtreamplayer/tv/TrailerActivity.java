package com.xtreamplayer.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * Plays a YouTube trailer. Uses the YouTube app if it's installed, otherwise an embedded player.
 */
public class TrailerActivity extends Activity {
    private static final String[] YOUTUBE_APPS = {
            "com.amazon.firetv.youtube", "com.google.android.youtube.tv", "com.google.android.youtube"};

    private WebView web;

    /** Pulls an 11-character YouTube id out of whatever the provider gave us. */
    public static String youtubeId(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.matches("[A-Za-z0-9_-]{11}")) return s;
        try {
            Uri u = Uri.parse(s.startsWith("http") ? s : "https://" + s);
            String v = u.getQueryParameter("v");
            if (v != null && v.matches("[A-Za-z0-9_-]{11}")) return v;
            String last = u.getLastPathSegment();
            if (last != null && last.matches("[A-Za-z0-9_-]{11}")) return last;
        } catch (Exception ignored) {
        }
        return null;
    }

    public static void open(Activity a, String raw) {
        String id = youtubeId(raw);
        if (id == null) {
            Toast.makeText(a, "No trailer available", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri url = Uri.parse("https://www.youtube.com/watch?v=" + id);
        for (String pkg : YOUTUBE_APPS) {
            Intent i = new Intent(Intent.ACTION_VIEW, url).setPackage(pkg);
            if (i.resolveActivity(a.getPackageManager()) != null) {
                try {
                    a.startActivity(i);
                    return;
                } catch (Exception ignored) {
                }
            }
        }
        a.startActivity(new Intent(a, TrailerActivity.class).putExtra("id", id));
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String id = getIntent().getStringExtra("id");
        if (id == null) {
            finish();
            return;
        }
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{border:0;width:100%;height:100%}</style>"
                + "</head><body><iframe src='https://www.youtube-nocookie.com/embed/" + id
                + "?autoplay=1&rel=0&modestbranding=1&playsinline=1' allow='autoplay; encrypted-media; fullscreen' allowfullscreen></iframe>"
                + "</body></html>";
        web.loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "UTF-8", null);
        setContentView(web);
        web.requestFocus();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
        }
        super.onDestroy();
    }
}
