package com.fk.player

import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Thème « Motion 3D » : l'élément choisi vient au centre, s'avance et s'éclaire ;
 * ses voisins s'inclinent et s'effacent (carrousel à l'horizontale, tambour à la verticale).
 */
object Fx3d {
    val on: Boolean get() = Prefs.theme == 1

    /** Inclinaison selon la distance au centre (d de -1 à 1). */
    private fun tilt(v: View, d: Float, horizontal: Boolean, angle: Float) {
        val a = abs(d)
        v.cameraDistance = 12000f * v.resources.displayMetrics.density
        if (horizontal) v.rotationY = -d * angle else v.rotationX = d * angle
        val s = 1.06f - 0.22f * a
        v.scaleX = s; v.scaleY = s
        v.alpha = 1f - 0.6f * a
        v.translationZ = (1f - a) * 24f
    }

    /** Carrousel (horizontal) ou tambour (vertical) sur une liste. Sans effet avec le thème Mosaïque. */
    fun list(rv: RecyclerView, horizontal: Boolean, angle: Float = if (horizontal) 30f else 22f) {
        if (!on) return
        rv.clipToPadding = false; rv.clipChildren = false
        (rv.parent as? ViewGroup)?.clipChildren = false
        // Marge pour que le premier et le dernier élément puissent aussi venir au centre
        // (recalculée quand la taille change : le panneau du lecteur est caché au départ)
        rv.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l == orr - ol && b - t == ob - ot) return@addOnLayoutChangeListener
            rv.post {
                val pad = ((if (horizontal) rv.width else rv.height) * 0.36f).toInt()
                // Carrousel : centré aussi en hauteur
                val top = rv.getChildAt(0)?.height?.let { ((rv.height - it) / 2).coerceAtLeast(0) } ?: rv.paddingTop
                if (horizontal) rv.setPadding(pad, top, pad, 0) else rv.setPadding(rv.paddingLeft, pad, rv.paddingRight, pad)
            }
        }
        val update = {
            val mid = (if (horizontal) rv.width else rv.height) / 2f
            if (mid > 0) for (i in 0 until rv.childCount) {
                val c = rv.getChildAt(i)
                val center = if (horizontal) (c.left + c.right) / 2f else (c.top + c.bottom) / 2f
                tilt(c, ((center - mid) / (mid * 1.1f)).coerceIn(-1f, 1f), horizontal, angle)
            }
        }
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) = update()
        })
        rv.viewTreeObserver.addOnGlobalLayoutListener { update() }
        rv.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            val item = now?.let { rv.findContainingItemView(it) } ?: return@addOnGlobalFocusChangeListener
            if (horizontal) rv.smoothScrollBy((item.left + item.right) / 2 - rv.width / 2, 0)
            else rv.smoothScrollBy(0, (item.top + item.bottom) / 2 - rv.height / 2)
        }
    }

    /** Fiche film / série : l'affiche flotte en relief, tournée vers le texte. */
    fun cover(img: View) {
        if (!on) return
        val card = img.parent as? View ?: img
        card.cameraDistance = 12000f * card.resources.displayMetrics.density
        card.rotationY = 16f
        card.translationZ = 30f
    }

    /** Même effet sur une rangée de tuiles fixes (Accueil). */
    fun row(scroll: HorizontalScrollView, tiles: List<View>) {
        fun centerOf(t: View) = (t.parent as View).left + (t.left + t.right) / 2
        val update = {
            val half = scroll.width / 2f
            if (half > 0) tiles.forEach { t -> tilt(t, ((centerOf(t) - scroll.scrollX - half) / (half * 1.1f)).coerceIn(-1f, 1f), true, 34f) }
        }
        scroll.viewTreeObserver.addOnScrollChangedListener { update() }
        scroll.viewTreeObserver.addOnGlobalLayoutListener { update() }
        tiles.forEach { t -> t.setOnFocusChangeListener { v, f -> if (f) scroll.smoothScrollTo(centerOf(v) - scroll.width / 2, 0) } }
    }
}
