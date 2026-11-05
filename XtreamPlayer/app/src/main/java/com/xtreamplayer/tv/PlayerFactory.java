package com.xtreamplayer.tv;

import android.annotation.SuppressLint;
import android.content.Context;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;

/** One place that builds ExoPlayer the way IPTV streams need it. */
@SuppressLint("UnsafeOptInUsageError")
public final class PlayerFactory {
    private PlayerFactory() {}

    public static ExoPlayer create(Context c, Prefs prefs) {
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
        return new ExoPlayer.Builder(c, renderers).setMediaSourceFactory(sources).build();
    }

    public static MediaItem media(String url) {
        MediaItem.Builder b = new MediaItem.Builder().setUri(url);
        if (url.endsWith(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8);
        return b.build();
    }
}
