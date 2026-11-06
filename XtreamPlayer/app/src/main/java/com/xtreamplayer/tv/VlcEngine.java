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
    /** VLC's relative subtitle sizes (smaller number = bigger text). */
    private static final int[] REL_SIZES = {20, 17, 14, 11};

    private final Prefs prefs;
    private final LibVLC vlc;
    private final MediaPlayer mp;
    private final VLCVideoLayout layout;
    private final Listener l;
    private boolean ready, released;

    public VlcEngine(Context c, Prefs prefs, final Listener l) {
        this.prefs = prefs;
        this.l = l;
        ArrayList<String> o = new ArrayList<>();
        o.add("--http-reconnect");
        o.add("--network-caching=2000");
        o.add("--live-caching=2000");
        o.add("--clock-jitter=0");
        o.add("--clock-synchro=0");
        o.add("--audio-time-stretch");
        o.add("--avcodec-skiploopfilter=1");
        o.add("--avcodec-hurry-up");
        // Subtitle look, from Settings
        int color = PlayerActivity.SUB_COLORS[prefs.subtitleColor()] & 0xFFFFFF;
        o.add("--freetype-color=" + color);
        o.add("--freetype-rel-fontsize=" + REL_SIZES[Math.max(0, Math.min(3, prefs.subtitleSize()))]);
        o.add("--freetype-bold");
        if (prefs.subtitleBackground()) {
            o.add("--freetype-background-color=0");
            o.add("--freetype-background-opacity=" + PlayerActivity.SUB_OPACITY_ALPHA[prefs.subtitleOpacity()]);
            o.add("--freetype-shadow-opacity=0");
        } else {
            o.add("--freetype-background-opacity=0");
            o.add("--freetype-shadow-opacity=200");
        }
        vlc = new LibVLC(c, o);
        mp = new MediaPlayer(vlc);
        layout = new VLCVideoLayout(c);
        layout.setFocusable(false);
        mp.attachViews(layout, null, true, false);
        mp.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
        mp.setEventListener(new MediaPlayer.EventListener() {
            public void onEvent(MediaPlayer.Event e) {
                if (released) return;
                switch (e.type) {
                    case MediaPlayer.Event.Buffering:
                        l.onBuffering(e.getBuffering() < 100f);
                        break;
                    case MediaPlayer.Event.Playing:
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

    public void load(String url, long startMs) {
        ready = false;
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
        mp.setEventListener(null);
        mp.stop();
        mp.detachViews();
        mp.release();
        vlc.release();
    }
}
