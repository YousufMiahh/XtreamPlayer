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
    private final VLCVideoLayout layout;
    private final Listener l;
    private boolean ready, released;

    public VlcEngine(Context c, Prefs prefs, final Listener l) {
        this.prefs = prefs;
        this.l = l;
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
    public String name() { return "VLC player"; }

    /** Attach the video surface; done on first load, once the view is in the window. */
    private boolean attached;

    private void attach() {
        if (attached) return;
        attached = true;
        mp.attachViews(layout, null, true, false);
        mp.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
    }

    /** Remembered across a crash: see App. Written synchronously so it survives a native crash. */
    private void markStarting(boolean on) {
        prefs.raw().edit().putBoolean("vlc_in_flight", on).commit();
    }

    public void load(String url, long startMs) {
        ready = false;
        attach();
        markStarting(true);
        Media m = new Media(vlc, Uri.parse(url));
        m.setHWDecoderEnabled(true, false);
        m.addOption(":http-user-agent=" + prefs.userAgent());
        if (startMs > 0) m.addOption(":start-time=" + (startMs / 1000.0));
        mp.setMedia(m);
        m.release();
        mp.play();
    }

    public void retry(boolean behindLiveWindow) {
        ready = false;
        mp.stop();
        mp.play();
    }

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
            mp.stop();
            if (attached) mp.detachViews();
            mp.release(); // the shared LibVLC stays alive for the next channel
        } catch (Throwable ignored) {
        }
    }
}
