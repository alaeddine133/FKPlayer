package com.fk.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.launch

/** Accueil « Mosaïque » : grande tuile Direct + Films, Séries, Ma liste, Recherche, Paramètres. */
class HomeActivity : BaseActivity() {

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (needData()) return
        setContentView(if (Fx3d.on) R.layout.activity_home_3d else R.layout.activity_home)

        val live = findViewById<View>(R.id.tileLive)
        val films = findViewById<View>(R.id.tileFilms)
        val series = findViewById<View>(R.id.tileSeries)
        val fav = findViewById<View>(R.id.tileFav)
        val search = findViewById<View>(R.id.tileSearch)
        val settings = findViewById<View>(R.id.tileSettings)

        live.setOnClickListener { startActivity(Intent(this, LiveActivity::class.java)) }
        films.setOnClickListener { startActivity(Intent(this, VodActivity::class.java).putExtra("mode", VodActivity.MOVIES)) }
        series.setOnClickListener { startActivity(Intent(this, VodActivity::class.java).putExtra("mode", VodActivity.SERIES)) }
        fav.setOnClickListener { startActivity(Intent(this, VodActivity::class.java).putExtra("mode", VodActivity.FAV)) }
        search.setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }
        settings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        if (Fx3d.on) {
            // Carrousel : Direct au centre dès l'ouverture
            val scroll = findViewById<android.widget.HorizontalScrollView>(R.id.homeScroll)
            Fx3d.row(scroll, listOf(search, films, live, series, fav, settings))
            scroll.post { scroll.scrollTo((live.parent as View).left + (live.left + live.right) / 2 - scroll.width / 2, 0) }
        } else {
            live.focusScale(1.02f)
            listOf(films, series, fav, search, settings).forEach { it.focusScale(1.04f) }
            popIn(live, films, series, fav, search, settings)
        }
        pulse(findViewById(R.id.liveDot))
        equalizer(findViewById(R.id.eq1), findViewById(R.id.eq2), findViewById(R.id.eq3))
        swing(findViewById(R.id.fanA), -9f, -13f)
        swing(findViewById(R.id.fanB), 7f, 11f)

        live.requestFocus()

        // Dernier plantage : affiché une fois pour pouvoir le photographier et nous l'envoyer
        val crash = App.crashFile(this)
        if (crash.exists()) {
            val txt = crash.readText().lines().take(14).joinToString("\n"); crash.delete()
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("FK PLAYER s'est fermé la dernière fois")
                .setMessage("Prends une photo de ce message pour la correction :\n\n$txt").setPositiveButton("OK", null).showTv()
        }

        // Ouvert depuis les listes gardées sur la box : mise à jour discrète en arrière-plan
        // + vérification que le serveur répond toujours : nouvelle adresse du fournisseur → bascule toute seule
        if (intent.getBooleanExtra("refresh", false)) {
            lifecycleScope.launch {
                val before = Prefs.server
                try {
                    if (Repo.cacheAge() > 6 * 3600_000L || Repo.serverMoved()) { Repo.load(preferCache = false) { }; refreshTexts() }
                } catch (_: Exception) { }
                if (Prefs.server != before) toast("Nouvelle adresse du serveur : ${Prefs.server}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTexts()
        resumeRow()
    }

    /* ---------- Continuer à regarder ---------- */

    private var resumeAd: Adapter<Pair<Item, String?>>? = null

    private fun resumeRow() {
        if (!Repo.loaded) return
        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.resumeRow) ?: return
        val list = Resume.items()
        val vis = if (list.isEmpty()) View.GONE else View.VISIBLE
        rv.visibility = vis; findViewById<View>(R.id.resumeTitle)?.visibility = vis
        val ad = resumeAd ?: Adapter<Pair<Item, String?>>(R.layout.item_poster, 1.07f) { v, (it, _), _ ->
            v.layoutParams = v.layoutParams.apply { width = dp(96) }
            v.findViewById<TextView>(R.id.name).text = it.name
            v.findViewById<TextView>(R.id.ini).text = it.name.take(40)
            val img = v.findViewById<RatioImageView>(R.id.img)
            if (it.icon != null) img.load(it.icon) { crossfade(true) } else img.setImageDrawable(null)
            v.findViewById<View>(R.id.badge).visibility = View.GONE
            v.findViewById<View>(R.id.fav).visibility = View.GONE
            v.findViewById<android.widget.ProgressBar>(R.id.resume).apply { progress = Prefs.progress(it.key); visibility = View.VISIBLE }
        }.also { a ->
            a.focusTarget = R.id.card
            a.onClick = { (it, series), _ ->
                // Épisode : on ouvre la fiche de la série (elle se place sur l'épisode commencé) ; sinon lecture directe
                val serie = series?.let { n -> Repo.series.firstOrNull { s -> s.name == n } }
                when {
                    it.type != Type.EPISODE -> open(listOf(it), 0)
                    serie != null -> startActivity(Intent(this, SeriesActivity::class.java).putExtra("id", serie.id))
                    else -> playChoice(listOf(it), 0, series)
                }
            }
            rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
            rv.fast(); rv.adapter = a; resumeAd = a
        }
        ad.items = list
    }

    private fun refreshTexts() {
        if (!Repo.loaded) return
        val nf = java.text.NumberFormat.getIntegerInstance(java.util.Locale.FRANCE)
        findViewById<TextView>(R.id.liveCount)?.text = "DIRECT · ${nf.format(Repo.live.size)} chaînes"
        findViewById<TextView>(R.id.liveLast)?.text =
            if (Prefs.lastLiveName.isNotBlank()) "Dernière chaîne · ${Prefs.lastLiveName}" else "Toutes tes chaînes"
        findViewById<TextView>(R.id.liveBtn)?.text = if (Prefs.lastLiveName.isNotBlank()) "Reprendre le direct" else "Regarder le direct"

        val resume = Prefs.resumeKeys().firstNotNullOfOrNull { k -> if (k.startsWith("${Type.MOVIE}:")) Repo.byKey(k) else null }
        findViewById<TextView>(R.id.filmsSub)?.text = resume?.let { "Reprendre : ${it.name}" } ?: "${nf.format(Repo.vod.size)} films"
        findViewById<TextView>(R.id.seriesSub)?.text = "${nf.format(Repo.series.size)} séries"
        val favs = Prefs.favs()
        val ch = favs.count { it.startsWith("${Type.LIVE}:") }
        // Séries de Ma liste avec de nouveaux épisodes depuis la dernière visite
        val news = favs.count { k -> k.startsWith("${Type.SERIES}:") && Repo.byKey(k)?.let { Prefs.hasNew(it) } == true }
        findViewById<TextView>(R.id.favSub)?.text = "${favs.size - ch} titres · $ch chaînes" + if (news > 0) " · $news nouveauté" + (if (news > 1) "s" else "") else ""

        val chip = when {
            Repo.expDate != null -> {
                val days = ((Repo.expDate!! - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
                "Abonnement · $days jours restants"
            }
            Prefs.mode == "m3u" -> "Playlist M3U"
            else -> "Abonnement illimité"
        }
        setTop("FK PLAYER", chip)
    }

    private fun swing(v: View, a: Float, b: Float) {
        ObjectAnimator.ofFloat(v, "rotation", a, b).apply {
            duration = 2500; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
        }
    }
}
