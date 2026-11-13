package com.xtreamplayer.tv;

import android.content.Context;
import android.net.Uri;
import android.view.View;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * VLC's engine (libVLC). Bigger, but very forgiving with broken timestamps and odd encodings
 * in live IPTV streams — the usual cause of "sound plays but picture freezes" with ExoPlayer.
 */
public class VlcEngine implements Engine {
    private final Prefs prefs;
    private final LibVLC vlc;
    private final MediaPlayer mp;
    private VLCVideoLayout layout;
    private volatile Listener l;
    private volatile boolean ready, released;
    /** VLC's stop/set-media can block while a network stream closes: keep that off the screen thread. */
    private final java.util.concurrent.ExecutorService ops = java.util.concurrent.Executors.newSingleThreadExecutor();

    public VlcEngine(Context c, Prefs prefs, Listener listener) {
        this.prefs = prefs;
        this.l = listener;
        vlc = VlcHolder.get(c, prefs); // shared, already warmed up at launch
        mp = new MediaPlayer(vlc);
        layout = new VLCVideoLayout(c);
        layout.setFocusable(false);
        mp.setEventListener(new MediaPlayer.EventListener() {
            public void onEvent(MediaPlayer.Event e) {
                if (released) return;
                switch (e.type) {
                    case MediaPlayer.Event.Buffering:
                        l.onBuffering(e.getBuffering() < 100f);
                        break;
                    case MediaPlayer.Event.Playing:
                        markStarting(false); // VLC got going without crashing
                        prefs.raw().edit().putInt("vlc_crashes", 0).apply();
                        l.onBuffering(false);
                        if (!ready) {
                            ready = true;
                            l.onReady();
                            applyPreferredTracks();
                        }
                        if (volume < 1f) mp.setVolume(Math.round(volume * 100));
                        if (audioDelayMs != 0) mp.setAudioDelay(audioDelayMs * 1000L);
                        l.onPlayingChanged(true);
                        break;
                    case MediaPlayer.Event.Paused:
                        l.onPlayingChanged(false);
                        break;
                    case MediaPlayer.Event.EndReached:
                        l.onEnded();
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        l.onError("VLC couldn't open the stream", false);
                        break;
                    case MediaPlayer.Event.ESAdded:
                    case MediaPlayer.Event.ESDeleted:
                        l.onTracksChanged();
                        break;
                    default:
                        break;
                }
            }
        });
    }

    public View view() { return layout; }

    private MediaPlayer.ScaleType scale = MediaPlayer.ScaleType.SURFACE_BEST_FIT;

    public void setPicture(int mode) {
        scale = mode == PIC_FILL ? MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                : mode == PIC_STRETCH ? MediaPlayer.ScaleType.SURFACE_FILL : MediaPlayer.ScaleType.SURFACE_BEST_FIT;
        if (attached && !released) mp.setVideoScale(scale);
    }
    public String name() { return "VLC player"; }

    /** Attach the video surface; done on first load, once the view is in the window. */
    private boolean attached;

    private void attach() {
        if (attached) return;
        attached = true;
        mp.attachViews(layout, null, true, false);
        mp.setVideoScale(scale);
    }

    /** Remembered across a crash: see App. Written synchronously so it survives a native crash. */
    private void markStarting(boolean on) {
        prefs.raw().edit().putBoolean("vlc_in_flight", on).commit();
    }

    public void load(String url, long startMs) {
        ready = false;
        attach();
        markStarting(true);
        final Media m = new Media(vlc, Uri.parse(url));
        m.setHWDecoderEnabled(true, false);
        m.addOption(":http-user-agent=" + prefs.userAgent());
        // Xtream live .ts streams are always MPEG-TS: saying so skips VLC's format probing,
        // which was a big part of the wait before a channel appeared. (If a provider ever sends
        // something else, the error retry switches to .m3u8, which doesn't use this.)
        if (url.endsWith(".ts")) m.addOption(":demux=ts");
        if (startMs > 0) m.addOption(":start-time=" + (startMs / 1000.0));
        if (volume == 0f) m.addOption(":no-audio"); // muted preview: don't even decode the sound
        ops.execute(new Runnable() {
            public void run() {
                if (released) {
                    m.release();
                    return;
                }
                mp.setMedia(m); // stops the previous channel, which can take a moment
                m.release();
                mp.play();
            }
        });
    }

    public void retry(boolean behindLiveWindow) {
        ready = false;
        ops.execute(new Runnable() {
            public void run() {
                if (released) return;
                mp.stop();
                mp.play();
            }
        });
    }

    private volatile float volume = 1f;
    private volatile long audioDelayMs;

    /** Kept and applied again whenever playback starts (VLC resets it for each new stream). */
    public boolean setAudioDelay(long ms) {
        audioDelayMs = ms;
        return mp.setAudioDelay(ms * 1000L);
    }

    /** VLC only takes a volume once audio is running, so it's applied again when playback starts. */
    public void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        mp.setVolume(Math.round(volume * 100));
    }

    public void stopPlayback() {
        ready = false;
        ops.execute(new Runnable() {
            public void run() { if (!released) mp.stop(); }
        });
    }

    public void setListener(Listener listener) { l = listener; }

    /**
     * Keeps the stream playing while its picture moves to another screen (the Live TV mini
     * player): lets go of the current view and returns a fresh one for the new screen.
     * Add it to the new screen, then call reattach().
     */
    public View moveTo(Context c) {
        if (attached) {
            try {
                mp.detachViews();
            } catch (Throwable ignored) {
            }
            attached = false;
        }
        android.view.ViewGroup p = (android.view.ViewGroup) layout.getParent();
        if (p != null) p.removeView(layout);
        layout = new VLCVideoLayout(c);
        layout.setFocusable(false);
        return layout;
    }

    public void reattach() { attach(); }

    public void play() { mp.play(); }
    public void pause() { mp.pause(); }
    public boolean isPlaying() { return mp.isPlaying(); }
    public boolean isReady() { return ready; }

    public long position() { return Math.max(0, mp.getTime()); }
    public long duration() { long d = mp.getLength(); return d > 0 ? d : 0; }
    public long buffered() { return position(); }
    public void seekTo(long ms) { mp.setTime(ms); }

    public List<Track> tracks(int type) {
        List<Track> out = new ArrayList<>();
        MediaPlayer.TrackDescription[] all = type == TEXT ? mp.getSpuTracks() : mp.getAudioTracks();
        if (all == null) return out;
        int current = type == TEXT ? mp.getSpuTrack() : mp.getAudioTrack();
        for (MediaPlayer.TrackDescription d : all) {
            if (d.id < 0) continue; // VLC's own "Disable" entry
            Track t = new Track();
            t.type = type;
            t.label = d.name == null || d.name.trim().isEmpty() ? (type == TEXT ? "Subtitles" : "Audio") + " " + d.id : d.name;
            t.selected = d.id == current;
            t.index = d.id;
            out.add(t);
        }
        return out;
    }

    public void select(Track t) {
        if (t.type == TEXT) mp.setSpuTrack(t.index);
        else mp.setAudioTrack(t.index);
    }

    public void subtitlesOff() { mp.setSpuTrack(-1); }

    public boolean subtitlesAreOff() { return mp.getSpuTrack() < 0; }

    /** VLC reads the subtitle look when it starts, so changes apply from the next video. */
    public void applySubtitleStyle() {}

    private void applyPreferredTracks() {
        if ("off".equals(prefs.subtitleLanguage())) mp.setSpuTrack(-1);
    }

    public int framesRendered() { return -1; }
    public float frameRate() { return 0; }

    public void release() {
        if (released) return;
        released = true;
        markStarting(false);
        try {
            if (attached) mp.detachViews(); // views must be let go of on the screen thread
        } catch (Throwable ignored) {
        }
        ops.execute(new Runnable() {
            public void run() {
                try {
                    mp.stop();
                    mp.release(); // the shared LibVLC stays alive for the next channel
                } catch (Throwable ignored) {
                }
            }
        });
        ops.shutdown();
    }

    public boolean audioUnsupported() { return false; } // VLC decodes the audio itself
}
