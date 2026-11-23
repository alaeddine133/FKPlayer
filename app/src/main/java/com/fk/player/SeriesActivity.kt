package com.fk.player

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch

/** Fiche série : affiche, résumé, saisons et épisodes (avec reprise). */
class SeriesActivity : BaseActivity() {

    private lateinit var seasonAd: Adapter<Int>
    private lateinit var epAd: Adapter<Episode>
    private var seasons: Map<Int, List<Episode>> = emptyMap()
    private var seasonKeys: List<Int> = emptyList()
    private var selSeason = 0
    private var cover: String? = null
    private lateinit var serie: Item

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (needData()) return
        setContentView(R.layout.activity_series)
        val id = intent.getStringExtra("id") ?: run { finish(); return }
        serie = Repo.series.firstOrNull { it.id == id } ?: run { finish(); return }
        cover = serie.icon

        findViewById<TextView>(R.id.title).text = serie.name
        findViewById<TextView>(R.id.meta).text = serie.rating?.let { "★ $it" } ?: ""
        serie.icon?.let { findViewById<ImageView>(R.id.cover).load(it) { crossfade(true) }; findViewById<ImageView>(R.id.backdrop).load(it) }
        val favBtn = findViewById<TextView>(R.id.favBtn)
        fun favLabel() { val f = Prefs.isFav(serie.key); favBtn.text = if (f) "Dans Ma liste" else "Ajouter à Ma liste"; favBtn.isActivated = f }
        favLabel()
        favBtn.setOnClickListener { Prefs.toggleFav(serie.key); favLabel() }

        val sl = findViewById<RecyclerView>(R.id.seasons)
        sl.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        seasonAd = Adapter(R.layout.item_season) { v, s, i ->
            v.isActivated = i == selSeason
            (v as TextView).text = "Saison $s"
        }
        seasonAd.onFocus = { _, i -> selectSeason(i) }
        seasonAd.onClick = { _, i -> selectSeason(i); findViewById<RecyclerView>(R.id.episodes).getChildAt(0)?.requestFocus() }
        sl.adapter = seasonAd

        val el = findViewById<RecyclerView>(R.id.episodes)
        el.layoutManager = LinearLayoutManager(this)
        epAd = Adapter(R.layout.item_episode, 1.02f) { v, e, _ ->
            v.findViewById<TextView>(R.id.epNum).text = "É${e.num}"
            v.findViewById<TextView>(R.id.name).text = "${e.num}. ${e.title}"
            v.findViewById<TextView>(R.id.plot).text = e.plot ?: ""
            val t = v.findViewById<ImageView>(R.id.thumb)
            (e.icon ?: cover)?.let { u -> t.load(u) { crossfade(true) } } ?: t.setImageDrawable(null)
            val pr = v.findViewById<ProgressBar>(R.id.resume)
            val p = Prefs.progress("${Type.EPISODE}:${e.id}")
            if (p > 0) { pr.progress = p; pr.visibility = View.VISIBLE } else pr.visibility = View.GONE
        }
        epAd.onClick = { _, i ->
            val list = epAd.items.map { Repo.episodeItem(it, cover) }
            playChoice(list, i, serie.name)
        }
        el.adapter = epAd
        similarRow(findViewById(R.id.similar), serie, 90)
        findViewById<View>(R.id.simTitle).visibility = findViewById<RecyclerView>(R.id.similar).visibility

        lifecycleScope.launch {
            try {
                val info = Repo.seriesInfo(serie.id)
                findViewById<View>(R.id.loading).visibility = View.GONE
                findViewById<TextView>(R.id.plot).text = info.info.plot ?: ""
                findViewById<TextView>(R.id.meta).text = listOfNotNull(info.info.year, info.info.genre, serie.rating?.let { "★ $it" }).joinToString("  ·  ")
                if (cover == null && info.info.cover != null) { cover = info.info.cover; findViewById<ImageView>(R.id.cover).load(info.info.cover) }
                info.info.backdrop?.let { findViewById<ImageView>(R.id.backdrop).load(it) { crossfade(true) } }
                findViewById<TextView>(R.id.cast).text = listOfNotNull(info.info.director?.let { "Réalisation : $it" }, info.info.cast?.let { "Avec : $it" }).joinToString("\n")
                info.info.trailer?.let { t -> findViewById<View>(R.id.trailerBtn).apply { visibility = View.VISIBLE; setOnClickListener { trailer(t) } } }
                seasons = info.seasons; seasonKeys = seasons.keys.sorted()
                if (seasonKeys.isEmpty()) { findViewById<TextView>(R.id.plot).text = "Aucun épisode disponible pour cette série."; return@launch }
                // Saison de l'épisode commencé le plus récemment
                val lastEp = Prefs.resumeKeys().firstOrNull { k -> seasons.values.flatten().any { "${Type.EPISODE}:${it.id}" == k } }
                selSeason = lastEp?.let { k -> seasonKeys.indexOfFirst { s -> seasons[s]!!.any { "${Type.EPISODE}:${it.id}" == k } } }?.coerceAtLeast(0) ?: 0
                seasonAd.items = seasonKeys
                epAd.items = seasons[seasonKeys[selSeason]] ?: emptyList()
                el.post {
                    val idx = epAd.items.indexOfFirst { "${Type.EPISODE}:${it.id}" == lastEp }.coerceAtLeast(0)
                    el.scrollToPosition(idx)
                    el.post { el.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus() }
                }
            } catch (e: Exception) {
                findViewById<View>(R.id.loading).visibility = View.GONE
                findViewById<TextView>(R.id.plot).text = "Impossible de charger la série : ${e.message}"
            }
        }
    }

    private fun selectSeason(i: Int) {
        if (i == selSeason) return
        val old = selSeason; selSeason = i
        seasonAd.notifyItemChanged(old); seasonAd.notifyItemChanged(i)
        epAd.items = seasons[seasonKeys[i]] ?: emptyList()
    }

    override fun onResume() {
        super.onResume()
        if (::epAd.isInitialized) epAd.notifyItemRangeChanged(0, epAd.itemCount)  // garde le focus sur l'épisode
    }
}
