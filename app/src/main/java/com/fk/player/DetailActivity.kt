package com.fk.player

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch

/** Fiche film : résumé, acteurs, bande-annonce, reprendre / depuis le début, titres similaires. */
class DetailActivity : BaseActivity() {

    private lateinit var movie: Item

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (needData()) return
        setContentView(R.layout.activity_detail)
        movie = Repo.byKey(intent.getStringExtra("key") ?: "") ?: run { finish(); return }

        findViewById<TextView>(R.id.title).text = movie.name
        findViewById<TextView>(R.id.meta).text = movie.rating?.let { "★ $it" } ?: ""
        movie.icon?.let { findViewById<ImageView>(R.id.cover).load(it) { crossfade(true) }; findViewById<ImageView>(R.id.backdrop).load(it) }

        val favBtn = findViewById<TextView>(R.id.favBtn)
        fun favLabel() { favBtn.text = if (Prefs.isFav(movie.key)) "♥  Dans Ma liste" else "♡  Ma liste" }
        favLabel()
        favBtn.setOnClickListener { Prefs.toggleFav(movie.key); favLabel() }
        findViewById<View>(R.id.btnResume).setOnClickListener { play(listOf(movie), 0) }
        findViewById<View>(R.id.btnStart).setOnClickListener { Prefs.clearPos(movie.key); play(listOf(movie), 0) }

        findViewById<View>(R.id.btnResume).requestFocus()

        lifecycleScope.launch {
            val i = Repo.vodInfo(movie.id)
            // Genres, acteurs et réalisateur de la fiche servent à trouver les titres proches
            similarRow(findViewById(R.id.similar), findViewById(R.id.simTitle), movie, 110, i)
            i ?: return@launch
            findViewById<TextView>(R.id.plot).text = i.plot ?: ""
            findViewById<TextView>(R.id.meta).text = listOfNotNull(i.year, i.genre, i.duration, movie.rating?.let { "★ $it" }).joinToString("  ·  ")
            findViewById<TextView>(R.id.cast).text = listOfNotNull(i.director?.let { "Réalisation : $it" }, i.cast?.let { "Avec : $it" }).joinToString("\n")
            if (movie.icon == null) i.cover?.let { findViewById<ImageView>(R.id.cover).load(it) }
            i.backdrop?.let { findViewById<ImageView>(R.id.backdrop).load(it) { crossfade(true) } }
            i.trailer?.let { t -> findViewById<View>(R.id.btnTrailer).apply { visibility = View.VISIBLE; setOnClickListener { trailer(t) } } }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::movie.isInitialized) return
        // Au retour du lecteur : « Reprendre à … » si le film a été commencé
        val p = Prefs.pos(movie.key)
        findViewById<TextView>(R.id.btnResume).text = if (p > 0) "▶  Reprendre à ${duration(p)}" else "▶  Lecture"
        findViewById<View>(R.id.btnStart).visibility = if (p > 0) View.VISIBLE else View.GONE
    }
}
