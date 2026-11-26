package com.fk.player

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** Films, Séries ou Ma liste : catégories à gauche, grille d'affiches à droite. */
class VodActivity : BaseActivity() {

    companion object {
        const val MOVIES = 0; const val SERIES = 1; const val FAV = 2
        private const val K_FAV = "__fav"; private const val K_RESUME = "__resume"; private const val K_ALL = "__all"
        private const val K_CH = "__ch"; private const val K_FILMS = "__films"; private const val K_SER = "__ser"
    }

    private var mode = MOVIES
    private lateinit var catAd: Adapter<Category>
    private lateinit var gridAd: Adapter<Item>
    private lateinit var grid: RecyclerView
    private lateinit var catList: RecyclerView

    /** Motion 3D : ▲ depuis une affiche ramène directement sur la catégorie en cours (sinon ◀ x fois le carrousel). */
    override fun dispatchKeyEvent(e: android.view.KeyEvent): Boolean {
        if (Fx3d.on && e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && ::grid.isInitialized && grid.hasFocus()) {
            if (e.action == android.view.KeyEvent.ACTION_DOWN) {
                val lm = catList.layoutManager as LinearLayoutManager
                lm.findViewByPosition(selCat)?.requestFocus() ?: run {
                    catList.scrollToPosition(selCat)
                    catList.post { lm.findViewByPosition(selCat)?.requestFocus() }
                }
            }
            return true
        }
        return super.dispatchKeyEvent(e)
    }
    private lateinit var empty: TextView
    private var cats: List<Category> = emptyList()
    private val counts = HashMap<String, Int>()
    private var selCat = 0
    private var pending: Runnable? = null
    private var ready = false  // le focus posé automatiquement à l'ouverture ne change pas de catégorie

    private val source: List<Item> get() = if (mode == SERIES) Repo.series else Repo.vod

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (needData()) return
        setContentView(R.layout.activity_vod)
        mode = intent.getIntExtra("mode", MOVIES)
        grid = findViewById(R.id.grid); empty = findViewById(R.id.empty)

        when (mode) {
            MOVIES -> setTop("Films", "${Repo.vod.size} films")
            SERIES -> setTop("Séries", "${Repo.series.size} séries")
            else -> setTop("Ma liste", "Appui long ou Menu pour retirer")
        }
        byCat.forEach { (k, v) -> counts[k] = v.size }
        cats = when (mode) {
            MOVIES -> listOf(Category(K_FAV, "Ma liste"), Category(K_RESUME, "Reprendre"), Category(K_ALL, "Tous les films")) + Repo.vodCats
            SERIES -> listOf(Category(K_FAV, "Ma liste"), Category(K_ALL, "Toutes les séries")) + Repo.serCats
            else -> listOf(Category(K_FILMS, "Films"), Category(K_SER, "Séries"), Category(K_CH, "Chaînes"))
        }

        val sortBtn = findViewById<TextView>(R.id.sortBtn)
        sortBtn.text = "⇅ " + Repo.sortNames[Prefs.sort]
        sortBtn.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Trier par")
                .setSingleChoiceItems(Repo.sortNames, Prefs.sort) { d, w ->
                    d.dismiss()
                    if (w != Prefs.sort) { Prefs.sort = w; sortBtn.text = "⇅ " + Repo.sortNames[w]; show() }
                }.show()
        }

        catList = findViewById<RecyclerView>(R.id.cats).fast()
        grid.fast()
        catList.layoutManager = LinearLayoutManager(this)
        catAd = Adapter(R.layout.item_cat, 1.03f) { v, c, i ->
            v.isActivated = i == selCat
            v.findViewById<TextView>(R.id.name).text = c.name
            val n = if (c.id.startsWith("__")) itemsFor(c).size else counts[c.id] ?: 0
            v.findViewById<TextView>(R.id.count).text = if (n > 0) n.toString() else ""
        }
        catAd.onFocus = { _, i ->
            if (ready) {
                pending?.let { ui.removeCallbacks(it) }
                pending = Runnable { select(i) }.also { ui.postDelayed(it, 300) }
            }
        }
        catAd.onClick = { _, i -> select(i); grid.post { grid.getChildAt(0)?.findViewById<View>(R.id.card)?.requestFocus() } }
        catList.adapter = catAd

        // Colonnes adaptées à la largeur de l'écran
        val span = (resources.displayMetrics.widthPixels / resources.displayMetrics.density / 140).toInt().coerceIn(4, 8) - 1
        // Motion 3D : une rangée d'affiches en carrousel au lieu de la grille
        grid.layoutManager = if (Fx3d.on) LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false) else GridLayoutManager(this, span.coerceAtLeast(3))
        gridAd = Adapter(R.layout.item_poster, 1.07f) { v, it, _ ->
            if (Fx3d.on) v.layoutParams = v.layoutParams.apply { width = dp(170) }
            bindPoster(v, it)
        }
        gridAd.focusTarget = R.id.card
        gridAd.onClick = { _, i -> open(gridAd.items, i) }
        gridAd.onLong = { it, _ -> toggleFav(it) { refresh() } }
        grid.adapter = gridAd
        Fx3d.list(grid, horizontal = true)
        Fx3d.list(catList, horizontal = false)
        // Ma liste vide → on ouvre « Tous »
        selCat = if (mode != FAV && itemsFor(cats[0]).isEmpty()) (if (mode == MOVIES && itemsFor(cats[1]).isNotEmpty()) 1 else cats.indexOfFirst { it.id == K_ALL }) else 0
        if (mode == FAV) selCat = cats.indexOfFirst { itemsFor(it).isNotEmpty() }.coerceAtLeast(0)
        catAd.items = cats
        show()
        // On attend que la liste soit dessinée avant de placer le focus sur la bonne catégorie
        ui.postDelayed({
            catList.getChildAt(selCat)?.requestFocus()
            ui.postDelayed({ ready = true }, 400)
        }, 350)
        popIn(catList, grid)
    }

    private val byCat: Map<String, List<Item>> get() = if (mode == SERIES) Repo.serByCat else Repo.vodByCat

    /** Favoris d'un type, retrouvés directement par leur clé (instantané même avec un gros catalogue). */
    private fun favs(vararg types: Int): List<Item> =
        Prefs.favs().mapNotNull { Repo.byKey(it) }.filter { it.type in types }.sortedBy { it.name.lowercase() }

    private fun itemsFor(c: Category): List<Item> = when (c.id) {
        K_FAV -> if (mode == SERIES) favs(Type.SERIES, Type.EPISODE) else favs(Type.MOVIE)
        K_RESUME -> Prefs.resumeKeys().filter { it.startsWith("${Type.MOVIE}:") }.mapNotNull { Repo.byKey(it) }
        K_ALL -> source
        K_FILMS -> favs(Type.MOVIE)
        K_SER -> favs(Type.SERIES, Type.EPISODE)
        K_CH -> favs(Type.LIVE)
        else -> byCat[c.id] ?: emptyList()
    }

    private fun select(i: Int) {
        if (i == selCat) return
        val old = selCat; selCat = i
        catAd.notifyItemChanged(old); catAd.notifyItemChanged(i)
        show()
    }

    // Dernier tri calculé par catégorie : changer de catégorie au focus reste instantané
    private val sortCache = HashMap<String, Pair<List<Item>, List<Item>>>()

    private fun sortedFor(c: Category): List<Item> {
        val raw = itemsFor(c)
        if (c.id == K_RESUME) return raw  // « Reprendre » garde l'ordre du dernier regardé
        val k = "${c.id}|${Prefs.sort}"
        sortCache[k]?.let { (src, out) -> if (src == raw) return out }
        return Repo.sorted(raw, Prefs.sort).also { sortCache[k] = raw to it }
    }

    private fun show() {
        val list = cats.getOrNull(selCat)?.let { sortedFor(it) } ?: emptyList()
        gridAd.items = list
        grid.scrollToPosition(0)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when (cats.getOrNull(selCat)?.id) {
            K_FAV, K_FILMS, K_SER, K_CH -> "Rien ici pour l'instant.\nAppui long (ou touche Menu) sur un titre pour l'ajouter."
            K_RESUME -> "Aucun film en cours."
            else -> "Aucun contenu dans cette catégorie."
        }
    }

    private fun refresh() {
        val keepFocus = grid.focusedChild
        if (cats.getOrNull(selCat)?.id in setOf(K_FAV, K_FILMS, K_SER, K_CH)) show() else gridAd.notifyDataSetChanged()
        catAd.notifyDataSetChanged()
        if (keepFocus == null) grid.post { grid.getChildAt(0)?.findViewById<View>(R.id.card)?.requestFocus() }
    }

    private fun bindPoster(v: View, it: Item) {
        val img = v.findViewById<RatioImageView>(R.id.img)
        img.ratio = if (it.type == Type.LIVE) 0.75f else 1.5f
        img.scaleType = if (it.type == Type.LIVE) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        v.findViewById<TextView>(R.id.ini).text = it.name.take(40)
        v.findViewById<TextView>(R.id.name).text = it.name
        if (it.icon != null) img.load(it.icon) { crossfade(true); listener(onError = { _, _ -> img.setImageDrawable(null) }) } else img.setImageDrawable(null)
        val badge = v.findViewById<TextView>(R.id.badge)
        if (it.rating != null) { badge.text = "★ ${it.rating}"; badge.visibility = View.VISIBLE } else badge.visibility = View.GONE
        v.findViewById<View>(R.id.fav).visibility = if (Prefs.isFav(it.key)) View.VISIBLE else View.GONE
        val pr = v.findViewById<ProgressBar>(R.id.resume)
        val p = Prefs.progress(it.key)
        if (p > 0) { pr.progress = p; pr.visibility = View.VISIBLE } else pr.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (::gridAd.isInitialized) { gridAd.notifyItemRangeChanged(0, gridAd.itemCount); catAd.notifyItemRangeChanged(0, catAd.itemCount) }
    }
}
