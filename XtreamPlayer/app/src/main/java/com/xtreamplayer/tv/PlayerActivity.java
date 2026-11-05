package com.xtreamplayer.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen player with its own tvOS-style overlay.
 * Movies/episodes: OK play/pause, ◀ back 5 s, ▶ forward 10 s, ▼ or ☰ subtitles & audio.
 * Live TV: ▲/▼ change channel, OK channel list, ☰ subtitles & audio.
 * A watchdog spots frozen video (audio still playing) and re-syncs the stream.
 */
@SuppressLint("UnsafeOptInUsageError")
public class PlayerActivity extends Activity {
    static final long BACK_MS = 5000, FORWARD_MS = 10000;
    static final float[] SUB_SIZES = {0.045f, 0.056f, 0.068f, 0.085f};

    private Prefs prefs;
    private XtreamApi api;
    private List<Item> queue;
    private int index;

    private ExoPlayer player;
    private PlayerView view;

    // VOD overlay
    private LinearLayout vodOverlay;
    private TextView vodTitle, vodSub, timeLeft, timeRight, seekBubble;
    private SeekBarView vodBar;
    private PlayPauseIcon centerIcon;

    // Live banner
    private LinearLayout liveBanner;
    private ImageView liveLogo;
    private TextView liveNum, liveName, liveNow, liveTime, liveNext;
    private SeekBarView liveBar;

    // Panels
    private LinearLayout channelPanel, trackList;
    private ListView channelList;
    private Adapters.Channels channelAdapter;
    private ScrollView trackPanel;
    private TextView errorText, statusPill;

    // State
    private String liveFormat;
    private int retries;
    private boolean triedAltFormat;
    private boolean firstStart = true;
    private long seekTarget = -1, burst;
    private int lastRendered = -1, stallTicks, recoveries;

    private final Runnable hideOverlay = new Runnable() { public void run() { fade(vodOverlay, false); } };
    private final Runnable hideBanner = new Runnable() { public void run() { fade(liveBanner, false); } };
    private final Runnable endBurst = new Runnable() {
        public void run() {
            burst = 0;
            seekTarget = -1;
            seekBubble.setVisibility(View.INVISIBLE);
        }
    };
    private final Runnable hideStatus = new Runnable() { public void run() { statusPill.setVisibility(View.GONE); } };
    private final Runnable zapPlay = new Runnable() { public void run() { play(false); } };
    private final Runnable ticker = new Runnable() {
        public void run() {
            updateProgress();
            Async.MAIN.postDelayed(this, 500);
        }
    };
    private final Runnable watchdog = new Runnable() {
        public void run() {
            checkStall();
            Async.MAIN.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
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
        view.setUseController(false);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        view.setFocusable(true);
        root.addView(view, match());

        buildVodOverlay(root);
        buildLiveBanner(root);
        buildChannelPanel(root);
        buildTrackPanel(root);

        errorText = Ui.text(this, "", 20, Ui.TEXT);
        errorText.setGravity(Gravity.CENTER);
        errorText.setBackground(Ui.rounded(this, Ui.SHEET, 0, 22));
        int p = Ui.dp(this, 36);
        errorText.setPadding(p, p, p, p);
        errorText.setVisibility(View.GONE);
        root.addView(errorText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        statusPill = Ui.medium(this, "", 16, Ui.TEXT);
        statusPill.setBackground(Ui.rounded(this, Ui.SHEET, 0, 20));
        statusPill.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 10));
        statusPill.setVisibility(View.GONE);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        sp.topMargin = sp.rightMargin = Ui.dp(this, 40);
        root.addView(statusPill, sp);

        setContentView(root);
        view.requestFocus();
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    // ------------------------------------------------------------ overlay construction

    private void buildVodOverlay(FrameLayout root) {
        vodOverlay = new LinearLayout(this);
        vodOverlay.setOrientation(LinearLayout.VERTICAL);
        vodOverlay.setGravity(Gravity.BOTTOM);
        vodOverlay.setBackground(Ui.scrimBottom());
        vodOverlay.setPadding(Ui.dp(this, 80), Ui.dp(this, 160), Ui.dp(this, 80), Ui.dp(this, 52));
        vodOverlay.setVisibility(View.GONE);

        vodSub = Ui.oneLine(Ui.medium(this, "", 18, Ui.W2));
        vodTitle = Ui.oneLine(Ui.title(this, "", 34));
        vodTitle.setTextColor(Ui.W1);
        vodTitle.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 22));
        vodOverlay.addView(vodSub);
        vodOverlay.addView(vodTitle);

        seekBubble = Ui.medium(this, "", 18, 0xFF0B0B0F);
        seekBubble.setTypeface(Ui.semibold());
        seekBubble.setBackground(Ui.rounded(this, Color.WHITE, 0, 16));
        seekBubble.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6));
        seekBubble.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        blp.bottomMargin = Ui.dp(this, 12);
        vodOverlay.addView(seekBubble, blp);

        vodBar = new SeekBarView(this);
        vodOverlay.addView(vodBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 7)));

        LinearLayout times = new LinearLayout(this);
        times.setPadding(0, Ui.dp(this, 12), 0, 0);
        timeLeft = Ui.medium(this, "", 17, Ui.W2);
        timeRight = Ui.medium(this, "", 17, Ui.W2);
        timeRight.setGravity(Gravity.END);
        times.addView(timeLeft, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        times.addView(timeRight, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        vodOverlay.addView(times);

        TextView hint = Ui.text(this, "◀  5 sec      ▶  10 sec      OK  Play / Pause      ▼  Subtitles & Audio", 14, Ui.W3);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(this, 18), 0, 0);
        vodOverlay.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(vodOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        centerIcon = new PlayPauseIcon(this);
        centerIcon.setBackground(Ui.rounded(this, 0x80000000, 0, 60));
        centerIcon.setVisibility(View.GONE);
        root.addView(centerIcon, new FrameLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 120), Gravity.CENTER));
    }

    private void buildLiveBanner(FrameLayout root) {
        liveBanner = new LinearLayout(this);
        liveBanner.setOrientation(LinearLayout.HORIZONTAL);
        liveBanner.setGravity(Gravity.BOTTOM);
        liveBanner.setBackground(Ui.scrimBottom());
        liveBanner.setPadding(Ui.dp(this, 80), Ui.dp(this, 150), Ui.dp(this, 80), Ui.dp(this, 52));
        liveBanner.setVisibility(View.GONE);

        FrameLayout logoBox = new FrameLayout(this);
        logoBox.setBackground(Ui.rounded(this, 0x26FFFFFF, 0, 18));
        liveLogo = new ImageView(this);
        liveLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int lp = Ui.dp(this, 18);
        liveLogo.setPadding(lp, lp, lp, lp);
        logoBox.addView(liveLogo, match());
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(Ui.dp(this, 220), Ui.dp(this, 124));
        llp.rightMargin = Ui.dp(this, 32);
        liveBanner.addView(logoBox, llp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setGravity(Gravity.BOTTOM);
        liveNum = Ui.medium(this, "", 22, Ui.W2);
        liveNum.setPadding(0, 0, Ui.dp(this, 14), Ui.dp(this, 2));
        liveName = Ui.oneLine(Ui.title(this, "", 32));
        liveName.setTextColor(Ui.W1);
        nameRow.addView(liveNum);
        nameRow.addView(liveName);
        col.addView(nameRow);
        liveNow = Ui.oneLine(Ui.medium(this, "", 21, Ui.W1));
        liveNow.setTypeface(Ui.semibold());
        liveNow.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 8));
        col.addView(liveNow);
        LinearLayout tr = new LinearLayout(this);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        liveTime = Ui.medium(this, "", 16, Ui.W2);
        tr.addView(liveTime);
        liveBar = new SeekBarView(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Ui.dp(this, 360), Ui.dp(this, 5));
        bp.leftMargin = Ui.dp(this, 16);
        tr.addView(liveBar, bp);
        col.addView(tr);
        liveNext = Ui.oneLine(Ui.text(this, "", 17, Ui.W2));
        liveNext.setPadding(0, Ui.dp(this, 10), 0, 0);
        col.addView(liveNext);
        TextView hint = Ui.text(this, "▲▼  Change channel      OK  Channel list      ☰  Subtitles & Audio", 14, Ui.W3);
        hint.setPadding(0, Ui.dp(this, 18), 0, 0);
        col.addView(hint);
        liveBanner.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(liveBanner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
    }

    private void buildChannelPanel(FrameLayout root) {
        channelPanel = new LinearLayout(this);
        channelPanel.setOrientation(LinearLayout.VERTICAL);
        channelPanel.setBackground(Ui.rounded(this, Ui.SHEET, 0, 26));
        int p = Ui.dp(this, 22);
        channelPanel.setPadding(p, p, p, p);
        channelPanel.setVisibility(View.GONE);
        TextView t = Ui.title(this, "Channels", 26);
        t.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), 0, Ui.dp(this, 14));
        channelPanel.addView(t);
        channelList = new ListView(this);
        channelList.setDivider(null);
        channelList.setDividerHeight(Ui.dp(this, 2));
        channelList.setVerticalScrollBarEnabled(false);
        Adapters.prepare(channelList);
        channelAdapter = new Adapters.Channels(this, prefs);
        channelAdapter.attach(channelList);
        channelPanel.addView(channelList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        channelList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> a, View v, int pos, long id) { Adapters.refresh(channelList); }
            public void onNothingSelected(AdapterView<?> a) {}
        });
        channelList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int pos, long id) {
                hidePanels();
                if (pos != index) {
                    index = pos;
                    resetLiveState();
                    play(false);
                }
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, 560), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        lp.setMargins(Ui.dp(this, 40), Ui.dp(this, 40), 0, Ui.dp(this, 40));
        root.addView(channelPanel, lp);
    }

    private void buildTrackPanel(FrameLayout root) {
        trackPanel = new ScrollView(this);
        trackPanel.setBackground(Ui.rounded(this, Ui.SHEET, 0, 26));
        trackPanel.setVerticalScrollBarEnabled(false);
        trackPanel.setVisibility(View.GONE);
        trackList = new LinearLayout(this);
        trackList.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 22);
        trackList.setPadding(p, p, p, p);
        trackPanel.addView(trackList);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, 520), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        lp.setMargins(0, Ui.dp(this, 40), Ui.dp(this, 40), Ui.dp(this, 40));
        root.addView(trackPanel, lp);
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onStart() {
        super.onStart();
        if (view == null) return;
        initPlayer();
        play(true);
        firstStart = false;
        Async.MAIN.post(ticker);
        Async.MAIN.postDelayed(watchdog, 1000);
    }

    @Override
    protected void onStop() {
        Async.MAIN.removeCallbacks(ticker);
        Async.MAIN.removeCallbacks(watchdog);
        Async.MAIN.removeCallbacks(zapPlay);
        savePosition();
        releasePlayer();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Runnable[] all = {hideOverlay, hideBanner, endBurst, hideStatus, zapPlay, ticker, watchdog};
        for (Runnable r : all) Async.MAIN.removeCallbacks(r);
        super.onDestroy();
    }

    private void initPlayer() {
        player = PlayerFactory.create(this, prefs);

        TrackSelectionParameters.Builder tb = player.getTrackSelectionParameters().buildUpon();
        String sl = prefs.subtitleLanguage();
        if ("off".equals(sl)) tb.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        else if (!sl.isEmpty()) tb.setPreferredTextLanguage(sl);
        if (!prefs.audioLanguage().isEmpty()) tb.setPreferredAudioLanguage(prefs.audioLanguage());
        player.setTrackSelectionParameters(tb.build());

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
            public void onIsPlayingChanged(boolean playing) {
                centerIcon.setPaused(!playing);
                if (!isLive()) {
                    if (!playing && player != null && player.getPlaybackState() == Player.STATE_READY) {
                        centerIcon.setVisibility(View.VISIBLE);
                        showOverlay(false);
                    } else {
                        centerIcon.setVisibility(View.GONE);
                        if (playing && vodOverlay.getVisibility() == View.VISIBLE) {
                            Async.MAIN.removeCallbacks(hideOverlay);
                            Async.MAIN.postDelayed(hideOverlay, 4000);
                        }
                    }
                }
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                if (trackPanel.getVisibility() == View.VISIBLE) buildTrackRows(false);
            }

            @Override
            public void onPlayerError(PlaybackException error) { handleError(error); }
        });
        view.setPlayer(player);
        styleSubtitles();
    }

    private void styleSubtitles() {
        SubtitleView sv = view.getSubtitleView();
        if (sv == null) return;
        sv.setApplyEmbeddedStyles(false);
        sv.setApplyEmbeddedFontSizes(false);
        sv.setStyle(new CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, 0xE6000000, Ui.semibold()));
        sv.setFractionalTextSize(SUB_SIZES[Math.max(0, Math.min(SUB_SIZES.length - 1, prefs.subtitleSize()))]);
        sv.setBottomPaddingFraction(0.07f);
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

    // ------------------------------------------------------------ playback

    private void play(boolean resume) {
        if (player == null) return;
        Item it = current();
        errorText.setVisibility(View.GONE);
        lastRendered = -1;
        stallTicks = 0;
        String url = api.playUrl(it, liveFormat);
        player.setMediaItem(PlayerFactory.media(url));
        if (!isLive() && resume) {
            long pos = prefs.position(it);
            if (pos > 10000) {
                player.seekTo(pos);
                if (firstStart) Toast.makeText(this, "Resuming from " + SeriesActivity.fmt(pos), Toast.LENGTH_SHORT).show();
            }
        }
        player.prepare();
        player.setPlayWhenReady(true);
        prefs.addHistory(it);
        if (isLive()) {
            fade(vodOverlay, false);
            showBanner();
        } else {
            fade(liveBanner, false);
            vodTitle.setText(it.name);
            vodSub.setText(Item.EPISODE.equals(it.type)
                    ? XtreamApi.joinNonEmpty(it.parentName, "Episode " + it.num) : "");
            vodSub.setVisibility(Item.EPISODE.equals(it.type) ? View.VISIBLE : View.GONE);
            showOverlay(true);
        }
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
            status("Reconnecting…");
            Async.MAIN.postDelayed(new Runnable() {
                public void run() { if (player != null) player.prepare(); }
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
                + "\n\nOK  Try again      Back  Exit" + (isLive() ? "      ▲▼  Other channel" : ""));
        errorText.setVisibility(View.VISIBLE);
    }

    private void savePosition() {
        if (player == null || queue == null || isLive()) return;
        long d = player.getDuration(), p = player.getCurrentPosition();
        if (d > 0) prefs.setDuration(current(), d);
        if (d > 0 && p > d - 60000) prefs.setPosition(current(), 0);
        else if (p > 10000) prefs.setPosition(current(), p);
    }

    private void resetLiveState() {
        retries = 0;
        triedAltFormat = false;
        recoveries = 0;
        liveFormat = prefs.liveFormat();
    }

    /** Frozen-picture detector: playing, but no new video frames for 4 s → re-sync. */
    private void checkStall() {
        if (player == null || !prefs.autoRecover() || !player.isPlaying()) {
            stallTicks = 0;
            return;
        }
        Format vf = player.getVideoFormat();
        DecoderCounters dc = player.getVideoDecoderCounters();
        if (vf == null || dc == null) return;
        if (vf.frameRate > 0 && vf.frameRate < 5) return; // radio stations with a still picture
        dc.ensureUpdated();
        int r = dc.renderedOutputBufferCount;
        if (r != lastRendered) {
            lastRendered = r;
            stallTicks = 0;
            return;
        }
        if (++stallTicks < 4) return;
        stallTicks = 0;
        if (recoveries >= 4) return; // give up quietly rather than loop forever
        recoveries++;
        status("Re-syncing video…");
        if (isLive()) {
            play(false);
        } else {
            player.seekTo(player.getCurrentPosition());
        }
    }

    // ------------------------------------------------------------ overlays

    private void fade(final View v, boolean show) {
        if (v == null) return;
        if (show) {
            if (v.getVisibility() != View.VISIBLE) {
                v.setAlpha(0f);
                v.setVisibility(View.VISIBLE);
            }
            v.animate().alpha(1f).setDuration(200).withEndAction(null).start();
        } else if (v.getVisibility() == View.VISIBLE) {
            v.animate().alpha(0f).setDuration(250).withEndAction(new Runnable() {
                public void run() { v.setVisibility(View.GONE); }
            }).start();
        }
    }

    private void showOverlay(boolean autoHide) {
        updateProgress();
        fade(vodOverlay, true);
        Async.MAIN.removeCallbacks(hideOverlay);
        if (autoHide && player != null && player.isPlaying()) Async.MAIN.postDelayed(hideOverlay, 4000);
    }

    private void updateProgress() {
        if (player == null || vodOverlay.getVisibility() != View.VISIBLE) return;
        long d = player.getDuration();
        long p = seekTarget >= 0 ? seekTarget : player.getCurrentPosition();
        if (d <= 0) {
            vodBar.set(0, 0);
            timeLeft.setText(SeriesActivity.fmt(p));
            timeRight.setText("");
            return;
        }
        vodBar.set(p / (float) d, player.getBufferedPosition() / (float) d);
        timeLeft.setText(SeriesActivity.fmt(p));
        timeRight.setText("−" + SeriesActivity.fmt(Math.max(0, d - p)));
    }

    private void showBanner() {
        final Item it = current();
        final int at = index;
        liveNum.setText(it.num == null ? "" : it.num);
        liveName.setText(it.name + (prefs.isFav(it) ? "  ★" : ""));
        liveNow.setText("");
        liveTime.setText("");
        liveNext.setText("");
        liveBar.setVisibility(View.INVISIBLE);
        ImageLoader.load(liveLogo, it.icon, 440, 248);
        fade(liveBanner, true);
        Async.MAIN.removeCallbacks(hideBanner);
        Async.MAIN.postDelayed(hideBanner, 6000);
        Async.run(new Async.Work<List<XtreamApi.Programme>>() {
            public List<XtreamApi.Programme> run() { return api.epg(it.id); }
        }, new Async.Done<List<XtreamApi.Programme>>() {
            public void done(List<XtreamApi.Programme> r, Exception e) {
                if (isFinishing() || at != index) return;
                if (r == null || r.isEmpty()) {
                    liveNow.setText("No guide information");
                    return;
                }
                XtreamApi.Programme now = r.get(0);
                liveNow.setText(now.title);
                liveTime.setText(now.timeRange());
                if (now.start > 0) {
                    liveBar.set(now.progress(), 0);
                    liveBar.setVisibility(View.VISIBLE);
                }
                if (r.size() > 1) {
                    XtreamApi.Programme n = r.get(1);
                    String t = n.timeRange();
                    liveNext.setText("Next   " + (t.length() >= 5 ? t.substring(0, 5) + "   " : "") + n.title);
                }
            }
        });
    }

    private void status(String s) {
        statusPill.setText(s);
        statusPill.setVisibility(View.VISIBLE);
        Async.MAIN.removeCallbacks(hideStatus);
        Async.MAIN.postDelayed(hideStatus, 2500);
    }

    private boolean panelOpen() {
        return channelPanel.getVisibility() == View.VISIBLE || trackPanel.getVisibility() == View.VISIBLE;
    }

    private void hidePanels() {
        fade(channelPanel, false);
        fade(trackPanel, false);
        view.requestFocus();
    }

    private void openChannels() {
        fade(liveBanner, false);
        channelAdapter.playingId = current().id;
        channelAdapter.set(queue);
        fade(channelPanel, true);
        channelList.requestFocus();
        channelList.setSelection(index);
    }

    // ------------------------------------------------------------ subtitles & audio

    private void openTracks() {
        fade(vodOverlay, false);
        fade(liveBanner, false);
        buildTrackRows(true);
        fade(trackPanel, true);
    }

    private void buildTrackRows(boolean focusSelected) {
        View focused = getCurrentFocus();
        int focusedIndex = focused != null && focused.getParent() == trackList ? trackList.indexOfChild(focused) : -1;
        trackList.removeAllViews();
        View firstSelected = null;
        if (player == null) return;

        TrackSelectionParameters params = player.getTrackSelectionParameters();
        boolean textDisabled = params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
        List<Tracks.Group> text = new ArrayList<>(), audio = new ArrayList<>();
        boolean anyTextSelected = false;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() == C.TRACK_TYPE_TEXT) {
                text.add(g);
                if (g.isSelected()) anyTextSelected = true;
            } else if (g.getType() == C.TRACK_TYPE_AUDIO) {
                audio.add(g);
            }
        }

        header("Subtitles");
        View off = option("Off", textDisabled || !anyTextSelected, new Runnable() {
            public void run() {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
                prefs.setSubtitleLanguage("off");
            }
        });
        if (textDisabled || !anyTextSelected) firstSelected = off;
        int n = 0;
        for (final Tracks.Group g : text) {
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                final int ti = i;
                boolean sel = !textDisabled && g.isTrackSelected(i);
                View o = option(trackName(g.getTrackFormat(i), C.TRACK_TYPE_TEXT, ++n), sel, new Runnable() {
                    public void run() { select(g, ti, C.TRACK_TYPE_TEXT); }
                });
                if (sel) firstSelected = o;
            }
        }
        if (n == 0) note("This stream has no subtitles");

        header("Audio");
        int a = 0;
        for (final Tracks.Group g : audio) {
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                final int ti = i;
                boolean sel = g.isTrackSelected(i);
                View o = option(trackName(g.getTrackFormat(i), C.TRACK_TYPE_AUDIO, ++a), sel, new Runnable() {
                    public void run() { select(g, ti, C.TRACK_TYPE_AUDIO); }
                });
                if (sel && firstSelected == null) firstSelected = o;
            }
        }
        if (a == 0) note("No audio tracks found");

        header("Subtitle Size");
        for (int s = 0; s < SettingsActivity.SUB_SIZES.length; s++) {
            final int size = s;
            option(SettingsActivity.SUB_SIZES[s], prefs.subtitleSize() == s, new Runnable() {
                public void run() {
                    prefs.setSubtitleSize(size);
                    styleSubtitles();
                }
            });
        }

        if (focusedIndex >= 0 && focusedIndex < trackList.getChildCount() && trackList.getChildAt(focusedIndex).isFocusable()) {
            trackList.getChildAt(focusedIndex).requestFocus();
        } else if (focusSelected && firstSelected != null) {
            firstSelected.requestFocus();
        }
    }

    private void select(Tracks.Group g, int i, int type) {
        String lang = g.getTrackFormat(i).language;
        TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(type, false)
                .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), i));
        if (type == C.TRACK_TYPE_TEXT) {
            b.setPreferredTextLanguage(lang);
            prefs.setSubtitleLanguage(lang == null ? "" : lang);
        } else {
            b.setPreferredAudioLanguage(lang);
            prefs.setAudioLanguage(lang == null ? "" : lang);
        }
        player.setTrackSelectionParameters(b.build());
    }

    private void header(String s) {
        TextView t = Ui.medium(this, s.toUpperCase(Locale.UK), 13, Ui.TEXT3);
        t.setLetterSpacing(0.1f);
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, trackList.getChildCount() == 0 ? 4 : 24), 0, Ui.dp(this, 8));
        trackList.addView(t);
    }

    private void note(String s) {
        TextView t = Ui.text(this, s, 16, Ui.TEXT2);
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), 0, Ui.dp(this, 8));
        trackList.addView(t);
    }

    private View option(String label, boolean selected, final Runnable action) {
        final LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        int p = Ui.dp(this, 14);
        r.setPadding(p + Ui.dp(this, 4), p, p + Ui.dp(this, 4), p);
        r.setBackground(Ui.pill(this, 0, 12));
        final TextView l = Ui.oneLine(Ui.medium(this, label, 19, Ui.TEXT));
        final TextView c = Ui.medium(this, selected ? "✓" : "", 19, Ui.TEXT);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(c);
        r.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                l.setTextColor(has ? Ui.ON_FOCUS : Ui.TEXT);
                c.setTextColor(has ? Ui.ON_FOCUS : Ui.TEXT);
            }
        });
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                action.run();
                Async.MAIN.postDelayed(new Runnable() {
                    public void run() { if (trackPanel.getVisibility() == View.VISIBLE) buildTrackRows(false); }
                }, 250);
            }
        });
        trackList.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    static String trackName(Format f, int type, int n) {
        String name;
        if (f.label != null && !f.label.trim().isEmpty()) {
            name = f.label.trim();
        } else if (f.language != null && !f.language.isEmpty() && !"und".equals(f.language)) {
            String d = new Locale(f.language).getDisplayLanguage(Locale.UK);
            name = d == null || d.isEmpty() ? f.language : d;
        } else {
            name = (type == C.TRACK_TYPE_AUDIO ? "Audio " : "Subtitles ") + n;
        }
        List<String> extra = new ArrayList<>();
        if (type == C.TRACK_TYPE_AUDIO) {
            String m = f.sampleMimeType == null ? "" : f.sampleMimeType;
            if (m.contains("eac3")) extra.add("Dolby Digital+");
            else if (m.contains("ac3")) extra.add("Dolby Digital");
            else if (m.contains("dts")) extra.add("DTS");
            else if (m.contains("mp4a")) extra.add("AAC");
            if (f.channelCount == 8) extra.add("7.1");
            else if (f.channelCount == 6) extra.add("5.1");
            else if (f.channelCount == 2) extra.add("Stereo");
            else if (f.channelCount == 1) extra.add("Mono");
        } else if ((f.selectionFlags & C.SELECTION_FLAG_FORCED) != 0) {
            extra.add("Forced");
        }
        StringBuilder b = new StringBuilder(name);
        for (String x : extra) b.append("  ·  ").append(x);
        return b.toString();
    }

    // ------------------------------------------------------------ remote control

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (player == null || e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e);

        if (panelOpen()) {
            if (k == KeyEvent.KEYCODE_MENU && channelPanel.getVisibility() == View.VISIBLE) {
                int pos = channelList.getSelectedItemPosition();
                if (pos >= 0 && pos < queue.size()) {
                    Open.toggleFav(this, prefs, queue.get(pos));
                    channelAdapter.notifyDataSetChanged();
                }
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER;
        if (errorText.getVisibility() == View.VISIBLE && ok) {
            resetLiveState();
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
                    openChannels();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_INFO:
                    showBanner();
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    openTracks();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    player.setPlayWhenReady(!player.getPlayWhenReady());
                    return true;
                default:
                    return super.dispatchKeyEvent(e);
            }
        }

        switch (k) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (e.getRepeatCount() > 0) return true;
                togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                player.play();
                showOverlay(true);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                player.pause();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-BACK_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(FORWARD_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                showOverlay(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MENU:
                openTracks();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void togglePause() {
        if (player.isPlaying()) {
            player.pause();
        } else {
            player.play();
            showOverlay(true);
        }
    }

    private void seekBy(long delta) {
        long d = player.getDuration();
        long base = seekTarget >= 0 ? seekTarget : player.getCurrentPosition();
        long target = Math.max(0, base + delta);
        if (d > 0) target = Math.min(target, d - 1000);
        seekTarget = target;
        burst += delta;
        player.seekTo(target);
        long s = Math.abs(burst) / 1000;
        seekBubble.setText((burst < 0 ? "−" : "+") + (s >= 60 ? SeriesActivity.fmt(Math.abs(burst)) : s + " sec"));
        seekBubble.setVisibility(View.VISIBLE);
        Async.MAIN.removeCallbacks(endBurst);
        Async.MAIN.postDelayed(endBurst, 1200);
        showOverlay(true);
    }

    private void zap(int dir) {
        if (queue.size() < 2) return;
        index = (index + dir + queue.size()) % queue.size();
        resetLiveState();
        showBanner();
        Async.MAIN.removeCallbacks(zapPlay);
        Async.MAIN.postDelayed(zapPlay, 350); // lets you skip through several channels quickly
    }

    @Override
    public void onBackPressed() {
        if (view != null && panelOpen()) {
            hidePanels();
            return;
        }
        if (view != null && player != null && !isLive() && vodOverlay.getVisibility() == View.VISIBLE && player.isPlaying()) {
            fade(vodOverlay, false);
            return;
        }
        super.onBackPressed();
    }

    /** Simple drawn play / pause glyph (no font dependency). */
    static class PlayPauseIcon extends View {
        private boolean paused;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        PlayPauseIcon(Context c) {
            super(c);
            p.setColor(Color.WHITE);
        }

        void setPaused(boolean b) {
            paused = b;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h) * 0.32f;
            float cx = w / 2f, cy = h / 2f;
            if (paused) { // show "play" while paused
                path.reset();
                path.moveTo(cx - s * 0.6f, cy - s);
                path.lineTo(cx + s, cy);
                path.lineTo(cx - s * 0.6f, cy + s);
                path.close();
                c.drawPath(path, p);
            } else {
                float bw = s * 0.45f, gap = s * 0.35f;
                c.drawRoundRect(cx - gap - bw, cy - s, cx - gap, cy + s, bw / 3, bw / 3, p);
                c.drawRoundRect(cx + gap, cy - s, cx + gap + bw, cy + s, bw / 3, bw / 3, p);
            }
        }
    }
}
