package com.fk.player

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ---------- Outils ---------- */

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

/** Fenêtre à boutons : sur TV, rien n'est surligné à l'ouverture si on ne place pas le focus soi-même. */
fun androidx.appcompat.app.AlertDialog.Builder.showTv(button: Int = android.content.DialogInterface.BUTTON_POSITIVE) =
    show().also { it.getButton(button)?.requestFocus() }

private val hm = SimpleDateFormat("HH:mm", Locale.FRANCE)
fun hhmm(t: Long): String = hm.format(Date(t))

fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
}

fun initials(name: String): String {
    val w = name.replace(Regex("[^\\p{L}\\p{N} ]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        w.isEmpty() -> "TV"
        w.size == 1 -> w[0].take(2).uppercase()
        else -> (w[0].take(1) + w[1].take(1)).uppercase()
    }
}

/** Fait apparaître les vues en cascade (effet « pop » de la maquette). */
fun popIn(vararg views: View) {
    views.forEachIndexed { i, v ->
        v.alpha = 0f; v.translationY = v.context.dp(18).toFloat(); v.scaleX = 0.94f; v.scaleY = 0.94f
        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(60L + i * 110L).setDuration(520).setInterpolator(DecelerateInterpolator(2f)).start()
    }
}

fun pulse(v: View) {
    ObjectAnimator.ofFloat(v, "alpha", 1f, 0.3f).apply {
        duration = 650; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
    }
}

fun equalizer(vararg bars: View) {
    bars.forEachIndexed { i, b ->
        b.pivotY = b.layoutParams.height.toFloat()
        ObjectAnimator.ofFloat(b, "scaleY", 0.35f, 1f).apply {
            duration = 450; startDelay = i * 150L; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
        }
    }
}

fun View.focusScale(scale: Float) {
    setOnFocusChangeListener { v, f -> v.animate().scaleX(if (f) scale else 1f).scaleY(if (f) scale else 1f).setDuration(160).start() }
}

/** Réglages de fluidité pour les longues listes. */
fun RecyclerView.fast(): RecyclerView {
    setHasFixedSize(true)
    setItemViewCacheSize(24)
    (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
    return this
}

/* ---------- Image à proportions fixes (affiches 2:3) ---------- */

class RatioImageView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : AppCompatImageView(c, a) {
    var ratio = 1.5f
        set(v) { field = v; requestLayout() }
    override fun onMeasure(w: Int, h: Int) {
        val width = View.MeasureSpec.getSize(w)
        setMeasuredDimension(width, (width * ratio).toInt())
    }
}

/* ---------- Adaptateur générique pour toutes les listes ---------- */

class VH(v: View) : RecyclerView.ViewHolder(v)

class Adapter<T>(
    private val layout: Int,
    private val scale: Float = 1f,
    private val bind: (View, T, Int) -> Unit
) : RecyclerView.Adapter<VH>() {
    var onClick: ((T, Int) -> Unit)? = null
    var onLong: ((T, Int) -> Unit)? = null
    var onFocus: ((T, Int) -> Unit)? = null
    /** Vue qui reçoit le focus dans l'élément (par défaut : la racine). */
    var focusTarget: Int = 0

    var items: List<T> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    override fun getItemCount() = items.size
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(layout, p, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val root = h.itemView
        val v: View = if (focusTarget != 0) root.findViewById<View>(focusTarget) ?: root else root
        bind(root, items[pos], pos)
        v.setOnClickListener { val p = h.bindingAdapterPosition; if (p in items.indices) onClick?.invoke(items[p], p) }
        v.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p in items.indices && onLong != null) { onLong?.invoke(items[p], p); true } else false
        }
        v.setOnKeyListener { _, code, ev ->
            if (code == android.view.KeyEvent.KEYCODE_MENU && ev.action == android.view.KeyEvent.ACTION_UP && onLong != null) {
                val p = h.bindingAdapterPosition; if (p in items.indices) onLong?.invoke(items[p], p); true
            } else false
        }
        v.setOnFocusChangeListener { view, f ->
            if (scale != 1f) view.animate().scaleX(if (f) scale else 1f).scaleY(if (f) scale else 1f).setDuration(150).start()
            if (f) { val p = h.bindingAdapterPosition; if (p in items.indices) onFocus?.invoke(items[p], p) }
        }
    }
}

/* ---------- Base de tous les écrans ---------- */

open class BaseActivity : AppCompatActivity() {
    val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            (findViewById<View>(R.id.clock) as? TextView)?.text = hhmm(System.currentTimeMillis())
            ui.postDelayed(this, 20_000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Prefs.init(applicationContext)
        Repo.init(applicationContext)
    }

    /** Le lecteur et l'écran de démarrage n'affichent pas la fenêtre de mise à jour (pas d'interruption de la vidéo). */
    open val checksUpdates = true

    override fun onResume() {
        super.onResume(); ui.post(tick); aurora()
        if (checksUpdates && Repo.loaded) Updater.check(this)
    }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }
    override fun onDestroy() { super.onDestroy(); ui.removeCallbacksAndMessages(null) }

    /** Si l'appli a été fermée par le système, on recharge les listes depuis l'écran de démarrage. */
    fun needData(): Boolean {
        if (Repo.loaded) return false
        startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish(); return true
    }

    fun setTop(title: String, chip: String? = null) {
        (findViewById<View>(R.id.title) as? TextView)?.text = title
        (findViewById<View>(R.id.chip) as? TextView)?.let { c ->
            if (chip.isNullOrBlank()) c.visibility = View.GONE else { c.text = chip; c.visibility = View.VISIBLE }
        }
    }

    private fun aurora() {
        listOf(R.id.aur1 to 1f, R.id.aur2 to -1f).forEach { (id, dir) ->
            val v = findViewById<View>(id) ?: return@forEach
            if (v.getTag(R.id.aur1) != null) return@forEach
            v.setTag(R.id.aur1, true)
            ObjectAnimator.ofFloat(v, "translationX", 0f, dp(120) * dir).apply {
                duration = 9000; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
            }
            ObjectAnimator.ofFloat(v, "translationY", 0f, dp(60) * dir).apply {
                duration = 9000; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
            }
        }
    }

    fun play(list: List<Item>, index: Int, series: String? = null) {
        PlayQueue.set(list, index, series)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    /** Contenu déjà commencé : on demande « reprendre » ou « depuis le début ». */
    fun playChoice(list: List<Item>, i: Int, series: String? = null) {
        val it = list[i]; val p = Prefs.pos(it.key)
        if (p <= 0) { play(list, i, series); return }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(it.name)
            .setItems(arrayOf("▶  Reprendre à ${duration(p)}", "↺  Depuis le début")) { _, w ->
                if (w == 1) Prefs.clearPos(it.key)
                play(list, i, series)
            }.show()
    }

    /** Bande-annonce dans l'appli YouTube de la box. */
    fun trailer(id: String) {
        val tries = listOf("vnd.youtube:$id", "https://www.youtube.com/watch?v=$id")
        for (u in tries) try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u))); return } catch (_: Exception) { }
        toast("Installe l'appli YouTube pour voir les bandes-annonces")
    }

    /** Rangée « Titres similaires » : affichée seulement si des titres vraiment proches sont trouvés. */
    fun similarRow(rv: RecyclerView, title: View, of: Item, width: Int, info: Repo.Info?) {
        rv.visibility = View.GONE; title.visibility = View.GONE
        lifecycleScope.launch {
            val list = try { Repo.similar(of, info) } catch (e: Exception) { emptyList() }
            if (isFinishing || isDestroyed || list.isEmpty()) return@launch
            fillSimilar(rv, list, width)
            rv.visibility = View.VISIBLE; title.visibility = View.VISIBLE
        }
    }

    private fun fillSimilar(rv: RecyclerView, list: List<Item>, width: Int) {
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
        val ad = Adapter<Item>(R.layout.item_poster, 1.07f) { v, x, _ ->
            v.layoutParams = v.layoutParams.apply { this.width = dp(width) }
            v.findViewById<TextView>(R.id.name).text = x.name
            v.findViewById<TextView>(R.id.ini).text = x.name.take(40)
            val img = v.findViewById<RatioImageView>(R.id.img)
            if (x.icon != null) img.load(x.icon) { crossfade(true) } else img.setImageDrawable(null)
            v.findViewById<TextView>(R.id.badge).let { b -> if (x.rating != null) { b.text = "★ ${x.rating}"; b.visibility = View.VISIBLE } else b.visibility = View.GONE }
        }
        ad.focusTarget = R.id.card
        ad.onClick = { _, i -> open(list, i); finish() }
        ad.items = list
        rv.adapter = ad
    }

    /** Ouvre un contenu : chaîne/épisode → lecteur ; film → fiche film ; série → fiche série. */
    fun open(list: List<Item>, index: Int) {
        val it = list[index]
        when (it.type) {
            Type.SERIES -> startActivity(Intent(this, SeriesActivity::class.java).putExtra("id", it.id))
            Type.MOVIE -> if (Repo.byKey(it.key) != null) startActivity(Intent(this, DetailActivity::class.java).putExtra("key", it.key)) else playChoice(listOf(it), 0)
            Type.LIVE -> play(list.filter { x -> x.type == Type.LIVE }, list.filter { x -> x.type == Type.LIVE }.indexOf(it))
            else -> play(listOf(it), 0)
        }
    }

    fun toggleFav(it: Item, refresh: () -> Unit) {
        val added = Prefs.toggleFav(it.key)
        toast(if (added) "Ajouté à Ma liste" else "Retiré de Ma liste")
        refresh()
    }
}
