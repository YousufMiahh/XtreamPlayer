package com.xtreamplayer.tv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.view.View;

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

/** Android's built-in engine (Media3 ExoPlayer). Light and fast; strict with messy live streams. */
@SuppressLint("UnsafeOptInUsageError")
public class ExoEngine implements Engine {
    private final Prefs prefs;
    private final PlayerView view;
    private ExoPlayer player;
    private Listener l;

    public ExoEngine(Context c, Prefs prefs, Listener listener) {
        this.prefs = prefs;
        this.l = listener;
        view = new PlayerView(c);
        view.setUseController(false);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
        view.setFocusable(false);
        player = PlayerFactory.create(c, prefs);

        TrackSelectionParameters.Builder tb = player.getTrackSelectionParameters().buildUpon();
        String sl = prefs.subtitleLanguage();
        if ("off".equals(sl)) tb.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        else if (!sl.isEmpty()) tb.setPreferredTextLanguage(sl);
        if (!prefs.audioLanguage().isEmpty()) tb.setPreferredAudioLanguage(prefs.audioLanguage());
        player.setTrackSelectionParameters(tb.build());

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                l.onBuffering(state == Player.STATE_BUFFERING);
                if (state == Player.STATE_READY) l.onReady();
                else if (state == Player.STATE_ENDED) l.onEnded();
            }

            @Override
            public void onIsPlayingChanged(boolean playing) { l.onPlayingChanged(playing); }

            @Override
            public void onTracksChanged(Tracks tracks) { l.onTracksChanged(); }

            @Override
            public void onPlayerError(PlaybackException e) {
                l.onError(e.getErrorCodeName(), e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW);
            }
        });
        view.setPlayer(player);
        applySubtitleStyle();
    }

    public void setVolume(float v) { if (player != null) player.setVolume(v); }

    public void stopPlayback() {
        if (player == null) return;
        player.stop();
        player.clearMediaItems();
    }

    public void setListener(Listener listener) { l = listener; }

    public View view() { return view; }
    public String name() { return "Android player"; }

    public void load(String url, long startMs) {
        player.setMediaItem(PlayerFactory.media(url));
        if (startMs > 0) player.seekTo(startMs);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    public void retry(boolean behindLiveWindow) {
        if (behindLiveWindow) player.seekToDefaultPosition();
        player.prepare();
    }

    public void play() { player.play(); }
    public void pause() { player.pause(); }
    public boolean isPlaying() { return player.isPlaying(); }
    public boolean isReady() { return player.getPlaybackState() == Player.STATE_READY; }

    public long position() { return player.getCurrentPosition(); }
    public long duration() { long d = player.getDuration(); return d > 0 ? d : 0; }
    public long buffered() { return player.getBufferedPosition(); }
    public void seekTo(long ms) { player.seekTo(ms); }

    public List<Track> tracks(int type) {
        List<Track> out = new ArrayList<>();
        int exoType = type == TEXT ? C.TRACK_TYPE_TEXT : C.TRACK_TYPE_AUDIO;
        boolean textOff = type == TEXT && subtitlesAreOff();
        int n = 0;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != exoType) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Track t = new Track();
                t.type = type;
                Format f = g.getTrackFormat(i);
                t.label = trackName(f, type, ++n);
                t.language = f.language;
                t.selected = !textOff && g.isTrackSelected(i);
                t.group = g;
                t.index = i;
                out.add(t);
            }
        }
        return out;
    }

    public boolean audioUnsupported() {
        boolean any = false;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
            any = true;
            for (int i = 0; i < g.length; i++) if (g.isTrackSupported(i)) return false;
        }
        return any;
    }

    public void select(Track t) {
        Tracks.Group g = (Tracks.Group) t.group;
        int exoType = t.type == TEXT ? C.TRACK_TYPE_TEXT : C.TRACK_TYPE_AUDIO;
        TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(exoType, false)
                .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), t.index));
        if (t.type == TEXT) b.setPreferredTextLanguage(t.language);
        else b.setPreferredAudioLanguage(t.language);
        player.setTrackSelectionParameters(b.build());
    }

    public void subtitlesOff() {
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
    }

    public boolean subtitlesAreOff() {
        if (player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return true;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() == C.TRACK_TYPE_TEXT && g.isSelected()) return false;
        }
        return true;
    }

    public void applySubtitleStyle() {
        SubtitleView sv = view.getSubtitleView();
        if (sv == null) return;
        sv.setApplyEmbeddedStyles(false);
        sv.setApplyEmbeddedFontSizes(false);
        boolean bg = prefs.subtitleBackground();
        int bgColor = bg ? (PlayerActivity.SUB_OPACITY_ALPHA[prefs.subtitleOpacity()] << 24) : Color.TRANSPARENT;
        sv.setStyle(new CaptionStyleCompat(PlayerActivity.SUB_COLORS[prefs.subtitleColor()], bgColor, Color.TRANSPARENT,
                bg ? CaptionStyleCompat.EDGE_TYPE_NONE : CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, 0xE6000000, Ui.semibold()));
        int size = Math.max(0, Math.min(PlayerActivity.SUB_SIZES.length - 1, prefs.subtitleSize()));
        sv.setFractionalTextSize(PlayerActivity.SUB_SIZES[size]);
        sv.setBottomPaddingFraction(0.07f);
    }

    public int framesRendered() {
        DecoderCounters dc = player.getVideoDecoderCounters();
        if (dc == null) return -1;
        dc.ensureUpdated();
        return dc.renderedOutputBufferCount;
    }

    public float frameRate() {
        Format vf = player.getVideoFormat();
        if (vf == null) return -1;
        return vf.frameRate > 0 ? vf.frameRate : 0;
    }

    public void release() {
        if (player == null) return;
        view.setPlayer(null);
        player.release();
        player = null;
    }

    static String trackName(Format f, int type, int n) {
        String name;
        if (f.label != null && !f.label.trim().isEmpty()) {
            name = f.label.trim();
        } else if (f.language != null && !f.language.isEmpty() && !"und".equals(f.language)) {
            String d = new Locale(f.language).getDisplayLanguage(Locale.UK);
            name = d == null || d.isEmpty() ? f.language : d;
        } else {
            name = (type == AUDIO ? "Audio " : "Subtitles ") + n;
        }
        List<String> extra = new ArrayList<>();
        if (type == AUDIO) {
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
}
