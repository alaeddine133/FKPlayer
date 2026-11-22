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
import coil.load

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
        query.requestFocus()
    }

    private fun run() {
        val q = query.text.toString()
        val res = Repo.search(q, types)
        ad.items = res
        empty.visibility = if (res.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (q.trim().length < 2) "Tape au moins 2 lettres" else "Aucun résultat pour « ${q.trim()} »"
    }
}
