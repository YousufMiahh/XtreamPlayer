package com.xtreamplayer.tv;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * A muted preview playing behind a page (Netflix-style): a film or episode from where you
 * left it, or a highlighted card on Home. The picture underneath (the artwork) fades away
 * once the video has started, and comes back when it stops.
 */
final class Preview implements Engine.Listener {
    private final Activity a;
    private final Prefs prefs;
    private final XtreamApi api;
    private final FrameLayout host;
    private final View cover;
    private Engine engine;
    private Item item;
    private boolean playing;

    Preview(Activity a, Prefs prefs, XtreamApi api, FrameLayout host, View cover) {
        this.a = a;
        this.prefs = prefs;
        this.api = api;
        this.host = host;
        this.cover = cover;
    }

    boolean isPlaying(Item it) { return playing && item != null && it != null && item.key().equals(it.key()); }

    /** Plays this (muted) from startMs. */
    void play(Item it, long startMs) {
        if (it == null || a.isFinishing()) return;
        if (isPlaying(it)) return;
        try {
            if (engine == null) {
                boolean vlc = prefs.engineMode() != 1 && !prefs.vlcBroken();
                try {
                    engine = vlc ? new VlcEngine(a, prefs, this) : new ExoEngine(a, prefs, this);
                } catch (Throwable t) {
                    engine = new ExoEngine(a, prefs, this);
                }
                host.addView(engine.view(), 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            item = it;
            playing = true;
            engine.setVolume(0f);
            engine.load(api.playUrl(it, prefs.liveFormat()), Math.max(0, startMs));
        } catch (Throwable t) {
            stop();
        }
    }

    void stop() {
        playing = false;
        item = null;
        if (engine != null) engine.stopPlayback();
        if (cover != null) {
            cover.animate().cancel();
            cover.animate().alpha(1f).setDuration(300).start();
        }
    }

    void release() {
        stop();
        if (engine != null) {
            host.removeView(engine.view());
            engine.release();
            engine = null;
        }
    }

    // Engine.Listener

    public void onReady() {
        // A moment for the first picture, then uncover it
        if (playing && cover != null) cover.animate().alpha(0f).setStartDelay(300).setDuration(500).start();
    }

    public void onEnded() { stop(); }

    public void onError(String what, boolean behindLiveWindow) { stop(); }

    public void onPlayingChanged(boolean p) {}

    public void onBuffering(boolean buffering) {}

    public void onTracksChanged() {}
}
