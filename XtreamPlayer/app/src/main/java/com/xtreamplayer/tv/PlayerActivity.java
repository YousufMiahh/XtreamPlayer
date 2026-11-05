package com.xtreamplayer.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.ui.PlayerView;

import java.util.List;

/**
 * Full-screen ExoPlayer.
 * Live TV: ▲/▼ (or CH+/CH-) zap, OK shows now/next, ☰ toggles favourite.
 * Movies/episodes: standard controls, ◀/▶ seek -10s/+30s, resume where you left off,
 * next episode plays automatically.
 */
@SuppressLint("UnsafeOptInUsageError")
public class PlayerActivity extends Activity {
    private Prefs prefs;
    private XtreamApi api;
    private List<Item> queue;
    private int index;

    private ExoPlayer player;
    private PlayerView view;
    private TextView overlay, errorText;

    private String liveFormat;
    private int retries;
    private boolean triedAltFormat;
    private boolean firstStart = true;

    private final Runnable hideOverlay = new Runnable() {
        public void run() { overlay.setVisibility(View.GONE); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = new Prefs(this);
        api = new XtreamApi(prefs);
        liveFormat = prefs.liveFormat();
        queue = Queue.items;
        index = getIntent().getIntExtra("index", 0);
        if (queue == null || queue.isEmpty() || index < 0 || index >= queue.size()) {
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new PlayerView(this);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        view.setControllerShowTimeoutMs(4000);
        view.setFocusable(true);
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = Ui.text(this, "", 20, Ui.TEXT);
        overlay.setBackground(Ui.rounded(this, 0xD0101318, 0, 14));
        int p = Ui.dp(this, 18);
        overlay.setPadding(p, p, p, p);
        overlay.setVisibility(View.GONE);
        FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        olp.leftMargin = olp.bottomMargin = Ui.dp(this, 40);
        olp.rightMargin = Ui.dp(this, 200);
        root.addView(overlay, olp);

        errorText = Ui.text(this, "", 20, Ui.TEXT);
        errorText.setGravity(Gravity.CENTER);
        errorText.setBackground(Ui.rounded(this, 0xE0101318, 0, 14));
        errorText.setPadding(p * 2, p * 2, p * 2, p * 2);
        errorText.setVisibility(View.GONE);
        root.addView(errorText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        setContentView(root);
        view.requestFocus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (view == null) return;
        initPlayer();
        play(true);
        firstStart = false;
    }

    @Override
    protected void onStop() {
        savePosition();
        releasePlayer();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (overlay != null) overlay.removeCallbacks(hideOverlay);
        super.onDestroy();
    }

    // ------------------------------------------------------------ player setup

    private void initPlayer() {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(prefs.userAgent())
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000);
        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                        | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS);
        DefaultMediaSourceFactory sources = new DefaultMediaSourceFactory(http, extractors);
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this).setEnableDecoderFallback(true);

        player = new ExoPlayer.Builder(this, renderers).setMediaSourceFactory(sources).build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    retries = 0;
                    errorText.setVisibility(View.GONE);
                } else if (state == Player.STATE_ENDED) {
                    onEnded();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                handleError(error);
            }
        });
        view.setPlayer(player);
    }

    private void releasePlayer() {
        if (player != null) {
            view.setPlayer(null);
            player.release();
            player = null;
        }
    }

    private Item current() { return queue.get(index); }

    private boolean isLive() { return Item.LIVE.equals(current().type); }

    private void play(boolean resume) {
        if (player == null) return;
        Item it = current();
        errorText.setVisibility(View.GONE);
        String url = api.playUrl(it, liveFormat);
        MediaItem.Builder mb = new MediaItem.Builder().setUri(url);
        if (url.endsWith(".m3u8")) mb.setMimeType(MimeTypes.APPLICATION_M3U8);
        view.setUseController(!isLive());
        player.setMediaItem(mb.build());
        if (!isLive() && resume) {
            long pos = prefs.position(it);
            if (pos > 10000) {
                player.seekTo(pos);
                if (firstStart) Toast.makeText(this, "Resuming from " + SeriesActivity.fmt(pos), Toast.LENGTH_SHORT).show();
            }
        }
        player.prepare();
        player.setPlayWhenReady(true);
        showInfo();
    }

    private void onEnded() {
        if (isLive()) { // server dropped the stream: reconnect
            play(false);
            return;
        }
        prefs.setPosition(current(), 0);
        if (Item.EPISODE.equals(current().type) && index < queue.size() - 1) {
            index++;
            retries = 0;
            play(false);
        } else {
            finish();
        }
    }

    private void handleError(PlaybackException e) {
        if (player == null) return;
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition();
            player.prepare();
            return;
        }
        if (retries < 2) {
            retries++;
            view.postDelayed(new Runnable() {
                public void run() {
                    if (player != null) player.prepare();
                }
            }, 1500);
            return;
        }
        if (isLive() && !triedAltFormat) { // try .m3u8 instead of .ts (or the other way round)
            triedAltFormat = true;
            retries = 0;
            liveFormat = "ts".equals(liveFormat) ? "m3u8" : "ts";
            play(false);
            return;
        }
        errorText.setText("Can't play this " + (isLive() ? "channel" : "video") + "\n" + e.getErrorCodeName()
                + "\n\nOK: try again   ·   Back: exit" + (isLive() ? "   ·   ▲▼: other channel" : ""));
        errorText.setVisibility(View.VISIBLE);
    }

    private void savePosition() {
        if (player == null || queue == null || isLive()) return;
        long d = player.getDuration(), p = player.getCurrentPosition();
        if (d > 0 && p > d - 60000) prefs.setPosition(current(), 0);
        else if (p > 10000) prefs.setPosition(current(), p);
    }

    // ------------------------------------------------------------ live helpers

    private void zap(int dir) {
        if (queue.size() < 2) return;
        index = (index + dir + queue.size()) % queue.size();
        retries = 0;
        triedAltFormat = false;
        liveFormat = prefs.liveFormat();
        play(false);
    }

    private void showInfo() {
        final Item it = current();
        final int at = index;
        final String head = (it.num != null && isLive() ? it.num + "   " : "") + it.name
                + (prefs.isFav(it) ? "   ★" : "");
        overlay.setText(head);
        overlay.setVisibility(View.VISIBLE);
        overlay.removeCallbacks(hideOverlay);
        overlay.postDelayed(hideOverlay, 6000);
        if (!isLive()) return;
        Async.run(new Async.Work<String[]>() {
            public String[] run() { return api.shortEpg(it.id); }
        }, new Async.Done<String[]>() {
            public void done(String[] r, Exception e) {
                if (isFinishing() || at != index || r == null || r[0] == null) return;
                overlay.setText(head + "\nNow:  " + r[0] + (r[1] != null ? "\nNext: " + r[1] : ""));
            }
        });
    }

    // ------------------------------------------------------------ remote control

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (player != null && e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER;
            if (errorText.getVisibility() == View.VISIBLE && ok) {
                retries = 0;
                triedAltFormat = false;
                liveFormat = prefs.liveFormat();
                play(false);
                return true;
            }
            if (isLive()) {
                switch (k) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_CHANNEL_UP:
                        zap(-1);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                    case KeyEvent.KEYCODE_CHANNEL_DOWN:
                        zap(1);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                    case KeyEvent.KEYCODE_INFO:
                        showInfo();
                        return true;
                    case KeyEvent.KEYCODE_MENU:
                        boolean fav = prefs.toggleFav(current());
                        Toast.makeText(this, fav ? "Added to favourites" : "Removed from favourites", Toast.LENGTH_SHORT).show();
                        showInfo();
                        return true;
                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    case KeyEvent.KEYCODE_MEDIA_PLAY:
                    case KeyEvent.KEYCODE_MEDIA_PAUSE:
                        player.setPlayWhenReady(!player.getPlayWhenReady());
                        return true;
                    default:
                        break;
                }
            } else if (!view.isControllerFullyVisible()) {
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
                    seekBy(-10000);
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    seekBy(30000);
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void seekBy(long delta) {
        long d = player.getDuration();
        long target = Math.max(0, player.getCurrentPosition() + delta);
        if (d > 0) target = Math.min(target, d - 1000);
        player.seekTo(target);
        view.showController();
    }

    @Override
    public void onBackPressed() {
        if (view != null && player != null && !isLive() && view.isControllerFullyVisible()) {
            view.hideController();
            return;
        }
        super.onBackPressed();
    }
}
