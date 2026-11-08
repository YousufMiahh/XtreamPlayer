package com.xtreamplayer.tv;

import android.view.View;

import java.util.List;

/** A video engine the player screen can drive: ExoPlayer (Android's) or VLC. */
public interface Engine {
    int AUDIO = 1, TEXT = 3;

    interface Listener {
        void onReady();
        void onEnded();
        void onError(String what, boolean behindLiveWindow);
        void onPlayingChanged(boolean playing);
        void onBuffering(boolean buffering);
        void onTracksChanged();
    }

    /** An audio or subtitle track. */
    class Track {
        public int type;
        public String label;
        public String language;
        public boolean selected;
        Object group;   // engine-specific
        int index;      // engine-specific
    }

    View view();
    String name();

    void load(String url, long startMs);
    /** Try the same stream again after an error. */
    void retry(boolean behindLiveWindow);

    void play();
    void pause();
    boolean isPlaying();
    boolean isReady();

    long position();
    /** Milliseconds, or 0 if unknown/live. */
    long duration();
    long buffered();
    void seekTo(long ms);

    List<Track> tracks(int type);
    void select(Track t);
    void subtitlesOff();
    boolean subtitlesAreOff();
    /** Re-apply size/colour/background from Settings. */
    void applySubtitleStyle();

    /** Video frames shown so far, or -1 if the engine can't tell (used to spot frozen video). */
    int framesRendered();
    /** Frames per second of the video, 0 if unknown, -1 if there's no video. */
    float frameRate();

    /** 0 = muted, 1 = full volume. */
    void setVolume(float v);

    /** Stop the current stream but keep the engine for the next one (the Live TV preview). */
    void stopPlayback();

    /** Who hears about playback events (changes when the mini player moves between screens). */
    void setListener(Listener l);

    void release();
}
