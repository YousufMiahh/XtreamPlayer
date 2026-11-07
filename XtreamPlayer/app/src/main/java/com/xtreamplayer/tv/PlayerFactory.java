package com.xtreamplayer.tv;

import android.annotation.SuppressLint;
import android.content.Context;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;

/** One place that builds ExoPlayer the way IPTV streams need it. */
@SuppressLint("UnsafeOptInUsageError")
public final class PlayerFactory {
    private PlayerFactory() {}

    public static ExoPlayer create(Context c, Prefs prefs) { return create(c, prefs, false); }

    /**
     * @param preview true for the small Live TV preview: starts showing the picture after
     * 0.3 s of video instead of the usual 2.5 s (a stutter matters less there than a quick picture).
     */
    public static ExoPlayer create(Context c, Prefs prefs, boolean preview) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(prefs.userAgent())
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000);
        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                        | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS);
        DefaultMediaSourceFactory sources = new DefaultMediaSourceFactory(http, extractors);
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(c).setEnableDecoderFallback(true);
        ExoPlayer.Builder b = new ExoPlayer.Builder(c, renderers).setMediaSourceFactory(sources);
        if (preview) {
            b.setLoadControl(new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(1500, 8000, 300, 1000)
                    .build());
        }
        return b.build();
    }

    public static MediaItem media(String url) {
        MediaItem.Builder b = new MediaItem.Builder().setUri(url);
        if (url.endsWith(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8);
        return b.build();
    }
}
