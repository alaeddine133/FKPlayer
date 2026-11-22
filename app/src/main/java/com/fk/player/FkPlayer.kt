package com.fk.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout

/**
 * Configuration du lecteur ExoPlayer réglée pour l'IPTV :
 * tampon adapté au direct, flux TS tolérants, décodage matériel avec secours logiciel,
 * nombreuses tentatives réseau et User-Agent réglable.
 */
@OptIn(UnstableApi::class)
object FkPlayer {

    val uaNames = listOf("FK Player", "VLC", "Navigateur")
    val bufferNames = listOf("Faible · zapping rapide", "Normale · recommandé", "Élevée · connexion instable")
    val resizeNames = listOf("Adapter", "Remplir", "Zoom")

    fun userAgent(): String = when (Prefs.ua) {
        1 -> "VLC/3.0.20 LibVLC/3.0.20"
        2 -> "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        else -> "FKPlayer/1.0 (Linux;Android) ExoPlayerLib/1.4.1"
    }

    fun resizeMode(): Int = when (Prefs.resize) {
        1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    fun build(ctx: Context, live: Boolean): ExoPlayer {
        // min, max, démarrage, reprise après coupure (ms)
        val b = when (Prefs.buffer) {
            0 -> intArrayOf(4_000, 20_000, 1_000, 2_000)
            2 -> intArrayOf(30_000, 120_000, 3_000, 6_000)
            else -> intArrayOf(15_000, 50_000, 1_500, 3_500)
        }
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(b[0], b[1], b[2], b[3])
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(if (live) 0 else 20_000, false)
            .build()

        val http = OkHttpDataSource.Factory(Repo.http).setUserAgent(userAgent())
        val ds = DefaultDataSource.Factory(ctx, http)

        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            .setConstantBitrateSeekingEnabled(true)

        val sources = DefaultMediaSourceFactory(ds, extractors)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))

        val renderers = DefaultRenderersFactory(ctx)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val player = ExoPlayer.Builder(ctx, renderers)
            .setLoadControl(load)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        val lang = Prefs.audioLang.takeIf { it != "orig" }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(lang)
            .setPreferredTextLanguage(lang ?: "fr")
            .setSelectUndeterminedTextLanguage(true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !Prefs.subtitles)
            .build()
        return player
    }

    fun mediaItem(url: String, live: Boolean): MediaItem {
        val low = url.lowercase().substringBefore('?')
        val b = MediaItem.Builder().setUri(url)
        when {
            low.endsWith(".m3u8") -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            low.endsWith(".ts") -> b.setMimeType(MimeTypes.VIDEO_MP2T)
        }
        if (live && low.endsWith(".m3u8")) {
            b.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(10_000).build())
        }
        return b.build()
    }
}
