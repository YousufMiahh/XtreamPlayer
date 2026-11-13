package com.xtreamplayer.tv;

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
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * Full-screen player with its own tvOS-style overlay, running on either engine:
 * Android's ExoPlayer (default) or VLC. In Automatic mode a live channel whose picture
 * freezes is switched to VLC on the spot and remembered for next time.
 * Movies/episodes: OK play/pause, ◀ back 5 s, ▶ forward 10 s, ▼ or ☰ subtitles & audio.
 * Live TV: ▲/▼ change channel, OK channel list, ☰ or hold OK favourite, subtitles & audio.
 */
public class PlayerActivity extends Activity implements Engine.Listener {
    static final long BACK_MS = 5000, FORWARD_MS = 10000;
    static final String VOD_HINT = "◀  5 sec      ▶  10 sec      OK  Play / Pause      ▼  Scrub      "
            + (Ui.hasMenuKey() ? "☰" : "Hold OK") + "  Subtitles & Audio";
    static final String SCRUB_HINT = "◀ ▶  Move (hold to go faster)      OK  Play from here      Back  Cancel";
    static final float[] SUB_SIZES = {0.045f, 0.056f, 0.068f, 0.085f};
    static final String[] SUB_COLOR_NAMES = {"White", "Yellow", "Cyan", "Green", "Pink", "Orange"};
    static final int[] SUB_COLORS = {0xFFFFFFFF, 0xFFFFE14D, 0xFF5AE0FF, 0xFF6BEA7A, 0xFFFF8AD8, 0xFFFFA94D};
    static final String[] SUB_OPACITY_NAMES = {"25%", "50%", "75%", "100%"};
    static final int[] SUB_OPACITY_ALPHA = {0x40, 0x80, 0xBF, 0xFF};

    private Prefs prefs;
    private XtreamApi api;
    private List<Item> queue;
    private int index;

    private Engine engine;
    private boolean usingVlc;
    private FrameLayout videoHost;
    private ProgressBar spinner;

    // VOD overlay
    private LinearLayout vodOverlay;
    private TextView vodTitle, vodSub, timeLeft, timeRight, seekBubble;
    private SeekBarView vodBar;
    private PlayPauseIcon centerIcon;

    // Scrubbing (▼): a knob you move along the bar, with the time above it
    private FrameLayout scrubRow;
    private LinearLayout scrubCard;
    private TextView scrubTime, vodHint;
    private boolean scrubbing;
    private long scrubPos;
    private final Runnable scrubCommit = new Runnable() { public void run() { commitScrub(); } };

    // Next episode card
    private NextUpCard nextCard;
    private boolean nextShown, nextDismissed;

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
    private boolean triedAltFormat, triedOtherEngine;
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
            checkNextCard();
            checkIntro();
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
        videoHost = new FrameLayout(this);
        videoHost.setFocusable(true);
        root.addView(videoHost, match());

        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.W2));
        spinner.setVisibility(View.GONE);
        root.addView(spinner, new FrameLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64), Gravity.CENTER));

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
        videoHost.requestFocus();
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

        seekBubble = Ui.medium(this, "", 18, Ui.W1);
        seekBubble.setTypeface(Ui.semibold());
        seekBubble.setBackground(Ui.rounded(this, 0x33FFFFFF, 0, 16));
        seekBubble.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6));
        seekBubble.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        blp.bottomMargin = Ui.dp(this, 12);
        vodOverlay.addView(seekBubble, blp);

        // Scrub time, slid along above the knob
        scrubRow = new FrameLayout(this);
        scrubRow.setClipChildren(false);
        scrubRow.setVisibility(View.GONE);
        scrubCard = new LinearLayout(this);
        scrubCard.setOrientation(LinearLayout.VERTICAL);
        scrubCard.setGravity(Gravity.CENTER_HORIZONTAL);
        scrubTime = Ui.medium(this, "", 22, Ui.W1);
        scrubTime.setTypeface(Ui.semibold());
        scrubTime.setShadowLayer(Ui.dp(this, 6), 0, 0, 0xCC000000);
        scrubTime.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 8));
        scrubCard.addView(scrubTime);
        scrubRow.addView(scrubCard, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        vodOverlay.addView(scrubRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        vodBar = new SeekBarView(this);
        vodBar.setBarHeight(Ui.dp(this, 7));
        LinearLayout.LayoutParams vbl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 28));
        vbl.leftMargin = vbl.rightMargin = -Ui.dp(this, 14); // the knob's room at each end sits outside the margins
        vodOverlay.addView(vodBar, vbl);

        LinearLayout times = new LinearLayout(this);
        times.setPadding(0, Ui.dp(this, 12), 0, 0);
        timeLeft = Ui.medium(this, "", 17, Ui.W2);
        timeRight = Ui.medium(this, "", 17, Ui.W2);
        timeRight.setGravity(Gravity.END);
        times.addView(timeLeft, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        times.addView(timeRight, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        vodOverlay.addView(times);

        vodHint = Ui.text(this, VOD_HINT, 14, Ui.W3);
        vodHint.setGravity(Gravity.CENTER);
        vodHint.setPadding(0, Ui.dp(this, 18), 0, 0);
        vodOverlay.addView(vodHint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(vodOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        skipBtn = Ui.medium(this, "OK   Skip Intro", 20, 0xFF000000);
        skipBtn.setTypeface(Ui.semibold());
        skipBtn.setBackground(Ui.rounded(this, 0xF2FFFFFF, 0, 8));
        skipBtn.setPadding(Ui.dp(this, 28), Ui.dp(this, 14), Ui.dp(this, 28), Ui.dp(this, 14));
        skipBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams skl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        skl.rightMargin = Ui.dp(this, 72);
        skl.bottomMargin = Ui.dp(this, 64);
        root.addView(skipBtn, skl);

        nextCard = new NextUpCard(this);
        nextCard.setVisibility(View.GONE);
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        nl.rightMargin = Ui.dp(this, 72);
        nl.bottomMargin = Ui.dp(this, 64);
        root.addView(nextCard, nl);
        nextCard.credits.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { watchCredits(); } });
        nextCard.next.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { playNext(); } });

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
        TextView hint = Ui.text(this, "▲▼  Change channel      OK  Channel list      "
                + (Ui.hasMenuKey() ? "☰" : "Hold OK") + "  Favourite, subtitles & audio", 14, Ui.W3);
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
        t.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), 0, Ui.dp(this, 2));
        channelPanel.addView(t);
        TextView th = Ui.text(this, (Ui.hasMenuKey() ? "☰ or hold OK" : "Hold OK") + "  Add or remove favourite", 14, Ui.TEXT2);
        th.setPadding(Ui.dp(this, 10), 0, 0, Ui.dp(this, 14));
        channelPanel.addView(th);
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
        channelList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> a, View v, int pos, long id) {
                if (pos < 0 || pos >= queue.size()) return false;
                Open.toggleFav(PlayerActivity.this, prefs, queue.get(pos));
                channelAdapter.notifyDataSetChanged();
                return true;
            }
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
        if (videoHost == null) return;
        startEngine(wantsVlc(current()));
        play(true);
        firstStart = false;
        String menu = Ui.hasMenuKey() ? "☰ (or hold OK)" : "Hold OK";
        if (isLive()) Coach.show(this, "player_live", true, new Coach.Step(null, "Watching Live TV",
                "▲ ▼  Change channel\nOK  Channel list\n◀ ▶  Channel info\n" + menu + "  Favourite, subtitles & audio\nBack  Leave"));
        else Coach.show(this, "player_vod", true, new Coach.Step(null, "Watching films & series",
                "OK  Pause / play\n◀  Back 5 seconds     ▶  Forward 10 seconds (press again to go further)\n▼  Scrub: move the knob with ◀ ▶, OK to jump there\n" + menu + "  Subtitles, audio & favourite\nBack  Leave — your place is saved"));
        Async.MAIN.post(ticker);
        Async.MAIN.postDelayed(watchdog, 1000);
    }

    @Override
    protected void onStop() {
        Async.MAIN.removeCallbacks(ticker);
        Async.MAIN.removeCallbacks(watchdog);
        Async.MAIN.removeCallbacks(zapPlay);
        if (scrubbing) cancelScrub();
        hideNextCard();
        savePosition();
        releaseEngine();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Runnable[] all = {hideOverlay, hideBanner, endBurst, hideStatus, zapPlay, ticker, watchdog, scrubCommit};
        for (Runnable r : all) Async.MAIN.removeCallbacks(r);
        super.onDestroy();
    }

    // ------------------------------------------------------------ engines

    /** Settings → Video Player: 0 Automatic (VLC for Live TV, Android for Movies & Series), 1 Android, 2 VLC. */
    private boolean wantsVlc(Item it) {
        // Live TV always uses VLC (the Android player doesn't cope with these live streams),
        // unless "Android player for everything" was picked in Settings.
        if (Item.LIVE.equals(it.type)) return prefs.engineMode() != 1 || (prefs.audioDelay(it) != 0 && !prefs.vlcBroken());
        if (prefs.vlcBroken()) return false; // VLC crashed on this TV before
        if (prefs.audioDelay(it) != 0) return true; // audio sync set for this title: only VLC can do it
        int mode = prefs.engineMode();
        if (mode == 2) return true;
        if (mode == 1) return false;
        Boolean choice = prefs.playerChoice(it);
        if (choice != null) return choice;
        return Item.LIVE.equals(it.type);
    }

    private void startEngine(boolean vlc) {
        releaseEngine();
        try {
            engine = vlc ? new VlcEngine(this, prefs, this) : new ExoEngine(this, prefs, this);
            usingVlc = vlc;
        } catch (Throwable t) { // VLC not available on this device: fall back
            engine = new ExoEngine(this, prefs, this);
            usingVlc = false;
        }
        videoHost.addView(engine.view(), 0, match());
    }

    private void releaseEngine() {
        if (engine == null) return;
        videoHost.removeView(engine.view());
        engine.release();
        engine = null;
        spinner.setVisibility(View.GONE);
    }

    /** Swap engines mid-playback, keeping the position for movies. */
    private void switchEngine(boolean vlc, String why) {
        long pos = isLive() || engine == null ? 0 : engine.position();
        startEngine(vlc);
        status(why);
        load(pos);
    }

    // Engine.Listener

    public void onReady() {
        retries = 0;
        errorText.setVisibility(View.GONE);
    }

    public void onEnded() {
        if (isLive()) { // server dropped the stream: reconnect
            play(false);
            return;
        }
        if (upNext() != null) playNext(); // next episode, or the next thing in your Queue
        else {
            prefs.setWatched(current(), true);
            finish();
        }
    }

    public void onPlayingChanged(boolean playing) {
        centerIcon.setPaused(!playing);
        if (isLive()) return;
        if (!playing && engine != null && engine.isReady()) {
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

    public void onBuffering(boolean buffering) {
        spinner.setVisibility(buffering ? View.VISIBLE : View.GONE);
    }

    public void onTracksChanged() {
        // The sound is in a format this device can't play with the Android player (DTS, TrueHD…):
        // the Android player would carry on silently, so move to VLC, which decodes it itself.
        if (engine != null && !usingVlc && !isLive() && prefs.engineMode() == 0 && !prefs.vlcBroken()
                && prefs.playerChoice(current()) != Boolean.FALSE && engine.audioUnsupported()) {
            prefs.rememberVlc(current());
            switchEngine(true, "This sound format needs the VLC player — switching");
            return;
        }
        if (trackPanel.getVisibility() == View.VISIBLE) buildTrackRows(false);
    }

    public void onError(String what, boolean behindLiveWindow) {
        if (engine == null) return;
        if (behindLiveWindow) {
            engine.retry(true);
            return;
        }
        if (retries < 2) {
            retries++;
            status("Reconnecting…");
            Async.MAIN.postDelayed(new Runnable() {
                public void run() { if (engine != null) engine.retry(false); }
            }, 1500);
            return;
        }
        if (isLive() && !triedAltFormat) { // try .m3u8 instead of .ts (or the other way round)
            triedAltFormat = true;
            retries = 0;
            liveFormat = "ts".equals(liveFormat) ? "m3u8" : "ts";
            load(0);
            return;
        }
        if (!isLive() && !triedOtherEngine && prefs.engineMode() == 0) { // movies: try the other engine
            triedOtherEngine = true;
            retries = 0;
            liveFormat = prefs.liveFormat();
            switchEngine(!usingVlc, usingVlc ? "Trying Android player…" : "Trying VLC player…");
            return;
        }
        errorText.setText("Can't play this " + (isLive() ? "channel" : "video") + "\n" + what
                + "\n\nOK  Try again      Back  Exit" + (isLive() ? "      ▲▼  Other channel" : ""));
        errorText.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.GONE);
    }

    private Item current() { return queue.get(index); }

    private boolean isLive() { return Item.LIVE.equals(current().type); }

    // ------------------------------------------------------------ playback

    private void play(boolean resume) {
        if (videoHost == null) return;
        if (scrubbing) cancelScrub();
        hideNextCard();
        nextDismissed = false;
        showSkip(false);
        Item it = current();
        boolean vlc = wantsVlc(it);
        if (engine == null || vlc != usingVlc) startEngine(vlc);
        long start = 0;
        if (!isLive() && resume) {
            long pos = prefs.position(it);
            if (pos > 10000) {
                start = pos;
                if (firstStart) Toast.makeText(this, "Resuming from " + SeriesActivity.fmt(pos), Toast.LENGTH_SHORT).show();
            }
        }
        load(start);
        prefs.addHistory(it);
        loadSegments();
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

    private void load(long startMs) {
        errorText.setVisibility(View.GONE);
        lastRendered = -1;
        stallTicks = 0;
        engine.setAudioDelay(prefs.audioDelay(current())); // this channel's / title's own audio sync
        try {
            engine.load(api.playUrl(current(), liveFormat), startMs);
        } catch (Throwable t) { // VLC failed to start: record it and use the Android player
            App.writeCrash(this, t);
            if (!usingVlc || isLive()) throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
            prefs.setVlcBroken(true);
            startEngine(false);
            status("VLC couldn't start — using the Android player");
            engine.load(api.playUrl(current(), liveFormat), startMs);
        }
    }

    private void savePosition() {
        if (engine == null || queue == null || isLive()) return;
        long d = engine.duration(), p = engine.position();
        if (d > 0) prefs.setDuration(current(), d);
        if (d > 0 && (p > d - 60000 || p >= d * 0.9)) prefs.setWatched(current(), true); // finished (or into the credits)
        else if (p > 10000) prefs.setPosition(current(), p);
    }

    private void resetLiveState() {
        retries = 0;
        triedAltFormat = false;
        triedOtherEngine = false;
        recoveries = 0;
        liveFormat = prefs.liveFormat();
    }

    /**
     * Frozen-picture detector: playing, but no new video frames for 3 s.
     * Automatic mode moves the stream to VLC (and remembers it); otherwise it reconnects.
     */
    private void checkStall() {
        if (engine == null || !prefs.autoRecover() || !engine.isPlaying()) {
            stallTicks = 0;
            return;
        }
        float fps = engine.frameRate();
        int r = engine.framesRendered();
        if (r < 0 || fps < 0) return;          // engine can't tell, or no video (radio)
        if (fps > 0 && fps < 5) return;        // still-picture channels
        if (r != lastRendered) {
            lastRendered = r;
            stallTicks = 0;
            return;
        }
        if (++stallTicks < 3) return;
        stallTicks = 0;
        if (prefs.engineMode() == 0 && !usingVlc && prefs.playerChoice(current()) == null) {
            prefs.setPlayerChoice(current(), true);
            switchEngine(true, "Picture froze — switching to VLC player");
            return;
        }
        if (recoveries >= 4) return; // give up quietly rather than loop forever
        recoveries++;
        status("Re-syncing video…");
        if (isLive()) load(0);
        else engine.seekTo(engine.position());
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
        if (autoHide && engine != null && engine.isPlaying()) Async.MAIN.postDelayed(hideOverlay, 4000);
    }

    private void updateProgress() {
        if (engine == null || vodOverlay.getVisibility() != View.VISIBLE) return;
        long d = engine.duration();
        long p = seekTarget >= 0 ? seekTarget : engine.position();
        if (d <= 0) {
            vodBar.set(0, 0);
            timeLeft.setText(SeriesActivity.fmt(p));
            timeRight.setText("");
            return;
        }
        vodBar.set(p / (float) d, engine.buffered() / (float) d);
        timeLeft.setText(SeriesActivity.fmt(p));
        timeRight.setText("−" + SeriesActivity.fmt(Math.max(0, d - p)));
    }

    private void showBanner() {
        final Item it = current();
        final int at = index;
        liveNum.setText(it.num == null ? "" : it.num);
        liveName.setText(it.name + (prefs.isFav(it) ? "  ♥" : ""));
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
        videoHost.requestFocus();
    }

    private void openChannels() {
        fade(liveBanner, false);
        channelAdapter.playingId = current().id;
        channelAdapter.set(queue);
        fade(channelPanel, true);
        channelList.requestFocus();
        channelList.setSelection(index);
    }

    // ------------------------------------------------------------ subtitles, audio & player

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
        if (engine == null) return;

        final Item cur = current();
        // Episodes add their series to Favourites
        Item favTarget = cur;
        if (Item.EPISODE.equals(cur.type)) {
            favTarget = null;
            if (cur.parentId != null) {
                favTarget = new Item(Item.SERIES, cur.parentId, cur.parentName == null ? "" : cur.parentName);
                favTarget.icon = cur.parentIcon;
            }
        }
        if (favTarget != null) {
            final Item fav = favTarget;
            String what = Item.EPISODE.equals(cur.type) ? "series " : "";
            header("Favourites");
            option(prefs.isFav(fav) ? "♥  In Favourites — remove " + what.trim() : "♡  Add " + what + "to Favourites", false, new Runnable() {
                public void run() {
                    Open.toggleFav(PlayerActivity.this, prefs, fav);
                    if (isLive()) showBanner();
                }
            });
        }

        header("Subtitles");
        boolean off = engine.subtitlesAreOff();
        View offRow = option("Off", off, new Runnable() {
            public void run() {
                engine.subtitlesOff();
                prefs.setSubtitleLanguage("off");
            }
        });
        if (off) firstSelected = offRow;
        List<Engine.Track> text = engine.tracks(Engine.TEXT);
        for (final Engine.Track t : text) {
            View o = option(t.label, t.selected, new Runnable() {
                public void run() {
                    engine.select(t);
                    prefs.setSubtitleLanguage(t.language == null ? "" : t.language);
                }
            });
            if (t.selected) firstSelected = o;
        }
        if (text.isEmpty()) note("This stream has no subtitles");

        header("Audio");
        List<Engine.Track> audio = engine.tracks(Engine.AUDIO);
        for (final Engine.Track t : audio) {
            View o = option(t.label, t.selected, new Runnable() {
                public void run() {
                    engine.select(t);
                    prefs.setAudioLanguage(t.language == null ? "" : t.language);
                }
            });
            if (t.selected && firstSelected == null) firstSelected = o;
        }
        if (audio.isEmpty()) note("No audio tracks found");

        audioSyncRows();

        header("Subtitle Size");
        for (int s = 0; s < SettingsActivity.SUB_SIZES.length; s++) {
            final int size = s;
            option(SettingsActivity.SUB_SIZES[s], prefs.subtitleSize() == s, new Runnable() {
                public void run() {
                    prefs.setSubtitleSize(size);
                    engine.applySubtitleStyle();
                }
            });
        }
        if (usingVlc) note("With VLC, size changes apply from the next video");

        if (!isLive()) {
        header("Player");
        option("Android player", !usingVlc, new Runnable() {
            public void run() {
                if (!usingVlc) return;
                prefs.setPlayerChoice(current(), false);
                hidePanels();
                switchEngine(false, "Switched to Android player");
            }
        });
        option("VLC player", usingVlc, new Runnable() {
            public void run() {
                if (usingVlc) return;
                prefs.setPlayerChoice(current(), true);
                hidePanels();
                switchEngine(true, "Switched to VLC player");
            }
        });
        note("Picture freezing? Try the other player — it's remembered for this video.");
        }

        if (focusedIndex >= 0 && focusedIndex < trackList.getChildCount() && trackList.getChildAt(focusedIndex).isFocusable()) {
            trackList.getChildAt(focusedIndex).requestFocus();
        } else if (focusSelected && firstSelected != null) {
            firstSelected.requestFocus();
        }
    }

    private static final int SYNC_STEP = 100, SYNC_MAX = 5000;

    /** Live TV: nudge the sound against the picture, remembered for this channel. */
    private void audioSyncRows() {
        final Item ch = current();
        final int d = prefs.audioDelay(ch);
        header("Audio Sync  ·  " + syncLabel(d));
        option("Sound is late  —  bring it forward", false, new Runnable() {
            public void run() { nudgeSync(ch, -SYNC_STEP); }
        });
        option("Sound is early  —  hold it back", false, new Runnable() {
            public void run() { nudgeSync(ch, SYNC_STEP); }
        });
        option("Reset", d == 0, new Runnable() {
            public void run() {
                prefs.setAudioDelay(ch, 0);
                engine.setAudioDelay(0);
                status("Audio sync reset");
            }
        });
        note("Press repeatedly in 0.1s steps. Remembered for this "
                + (isLive() ? "channel" : Item.EPISODE.equals(ch.type) ? "series" : "film")
                + (usingVlc ? "." : " (switches to the VLC player, which can shift the sound)."));
    }

    private void nudgeSync(Item ch, int step) {
        if (!usingVlc && prefs.vlcBroken()) {
            status("Audio sync needs the VLC player, which isn't working on this device");
            return;
        }
        int d = Math.max(-SYNC_MAX, Math.min(SYNC_MAX, prefs.audioDelay(ch) + step));
        prefs.setAudioDelay(ch, d);
        if (!usingVlc) {
            // Only VLC can shift the sound: move this to VLC (and keep it there next time)
            if (!isLive()) prefs.rememberVlc(ch);
            hidePanels();
            switchEngine(true, "Switched to VLC to adjust audio sync: " + syncLabel(d));
            return;
        }
        engine.setAudioDelay(d);
        status("Audio sync: " + syncLabel(d));
    }

    private static String syncLabel(int ms) {
        if (ms == 0) return "normal";
        String s = String.format(Locale.UK, "%.1fs", Math.abs(ms) / 1000f);
        return ms < 0 ? "sound " + s + " earlier" : "sound " + s + " later";
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

    // ------------------------------------------------------------ remote control

    /** Live TV: OK pressed and not yet released (tap opens channels, hold opens the menu). */
    private boolean okDown, okHeld;

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (Coach.showing(this)) return super.dispatchKeyEvent(e); // the tutorial card has the keys
        int k = e.getKeyCode();
        boolean okKey = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER;

        // Next episode card: ◀ ▶ between its two buttons, OK presses, Back = watch credits
        if (nextShown && engine != null && !panelOpen() && !scrubbing) {
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) (k == KeyEvent.KEYCODE_DPAD_LEFT ? nextCard.credits : nextCard.next).requestFocus();
                return true;
            }
            if (okKey) {
                if (e.getAction() == KeyEvent.ACTION_UP) {
                    if (nextCard.credits.isFocused()) watchCredits(); else playNext();
                }
                return true;
            }
        }

        // Films & series: OK pauses when released; held, it opens the menu (remotes without ☰)
        if (engine != null && okKey && !isLive() && !panelOpen() && errorText.getVisibility() != View.VISIBLE) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    okDown = true;
                    okHeld = false;
                } else if (okDown && !okHeld && !scrubbing && e.getEventTime() - e.getDownTime() >= 450) {
                    okHeld = true;
                    openTracks();
                }
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_UP) {
                if (okDown && !okHeld) {
                    if (scrubbing) commitScrub();
                    else if (skipShown) skipIntro();
                    else togglePause();
                }
                okDown = okHeld = false;
                return true;
            }
        }
        if (engine != null && okKey && isLive() && !panelOpen() && errorText.getVisibility() != View.VISIBLE) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    okDown = true;
                    okHeld = false;
                } else if (okDown && !okHeld && e.getRepeatCount() >= 1 && e.getEventTime() - e.getDownTime() >= 450) {
                    okHeld = true;
                    openTracks(); // hold OK: favourite, subtitles & audio (for remotes without ☰)
                }
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_UP) {
                if (okDown && !okHeld) openChannels();
                okDown = okHeld = false;
                return true;
            }
        }
        if (engine == null || e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
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
                    if (engine.isPlaying()) engine.pause();
                    else engine.play();
                    return true;
                default:
                    return super.dispatchKeyEvent(e);
            }
        }

        if (scrubbing) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    moveScrub(-1, e.getRepeatCount());
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    moveScrub(1, e.getRepeatCount());
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    cancelScrub();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    cancelScrub();
                    openTracks();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    commitScrub();
                    return true;
                default:
                    return super.dispatchKeyEvent(e);
            }
        }

        switch (k) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (e.getRepeatCount() > 0) return true;
                togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                engine.play();
                showOverlay(true);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                engine.pause();
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
                if (e.getRepeatCount() == 0) enterScrub();
                return true;
            case KeyEvent.KEYCODE_MENU:
                openTracks();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    // ------------------------------------------------------------ scrubbing

    /** ▼ while a film or episode plays: the knob appears where you are. */
    private void enterScrub() {
        long d = engine.duration();
        if (d <= 0) { // no length known (some streams): nothing to scrub along
            showOverlay(true);
            return;
        }
        Async.MAIN.removeCallbacks(endBurst);
        endBurst.run();
        scrubbing = true;
        scrubPos = engine.position();
        scrubRow.setVisibility(View.VISIBLE);
        vodHint.setText(SCRUB_HINT);
        showOverlay(false); // stays up while scrubbing
        updateScrub();
    }

    /**
     * ◀ ▶ while scrubbing. Steps grow while the button is held (like YouTube), from a few
     * seconds per press to minutes, so a whole film can be crossed in a couple of seconds.
     */
    private void moveScrub(int dir, int repeat) {
        long d = engine.duration();
        if (d <= 0) return;
        long base = Math.max(10_000L, d / 120);           // ~1% of the film per press, at least 10 s
        float speed = repeat < 3 ? 1f : repeat < 8 ? 2f : repeat < 16 ? 4f : 8f;
        scrubPos = Math.max(0, Math.min(d - 1000, scrubPos + dir * (long) (base * speed)));
        updateScrub();
    }

    private void updateScrub() {
        long d = engine.duration();
        if (d <= 0) return;
        vodBar.setKnob(scrubPos / (float) d);
        long delta = scrubPos - engine.position();
        scrubTime.setText(SeriesActivity.fmt(scrubPos)
                + (Math.abs(delta) >= 1000 ? "   " + (delta < 0 ? "−" : "+") + SeriesActivity.fmt(Math.abs(delta)) : ""));
        placeScrubCard();
        // Like YouTube: once you stop moving, it plays from the new point after a short pause
        Async.MAIN.removeCallbacks(scrubCommit);
        Async.MAIN.postDelayed(scrubCommit, 1000);
    }

    /** Keep the time centred over the knob, without running off either edge. */
    private void placeScrubCard() {
        if (scrubCard.getWidth() == 0) {
            scrubCard.post(new Runnable() { public void run() { if (scrubbing) placeScrubCard(); } });
            return;
        }
        float barLeft = vodBar.getLeft() - scrubRow.getLeft();
        float x = barLeft + vodBar.knobX() - scrubCard.getWidth() / 2f;
        x = Math.max(0, Math.min(scrubRow.getWidth() - scrubCard.getWidth(), x));
        scrubCard.setTranslationX(x);
    }

    /** OK, or a moment after you stop moving: play from the knob. */
    private void commitScrub() {
        if (!scrubbing) return;
        long target = scrubPos;
        exitScrub();
        if (Math.abs(target - engine.position()) >= 1000) engine.seekTo(target);
        if (!engine.isPlaying()) engine.play();
        showOverlay(true);
    }

    /** Back / ▲: leave the knob where the film actually is. */
    private void cancelScrub() {
        if (!scrubbing) return;
        exitScrub();
        showOverlay(true);
    }

    private void exitScrub() {
        scrubbing = false;
        Async.MAIN.removeCallbacks(scrubCommit);
        scrubRow.setVisibility(View.GONE);
        vodBar.setKnob(-1);
        vodHint.setText(VOD_HINT);
    }

    // ------------------------------------------------------------ next episode

    /** Shows the Next Episode card in the last 30 seconds of an episode that has a next one. */
    /** What plays after this: the next episode, or else the next thing in your Queue. */
    private Item upNext() {
        Item cur = current();
        if (Item.EPISODE.equals(cur.type) && index < queue.size() - 1) return queue.get(index + 1);
        return prefs.queueNextAfter(cur);
    }

    /** When the card appears: when the credits start (if the intro database knows), else 30 s from the end. */
    private long cardAt(long d) {
        long credits = segments != null ? segments.creditsStart : -1;
        if (credits > 60_000 && credits < d - 5_000) return credits;
        return d - 30_000;
    }

    private void checkNextCard() {
        if (engine == null || isLive()) return;
        long d = engine.duration(), p = engine.position();
        if (d < 5 * 60_000L) return;
        long at = cardAt(d), left = d - p;
        if (p < at - 15_000) nextDismissed = false; // went back: offer it again later
        if (nextShown) {
            if (p < at - 5_000) hideNextCard();
            else nextCard.setPaused(!engine.isPlaying());
            return;
        }
        if (p >= at && left > 2_000 && !nextDismissed && !scrubbing && engine.isPlaying()) {
            Item nx = upNext();
            if (nx == null) return;
            boolean sameSeries = Item.EPISODE.equals(current().type) && Item.EPISODE.equals(nx.type)
                    && current().parentId != null && current().parentId.equals(nx.parentId);
            nextCard.next.setLabel(sameSeries ? "Next Episode" : "Next in Queue");
            nextShown = true;
            fade(vodOverlay, false);
            nextCard.setAlpha(0f);
            nextCard.setVisibility(View.VISIBLE);
            nextCard.animate().alpha(1f).setDuration(250).start();
            nextCard.next.requestFocus();
            nextCard.start(new Runnable() { public void run() { playNext(); } });
        }
    }

    private void hideNextCard() {
        if (nextCard == null) return;
        nextCard.stopTimer();
        if (nextShown) {
            nextShown = false;
            nextCard.setVisibility(View.GONE);
            videoHost.requestFocus();
        }
    }

    private void watchCredits() {
        nextDismissed = true;
        hideNextCard();
    }

    /** Next episode (in its series' list), or the next item in your Queue (played on its own). */
    private void playNext() {
        Item nx = upNext();
        hideNextCard();
        if (nx == null) return;
        prefs.setWatched(current(), true);
        if (Item.EPISODE.equals(current().type) && index < queue.size() - 1 && queue.get(index + 1) == nx) {
            index++;
        } else {
            queue = new java.util.ArrayList<>(java.util.Collections.singletonList(nx));
            index = 0;
        }
        retries = 0;
        play(false);
    }

    // ------------------------------------------------------------ skip intro

    private Intros.Segments segments;
    private TextView skipBtn;
    private boolean skipShown;

    /** Episodes: ask the intro database where this one's intro and credits are. */
    private void loadSegments() {
        segments = null;
        final Item ep = current();
        if (!Item.EPISODE.equals(ep.type) || ep.season < 0 || ep.parentName == null) return;
        final int at = index;
        Async.run(new Async.Work<Intros.Segments>() {
            public Intros.Segments run() {
                int n;
                try { n = Integer.parseInt(ep.num == null ? "" : ep.num.trim()); } catch (Exception e) { return null; }
                String id = Tmdb.findId(getApplicationContext(), Tmdb.TV, ep.parentName, ep.tmdb);
                return id == null ? null : Intros.get(id, ep.season, n);
            }
        }, new Async.Done<Intros.Segments>() {
            public void done(Intros.Segments s, Exception e) {
                if (!isFinishing() && at == index && current() == ep) segments = s;
            }
        });
    }

    /** The "Skip Intro" button shows while the intro is playing. */
    private void checkIntro() {
        if (engine == null || isLive() || segments == null || !segments.hasIntro() || scrubbing) {
            showSkip(false);
            return;
        }
        long p = engine.position();
        showSkip(p >= segments.introStart && p < segments.introEnd - 1500);
    }

    private void showSkip(boolean on) {
        if (skipBtn == null || on == skipShown) return;
        skipShown = on;
        fade(skipBtn, on);
    }

    private void skipIntro() {
        if (segments == null) return;
        engine.seekTo(segments.introEnd);
        showSkip(false);
        status("Skipped the intro");
    }

    private void togglePause() {
        if (engine.isPlaying()) {
            engine.pause();
        } else {
            engine.play();
            showOverlay(true);
        }
    }

    private void seekBy(long delta) {
        long d = engine.duration();
        long base = seekTarget >= 0 ? seekTarget : engine.position();
        long target = Math.max(0, base + delta);
        if (d > 0) target = Math.min(target, d - 1000);
        seekTarget = target;
        burst += delta;
        engine.seekTo(target);
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
        if (Coach.back(this)) return;
        if (scrubbing) {
            cancelScrub();
            return;
        }
        if (nextShown) {
            watchCredits();
            return;
        }
        if (videoHost != null && panelOpen()) {
            hidePanels();
            return;
        }
        if (engine != null && !isLive() && vodOverlay.getVisibility() == View.VISIBLE && engine.isPlaying()) {
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
