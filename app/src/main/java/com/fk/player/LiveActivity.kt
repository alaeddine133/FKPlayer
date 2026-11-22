package com.fk.player

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch

/** Direct : catégories | chaînes | aperçu vidéo + guide « en cours / ensuite ». */
class LiveActivity : BaseActivity() {

    companion object { const val FAV = "__fav"; const val ALL = "__all" }

    private lateinit var catAd: Adapter<Category>
    private lateinit var chAd: Adapter<Item>
    private lateinit var catList: RecyclerView
    private lateinit var chList: RecyclerView
    private lateinit var pv: PlayerView
    private var player: ExoPlayer? = null

    private var cats: List<Category> = emptyList()
    private val counts = HashMap<String, Int>()
    private var selCat = 0
    private var chans: List<Item> = emptyList()
    private var current: Item? = null
    private var pendingCat: Runnable? = null
    private var pendingPreview: Runnable? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (needData()) return
        setContentView(R.layout.activity_live)
        setTop("Direct", "${Repo.live.size} chaînes" + if (Prefs.frFirst) " · français en premier" else "")
        pulse(findViewById(R.id.liveDot))

        Repo.live.groupingBy { it.catId }.eachCountTo(counts)
        cats = listOf(Category(FAV, "Favoris"), Category(ALL, "Toutes les chaînes")) +
            Repo.liveCats.filter { (counts[it.id] ?: 0) > 0 }

        catList = findViewById(R.id.cats); chList = findViewById(R.id.chans); pv = findViewById(R.id.previewVideo)
        catList.layoutManager = LinearLayoutManager(this)
        chList.layoutManager = LinearLayoutManager(this)

        catAd = Adapter(R.layout.item_cat, 1.03f) { v, c, i ->
            v.isActivated = i == selCat
            v.findViewById<TextView>(R.id.name).text = c.name
            v.findViewById<TextView>(R.id.count).text = when (c.id) {
                FAV -> Prefs.favs().count { it.startsWith("${Type.LIVE}:") }.toString()
                ALL -> Repo.live.size.toString()
                else -> (counts[c.id] ?: 0).toString()
            }
        }
        catAd.onFocus = { _, i ->
            pendingCat?.let { ui.removeCallbacks(it) }
            pendingCat = Runnable { selectCat(i) }.also { ui.postDelayed(it, 280) }
        }
        catAd.onClick = { _, i -> selectCat(i); chList.post { chList.getChildAt(0)?.requestFocus() } }
        catList.adapter = catAd

        chAd = Adapter(R.layout.item_channel, 1.02f) { v, c, _ -> bindChannel(v, c) }
        chAd.onFocus = { c, _ ->
            pendingPreview?.let { ui.removeCallbacks(it) }
            pendingPreview = Runnable { preview(c) }.also { ui.postDelayed(it, 650) }
        }
        chAd.onClick = { c, i -> if (current?.id == c.id) fullscreen(i) else { preview(c); fullscreen(i) } }
        chAd.onLong = { c, _ -> toggleFav(c) { chAd.notifyDataSetChanged(); catAd.notifyItemChanged(0) } }
        chList.adapter = chAd

        findViewById<View>(R.id.preview).setOnClickListener { current?.let { c -> fullscreen(chans.indexOfFirst { it.id == c.id }) } }

        // Ouvre la dernière catégorie / chaîne regardée
        val lastCat = cats.indexOfFirst { it.id == Prefs.lastLiveCat }
        selCat = if (lastCat >= 0) lastCat else if (Prefs.favs().any { it.startsWith("${Type.LIVE}:") }) 0 else 1
        catAd.items = cats
        loadChannels()
        val lastIdx = chans.indexOfFirst { it.id == Prefs.lastLiveId }
        chList.post {
            if (lastIdx >= 0) {
                (chList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(lastIdx, 0)
                chList.post { chList.findViewHolderForAdapterPosition(lastIdx)?.itemView?.requestFocus() }
            } else chList.getChildAt(0)?.requestFocus() ?: catList.getChildAt(selCat)?.requestFocus()
        }
        popIn(catList, chList, findViewById(R.id.preview))
    }

    private fun bindChannel(v: View, c: Item) {
        v.isActivated = current?.id == c.id
        v.findViewById<TextView>(R.id.num).text = if (c.num > 0) c.num.toString() else ""
        v.findViewById<TextView>(R.id.name).text = c.name
        v.findViewById<TextView>(R.id.ini).text = initials(c.name)
        val logo = v.findViewById<ImageView>(R.id.logo)
        if (c.icon != null) logo.load(c.icon) { crossfade(true); listener(onError = { _, _ -> logo.setImageDrawable(null) }) } else logo.setImageDrawable(null)
        v.findViewById<View>(R.id.fav).visibility = if (Prefs.isFav(c.key)) View.VISIBLE else View.GONE
        val now = v.findViewById<TextView>(R.id.now)
        now.text = ""
        if (Repo.isXtream) {
            now.tag = c.id
            lifecycleScope.launch {
                val e = Repo.shortEpg(c).firstOrNull()
                if (now.tag == c.id) now.text = e?.title ?: ""
            }
        }
    }

    private fun selectCat(i: Int) {
        if (i == selCat && chans.isNotEmpty()) return
        val old = selCat; selCat = i
        catAd.notifyItemChanged(old); catAd.notifyItemChanged(i)
        loadChannels()
    }

    private fun loadChannels() {
        val c = cats.getOrNull(selCat) ?: return
        chans = when (c.id) {
            FAV -> Repo.live.filter { Prefs.isFav(it.key) }
            ALL -> Repo.live
            else -> Repo.live.filter { it.catId == c.id }
        }
        chAd.items = chans
        chList.scrollToPosition(0)
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = FkPlayer.build(this, true)
        p.volume = 1f
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                findViewById<ProgressBar>(R.id.previewLoading).visibility = if (s == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            }
            override fun onPlayerError(error: PlaybackException) {
                findViewById<TextView>(R.id.pvTitle).text = "Chaîne indisponible pour le moment"
                findViewById<ProgressBar>(R.id.previewLoading).visibility = View.GONE
            }
        })
        pv.player = p
        player = p
        return p
    }

    private fun preview(c: Item) {
        if (current?.id == c.id) return
        val prev = current
        current = c
        chans.indexOfFirst { it.id == prev?.id }.takeIf { it >= 0 }?.let { chAd.notifyItemChanged(it) }
        chans.indexOfFirst { it.id == c.id }.takeIf { it >= 0 }?.let { chAd.notifyItemChanged(it) }

        val p = ensurePlayer()
        p.setMediaItem(FkPlayer.mediaItem(Repo.streamUrl(c), true))
        p.prepare(); p.playWhenReady = true

        Prefs.lastLiveId = c.id; Prefs.lastLiveName = c.name; Prefs.lastLiveCat = cats.getOrNull(selCat)?.id ?: ""
        findViewById<TextView>(R.id.pvChannel).text = (if (c.num > 0) "${c.num} · " else "") + c.name
        findViewById<TextView>(R.id.pvTitle).text = c.name
        findViewById<View>(R.id.pvTimes).visibility = View.INVISIBLE
        findViewById<TextView>(R.id.next1).text = if (Repo.isXtream) "Chargement du guide…" else "Guide TV non fourni par la playlist M3U"
        findViewById<TextView>(R.id.next2).text = ""
        lifecycleScope.launch {
            val epg = Repo.shortEpg(c)
            if (current?.id != c.id) return@launch
            val now = epg.firstOrNull()
            if (now != null) {
                findViewById<TextView>(R.id.pvTitle).text = now.title
                findViewById<TextView>(R.id.pvStart).text = hhmm(now.start)
                findViewById<TextView>(R.id.pvEnd).text = hhmm(now.end)
                val span = (now.end - now.start).coerceAtLeast(1)
                findViewById<ProgressBar>(R.id.pvProg).progress = (((System.currentTimeMillis() - now.start) * 1000) / span).toInt()
                findViewById<View>(R.id.pvTimes).visibility = View.VISIBLE
            }
            val n = epg.drop(1)
            findViewById<TextView>(R.id.next1).text = n.getOrNull(0)?.let { "${hhmm(it.start)}   ${it.title}" } ?: "Pas de programme annoncé"
            findViewById<TextView>(R.id.next2).text = n.getOrNull(1)?.let { "${hhmm(it.start)}   ${it.title}" } ?: ""
        }
    }

    private fun fullscreen(i: Int) {
        if (i < 0) return
        player?.stop()
        current = null
        play(chans, i)
    }

    override fun onStart() {
        super.onStart()
        // Au retour du plein écran : on relance l'aperçu de la dernière chaîne vue
        if (Repo.loaded && ::chAd.isInitialized) {
            val last = chans.firstOrNull { it.id == Prefs.lastLiveId }
            if (last != null && player == null) ui.postDelayed({ preview(last) }, 400)
            chAd.notifyDataSetChanged()
        }
    }

    override fun onStop() {
        super.onStop()
        player?.release(); player = null; pv.player = null; current = null
    }
}
