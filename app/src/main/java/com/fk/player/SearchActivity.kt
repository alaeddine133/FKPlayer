package com.fk.player

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.launch

/** Recherche instantanée dans les chaînes, films et séries (sans accents, sans majuscules). */
class SearchActivity : BaseActivity() {

    private lateinit var ad: Adapter<Item>
    private lateinit var empty: TextView
    private lateinit var query: EditText
    private val types = mutableSetOf(Type.LIVE, Type.MOVIE, Type.SERIES)
    private var pending: Runnable? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (needData()) return
        setContentView(R.layout.activity_search)
        setTop("Recherche")
        empty = findViewById(R.id.empty); query = findViewById(R.id.query)

        val grid = findViewById<RecyclerView>(R.id.grid)
        val span = (resources.displayMetrics.widthPixels / resources.displayMetrics.density / 130).toInt().coerceIn(4, 9)
        grid.layoutManager = GridLayoutManager(this, span)
        ad = Adapter(R.layout.item_poster, 1.07f) { v, it, _ ->
            val img = v.findViewById<RatioImageView>(R.id.img)
            img.ratio = if (it.type == Type.LIVE) 0.75f else 1.5f
            img.scaleType = if (it.type == Type.LIVE) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            v.findViewById<TextView>(R.id.ini).text = it.name.take(40)
            v.findViewById<TextView>(R.id.name).text = it.name
            if (it.icon != null) img.load(it.icon) { crossfade(true) } else img.setImageDrawable(null)
            val badge = v.findViewById<TextView>(R.id.badge)
            badge.text = when (it.type) { Type.LIVE -> "DIRECT"; Type.SERIES -> "SÉRIE"; else -> "FILM" }
            badge.visibility = View.VISIBLE
            v.findViewById<View>(R.id.fav).visibility = if (Prefs.isFav(it.key)) View.VISIBLE else View.GONE
        }
        ad.focusTarget = R.id.card
        ad.onClick = { _, i -> open(ad.items, i) }
        ad.onLong = { it, _ -> toggleFav(it) { ad.notifyDataSetChanged() } }
        grid.adapter = ad

        listOf(R.id.fLive to Type.LIVE, R.id.fMovies to Type.MOVIE, R.id.fSeries to Type.SERIES).forEach { (id, t) ->
            val v = findViewById<View>(id)
            v.isActivated = true
            v.setOnClickListener {
                if (t in types && types.size > 1) types.remove(t) else types.add(t)
                v.isActivated = t in types
                run()
            }
        }

        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pending?.let { ui.removeCallbacks(it) }
                pending = Runnable { run() }.also { ui.postDelayed(it, 350) }
            }
        })
        findViewById<View>(R.id.voice).setOnClickListener { listen() }
        // Ouvert depuis la touche Recherche / micro de la télécommande : on écoute tout de suite
        if (intent.getBooleanExtra("voice", false)) listen() else query.requestFocus()
    }

    /** Recherche vocale : la box transforme la parole en texte, qui remplit la recherche. */
    private fun listen() {
        val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Dis le nom d'un film, d'une série ou d'une chaîne")
        try { @Suppress("DEPRECATION") startActivityForResult(i, VOICE) }
        catch (e: Exception) { toast("La recherche vocale n'est pas disponible sur cette box : utilise le clavier"); query.requestFocus() }
    }

    @Deprecated("API Android standard")
    override fun onActivityResult(req: Int, res: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(req, res, data)
        if (req != VOICE || res != RESULT_OK) return
        val said = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        focusResults = true
        query.setText(said); query.setSelection(said.length)
    }

    private var focusResults = false  // après une recherche vocale, le focus va directement sur le premier résultat

    /** Touche Recherche de la télécommande sur cet écran : on écoute. */
    override fun onKeyDown(code: Int, e: android.view.KeyEvent): Boolean =
        if (code == android.view.KeyEvent.KEYCODE_SEARCH) { listen(); true } else super.onKeyDown(code, e)

    private companion object { const val VOICE = 7 }

    private fun run() {
        val q = query.text.toString(); val t = types.toSet()
        // Hors du fil d'affichage : la saisie reste fluide même avec 100 000 titres
        lifecycleScope.launch {
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Repo.search(q, t) }
            if (query.text.toString() != q) return@launch  // une frappe plus récente arrive
            ad.items = res
            if (focusResults && res.isNotEmpty()) {
                focusResults = false
                val grid = findViewById<RecyclerView>(R.id.grid)
                grid.post { grid.getChildAt(0)?.findViewById<View>(R.id.card)?.requestFocus() }
            }
            empty.visibility = if (res.isEmpty()) View.VISIBLE else View.GONE
            empty.text = if (q.trim().length < 2) "Tape au moins 2 lettres" else "Aucun résultat pour « ${q.trim()} »"
        }
    }
}
