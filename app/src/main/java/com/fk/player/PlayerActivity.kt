package com.fk.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.load
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Lecteur plein écran.
 * Direct : haut/bas = zapper, chiffres = aller à une chaîne, OK = infos.
 * Films/séries : OK = pause, gauche/droite = -10 s / +10 s (maintenir = plus vite).
 * Menu ou appui long sur OK = options (audio, sous-titres, format d'image, infos du flux).
 */
@OptIn(UnstableApi::class)
class PlayerActivity : BaseActivity() {

    private lateinit var pv: PlayerView
    private var player: ExoPlayer? = null
    private lateinit var top: View
    private lateinit var bottom: View
    private lateinit var spinner: ProgressBar
    private lateinit var status: TextView
    private lateinit var zap: TextView

    private var items: List<Item> = emptyList()
    private var index = 0
    private val cur: Item get() = items[index]
    private val isLive: Boolean get() = cur.type == Type.LIVE

    private var retries = 0
    private var played = false  // le chien de garde n'agit qu'apres une premiere lecture
    private var zapBuffer = ""
    private var centerDownAt = 0L
    private var longHandled = false

    private val hide = Runnable { showOverlay(false) }
    private val ticker = object : Runnable {
        override fun run() { updateProgress(); ui.postDelayed(this, 1000) }
    }
    private val zapGo = Runnable { goToNumber() }

    /** Chien de garde : flux bloqué trop longtemps en chargement → on le relance. */
    private val watchdog = Runnable {
        if (player?.playbackState == Player.STATE_BUFFERING && Prefs.reconnect) { status("Flux bloqué, relance…", 2000); restart() }
    }

    private fun restart() {
        val p = player ?: return
        val pos = p.currentPosition
        p.setMediaItem(FkPlayer.mediaItem(Repo.streamUrl(cur), isLive))
        if (!isLive && pos > 0) p.seekTo(pos)
        p.prepare(); p.playWhenReady = true
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        setContentView(R.layout.activity_player)
        items = PlayQueue.items; index = PlayQueue.index
        if (items.isEmpty()) { finish(); return }

        pv = findViewById(R.id.video); top = findViewById(R.id.top); bottom = findViewById(R.id.bottom)
        spinner = findViewById(R.id.spinner); status = findViewById(R.id.status); zap = findViewById(R.id.zap)
        pv.resizeMode = FkPlayer.resizeMode()

        findViewById<View>(R.id.optionsBtn).setOnClickListener { options() }
        findViewById<View>(R.id.root).setOnClickListener {
            if (top.visibility == View.VISIBLE && !isLive) togglePause() else showOverlay(true)
        }
    }

    override fun onStart() {
        super.onStart()
        if (items.isEmpty()) return
        val p = FkPlayer.build(this, isLive)
        p.addListener(listener)
        pv.player = p
        player = p
        load(resume = true)
        ui.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        ui.removeCallbacks(ticker); ui.removeCallbacks(watchdog)
        player?.release(); player = null; pv.player = null
    }

    /* ---------- Chargement ---------- */

    private fun load(resume: Boolean) {
        val p = player ?: return
        retries = 0; played = false
        val it = cur
        p.setMediaItem(FkPlayer.mediaItem(Repo.streamUrl(it), isLive))
        if (!isLive && resume) {
            val pos = Prefs.pos(it.key)
            if (pos > 0) { p.seekTo(pos); status("Reprise à ${duration(pos)}", 2500) }
        }
        p.prepare(); p.playWhenReady = true
        if (isLive) { Prefs.lastLiveId = it.id; Prefs.lastLiveName = it.name }
        fillInfo()
        showOverlay(true)
    }

    private fun fillInfo() {
        val it = cur
        findViewById<TextView>(R.id.topTitle).text = when {
            isLive -> "Direct"
            it.type == Type.EPISODE -> PlayQueue.seriesName ?: "Série"
            else -> "Film"
        }
        findViewById<TextView>(R.id.title).text = it.name
        findViewById<TextView>(R.id.sub).text = if (isLive) ((if (it.num > 0) "${it.num} · " else "") + it.name) else (it.sub ?: "")
        findViewById<TextView>(R.id.sub).maxLines = 1
        findViewById<View>(R.id.logoBox).visibility = if (isLive) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.logoIni).text = initials(it.name)
        val logo = findViewById<ImageView>(R.id.logo)
        if (isLive && it.icon != null) logo.load(it.icon) else logo.setImageDrawable(null)
        findViewById<TextView>(R.id.hint).text = if (isLive)
            "▲▼ zapper  ·  chiffres : n° de chaîne  ·  OK : infos  ·  Menu : options"
        else "OK : pause  ·  ◀▶ -10 s / +10 s  ·  Menu : audio, sous-titres, format"
        findViewById<TextView>(R.id.next).text = ""
        findViewById<TextView>(R.id.tStart).text = ""; findViewById<TextView>(R.id.tEnd).text = ""
        findViewById<ProgressBar>(R.id.prog).progress = 0
        if (isLive) {
            lifecycleScope.launch {
                val epg = Repo.shortEpg(it)
                if (cur.id != it.id) return@launch
                epg.firstOrNull()?.let { e ->
                    findViewById<TextView>(R.id.title).text = e.title
                    findViewById<TextView>(R.id.tStart).text = hhmm(e.start)
                    findViewById<TextView>(R.id.tEnd).text = hhmm(e.end)
                    updateProgress()
                }
                findViewById<TextView>(R.id.next).text = epg.getOrNull(1)?.let { n -> "Ensuite  ${hhmm(n.start)}   ${n.title}" } ?: ""
            }
        } else if (index + 1 < items.size) {
            findViewById<TextView>(R.id.next).text = "Ensuite : ${items[index + 1].name}"
        }
    }

    private fun updateProgress() {
        val p = player ?: return
        val bar = findViewById<ProgressBar>(R.id.prog)
        if (isLive) {
            val s = findViewById<TextView>(R.id.tStart).text.toString()
            if (s.isEmpty()) return
            lifecycleScope.launch {
                val e = Repo.shortEpg(cur).firstOrNull() ?: return@launch
                val span = (e.end - e.start).coerceAtLeast(1)
                bar.progress = (((System.currentTimeMillis() - e.start) * 1000) / span).toInt()
            }
        } else {
            val d = p.duration
            if (d > 0 && d != C.TIME_UNSET) {
                bar.progress = ((p.currentPosition * 1000) / d).toInt()
                bar.secondaryProgress = ((p.bufferedPosition * 1000) / d).toInt()
                findViewById<TextView>(R.id.tStart).text = duration(p.currentPosition)
                findViewById<TextView>(R.id.tEnd).text = "-" + duration(d - p.currentPosition)
            }
        }
    }

    private fun savePosition() {
        val p = player ?: return
        if (isLive) return
        val d = p.duration
        if (d > 0 && d != C.TIME_UNSET) Prefs.savePos(cur.key, p.currentPosition, d)
    }

    /* ---------- Événements du lecteur ---------- */

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            spinner.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            ui.removeCallbacks(watchdog)
            if (state == Player.STATE_BUFFERING && played) ui.postDelayed(watchdog, if (isLive) 15_000 else 30_000)
            if (state == Player.STATE_READY) { retries = 0; played = true; status.visibility = View.GONE; techInfo() }
            // Un flux direct ne « finit » jamais : si le serveur coupe, on relance
            if (state == Player.STATE_ENDED && isLive) {
                if (retries < 12) { retries++; status("Reconnexion… ($retries)", 2000); ui.postDelayed({ restart() }, 800L * retries) }
                else status("Le serveur a coupé la chaîne", 0)
            }
            if (state == Player.STATE_ENDED && !isLive) {
                Prefs.clearPos(cur.key)
                if (Prefs.autoNext && index + 1 < items.size) { index++; status("Épisode suivant…", 2000); load(resume = true) }
                else finish()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition(); p.prepare(); return
            }
            if (Prefs.reconnect && retries < 12) {
                retries++
                val pos = p.currentPosition
                status("Reconnexion… ($retries)", 0)
                ui.postDelayed({
                    val pl = player ?: return@postDelayed
                    pl.setMediaItem(FkPlayer.mediaItem(Repo.streamUrl(cur), isLive))
                    if (!isLive && pos > 0) pl.seekTo(pos)
                    pl.prepare(); pl.playWhenReady = true
                }, (1000L * retries).coerceAtMost(5000L))
            } else {
                spinner.visibility = View.GONE
                status(errorText(error), 0)
                showOverlay(true)
            }
        }

        override fun onTracksChanged(tracks: Tracks) { techInfo() }
    }

    private fun errorText(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when ((e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode) {
            401, 403 -> "Accès refusé : abonnement expiré ou trop d'appareils connectés"
            404 -> "Chaîne introuvable sur le serveur"
            429, 456, 458, 509 -> "Trop de connexions en même temps sur ce compte"
            in 500..599 -> "Le serveur IPTV a un problème, réessaie dans un moment"
            else -> "Flux refusé par le serveur"
        }
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Pas de connexion au serveur"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "Flux illisible : essaie le format HLS dans Paramètres"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Format vidéo non pris en charge par ta box"
        else -> "Lecture impossible (${e.errorCodeName})"
    }

    private fun techInfo() {
        val p = player ?: return
        val v = p.videoFormat
        val parts = mutableListOf<String>()
        if (v != null && v.height > 0) parts += when {
            v.height >= 2000 -> "4K"; v.height >= 1000 -> "FHD"; v.height >= 700 -> "HD"; else -> "SD"
        }
        p.audioFormat?.let { a -> if (a.channelCount >= 6) parts += "5.1" }
        val audios = p.currentTracks.groups.count { it.type == C.TRACK_TYPE_AUDIO }
        if (audios > 1) parts += "$audios langues"
        findViewById<TextView>(R.id.tech).apply { text = parts.joinToString(" · "); visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE }
    }

    /* ---------- Interface ---------- */

    private fun status(msg: String, autoHideMs: Long) {
        status.text = msg; status.visibility = View.VISIBLE
        if (autoHideMs > 0) ui.postDelayed({ if (status.text == msg) status.visibility = View.GONE }, autoHideMs)
    }

    private fun showOverlay(show: Boolean) {
        ui.removeCallbacks(hide)
        if (show) {
            if (top.visibility != View.VISIBLE) {
                top.visibility = View.VISIBLE; bottom.visibility = View.VISIBLE
                top.alpha = 0f; bottom.alpha = 0f
                top.animate().alpha(1f).setDuration(200).start(); bottom.animate().alpha(1f).setDuration(200).start()
            }
            if (player?.isPlaying != false) ui.postDelayed(hide, 5000)
        } else {
            top.animate().alpha(0f).setDuration(250).withEndAction { top.visibility = View.GONE }.start()
            bottom.animate().alpha(0f).setDuration(250).withEndAction { bottom.visibility = View.GONE }.start()
        }
    }

    private fun togglePause() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        status(if (p.playWhenReady) "Lecture" else "Pause", 1200)
        showOverlay(true)
    }

    private fun zapBy(d: Int) {
        if (!isLive || items.size < 2) return
        index = (index + d + items.size) % items.size
        load(resume = false)
    }

    private fun seekBy(ms: Long) {
        val p = player ?: return
        val d = p.duration
        var t = p.currentPosition + ms
        if (d > 0 && d != C.TIME_UNSET) t = t.coerceIn(0, d - 1000)
        p.seekTo(t.coerceAtLeast(0))
        status((if (ms > 0) "+" else "") + "${ms / 1000} s", 700)
        showOverlay(true); updateProgress()
    }

    private fun goToNumber() {
        val n = zapBuffer.toIntOrNull(); zapBuffer = ""; zap.visibility = View.GONE
        if (n == null) return
        val i = items.indexOfFirst { it.num == n }.takeIf { it >= 0 }
            ?: Repo.live.indexOfFirst { it.num == n }.takeIf { it >= 0 }?.let { gi ->
                items = Repo.live; gi
            }
        if (i == null) { status("Chaîne $n introuvable", 1500); return }
        index = i; load(resume = false)
    }

    /* ---------- Télécommande ---------- */

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        // Appui long sur OK = options
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (e.repeatCount == 0) { centerDownAt = System.currentTimeMillis(); longHandled = false }
                else if (!longHandled && System.currentTimeMillis() - centerDownAt > 600) { longHandled = true; options() }
                return true
            }
            if (e.action == KeyEvent.ACTION_UP) {
                if (!longHandled) { if (isLive) showOverlay(top.visibility != View.VISIBLE) else togglePause() }
                return true
            }
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP ->
                { if (isLive) zapBy(-1) else showOverlay(true); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN ->
                { if (isLive) zapBy(1) else showOverlay(true); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND ->
                { if (!isLive) seekBy(if (e.repeatCount > 4) -30_000 else -10_000) else showOverlay(true); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                { if (!isLive) seekBy(if (e.repeatCount > 4) 30_000 else 10_000) else showOverlay(true); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_SPACE ->
                { togglePause(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_SETTINGS -> { options(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (isLive) zapBy(1) else if (index + 1 < items.size) { savePosition(); index++; load(true) }; return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (isLive) zapBy(-1) else if (index > 0) { savePosition(); index--; load(true) }; return true }
            KeyEvent.KEYCODE_BACK -> {
                if (top.visibility == View.VISIBLE && player?.isPlaying == true) { showOverlay(false); return true }
            }
        }
        if (isLive && code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            zapBuffer += (code - KeyEvent.KEYCODE_0).toString()
            zap.text = zapBuffer; zap.visibility = View.VISIBLE
            ui.removeCallbacks(zapGo); ui.postDelayed(zapGo, 1500)
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) showOverlay(true)
        return super.dispatchTouchEvent(ev)
    }

    /* ---------- Options ---------- */

    private fun options() {
        val p = player ?: return
        showOverlay(true)
        val fav = Prefs.isFav(cur.key)
        val opts = arrayOf(
            "Piste audio",
            "Sous-titres",
            "Format d'image : ${FkPlayer.resizeNames[Prefs.resize]}",
            if (fav) "Retirer de Ma liste" else "Ajouter à Ma liste",
            "Relancer le flux",
            "Infos du flux"
        )
        AlertDialog.Builder(this).setTitle(cur.name).setItems(opts) { _, w ->
            when (w) {
                0 -> tracks(C.TRACK_TYPE_AUDIO)
                1 -> tracks(C.TRACK_TYPE_TEXT)
                2 -> { Prefs.resize = (Prefs.resize + 1) % 3; pv.resizeMode = FkPlayer.resizeMode(); status(FkPlayer.resizeNames[Prefs.resize], 1200) }
                3 -> toast(if (Prefs.toggleFav(cur.key)) "Ajouté à Ma liste" else "Retiré de Ma liste")
                4 -> { savePosition(); load(resume = true) }
                5 -> streamInfo(p)
            }
        }.show()
    }

    private fun tracks(type: Int) {
        val p = player ?: return
        val labels = mutableListOf<String>()
        val choices = mutableListOf<Pair<Tracks.Group, Int>?>()
        if (type == C.TRACK_TYPE_TEXT) { labels += "Désactivés"; choices += null }
        var checked = 0
        p.currentTracks.groups.filter { it.type == type }.forEach { g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                if (g.isTrackSelected(i)) checked = labels.size
                labels += trackLabel(g.getTrackFormat(i), labels.size + 1)
                choices += g to i
            }
        }
        if (choices.none { it != null }) { toast(if (type == C.TRACK_TYPE_AUDIO) "Une seule piste audio" else "Aucun sous-titre dans ce flux"); return }
        AlertDialog.Builder(this)
            .setTitle(if (type == C.TRACK_TYPE_AUDIO) "Piste audio" else "Sous-titres")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, w ->
                val c = choices[w]
                val b = p.trackSelectionParameters.buildUpon()
                if (c == null) b.setTrackTypeDisabled(type, true)
                else b.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(c.first.mediaTrackGroup, c.second))
                p.trackSelectionParameters = b.build()
                d.dismiss()
            }.show()
    }

    private fun trackLabel(f: Format, n: Int): String {
        val lang = f.language?.takeIf { it != "und" }?.let { Locale(it).getDisplayLanguage(Locale.FRENCH).replaceFirstChar { c -> c.uppercase() } }
        val name = f.label ?: lang ?: "Piste $n"
        val extra = mutableListOf<String>()
        if (f.channelCount >= 6) extra += "5.1" else if (f.channelCount == 2) extra += "stéréo"
        f.sampleMimeType?.substringAfter('/')?.uppercase()?.let { if (it.length <= 8) extra += it }
        return if (extra.isEmpty()) name else "$name  (${extra.joinToString(", ")})"
    }

    private fun streamInfo(p: ExoPlayer) {
        val v = p.videoFormat; val a = p.audioFormat
        val txt = buildString {
            append("Vidéo : ").append(v?.let { "${it.width}×${it.height}  ${it.sampleMimeType?.substringAfter('/') ?: ""}  ${if (it.frameRate > 0) "%.0f i/s".format(it.frameRate) else ""}" } ?: "—").append('\n')
            append("Audio : ").append(a?.let { "${it.sampleMimeType?.substringAfter('/') ?: ""}  ${it.channelCount} canaux  ${it.sampleRate} Hz" } ?: "—").append('\n')
            val br = (v?.bitrate ?: 0).coerceAtLeast(0)
            if (br > 0) append("Débit : ${br / 1000} kb/s\n")
            append("Tampon : ${FkPlayer.bufferNames[Prefs.buffer]}\n")
            append("Format direct : ${Prefs.liveFormat.uppercase()}  ·  User-Agent : ${FkPlayer.uaNames[Prefs.ua]}")
        }
        AlertDialog.Builder(this).setTitle("Infos du flux").setMessage(txt).setPositiveButton("OK", null).show()
    }
}
