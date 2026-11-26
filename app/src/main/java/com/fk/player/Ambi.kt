package com.fk.player

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.widget.ImageView

/**
 * Thème « Ambilight » : le fond de l'écran prend doucement la couleur de l'affiche sélectionnée,
 * comme une TV qui éclaire le mur derrière elle.
 */
object Ambi {
    val on: Boolean get() = Prefs.theme == 2

    private const val DARK = 0xFF0A0806.toInt()
    private const val START = 0xFF3B2A63.toInt()  // violet doux tant qu'aucune affiche n'est choisie

    private class State(val bg: GradientDrawable, var color: Int, var anim: ValueAnimator? = null)

    /** Installe le fond en dégradé ; chaque affiche qui reçoit le focus lui donne sa couleur. */
    fun install(a: Activity, root: View) {
        val g = GradientDrawable().apply {
            setGradientType(GradientDrawable.RADIAL_GRADIENT)
            setGradientCenter(0.3f, 0.35f)
            setGradientRadius(a.resources.displayMetrics.widthPixels * 0.95f)
            setColors(intArrayOf(START, DARK))
        }
        root.background = g
        root.setTag(R.id.ambi, State(g, START))
        // Les halos violet et cyan du thème Mosaïque brouilleraient la couleur
        listOf(R.id.aur1, R.id.aur2).forEach { a.findViewById<View>(it)?.visibility = View.GONE }
        a.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            now?.findViewById<ImageView>(R.id.img)?.let { tint(root, it.drawable) }
        }
    }

    /** Fond teinté par une image (fiche film / série) ; sans effet si le thème n'est pas actif. */
    fun tint(root: View?, d: Drawable?) {
        val st = root?.getTag(R.id.ambi) as? State ?: return
        val to = colorOf(d) ?: return
        if (to == st.color) return
        st.anim?.cancel()
        st.anim = ValueAnimator.ofObject(ArgbEvaluator(), st.color, to).apply {
            duration = 450
            addUpdateListener { val c = it.animatedValue as Int; st.color = c; st.bg.setColors(intArrayOf(c, DARK)) }
            start()
        }
    }

    fun tint(a: Activity, d: Drawable?) = tint(a.findViewById<android.view.ViewGroup>(android.R.id.content)?.getChildAt(0), d)

    /** Couleur moyenne de l'affiche, rendue assez vive et assez sombre pour un fond (null si l'image n'est pas prête). */
    private fun colorOf(d: Drawable?): Int? {
        val src = (d as? BitmapDrawable)?.bitmap ?: return null
        // Les images en mémoire graphique ne se lisent pas pixel par pixel : copie légère
        val b = if (Build.VERSION.SDK_INT >= 26 && src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val s = Bitmap.createScaledBitmap(b, 8, 8, true)
        var r = 0; var g = 0; var bl = 0
        for (y in 0 until 8) for (x in 0 until 8) { val p = s.getPixel(x, y); r += Color.red(p); g += Color.green(p); bl += Color.blue(p) }
        val hsv = FloatArray(3)
        Color.colorToHSV(Color.rgb(r / 64, g / 64, bl / 64), hsv)
        if (hsv[1] > 0.12f) hsv[1] = hsv[1].coerceAtLeast(0.55f)  // une affiche presque grise reste grise
        hsv[2] = 0.5f
        return Color.HSVToColor(hsv)
    }
}
