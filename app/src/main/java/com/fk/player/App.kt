package com.fk.player

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Repo.init(this)
    }

    /** Images (logos, affiches) : cache mémoire + 150 Mo sur disque, format léger pour les box modestes. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(Repo.http)
        .crossfade(180)
        .respectCacheHeaders(false)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(150L * 1024 * 1024).build() }
        .build()
}

/* ---------- Modèles ---------- */

object Type {
    const val LIVE = 0
    const val MOVIE = 1
    const val SERIES = 2
    const val EPISODE = 3
}

data class Category(val id: String, val name: String)

data class Item(
    val type: Int,
    val id: String,
    val name: String,
    val icon: String?,
    val catId: String,
    val ext: String? = null,
    val url: String? = null,
    val num: Int = 0,
    val rating: String? = null,
    val epgId: String? = null,
    val sub: String? = null
) {
    val key: String get() = "$type:$id"
}

data class Episode(
    val id: String,
    val title: String,
    val ext: String,
    val season: Int,
    val num: Int,
    val icon: String?,
    val plot: String?
)

data class Epg(val title: String, val start: Long, val end: Long)

/** File de lecture partagée entre les écrans (évite de passer de gros objets dans les Intent). */
object PlayQueue {
    var items: List<Item> = emptyList()
    var index: Int = 0
    var seriesName: String? = null
    fun set(list: List<Item>, i: Int, series: String? = null) {
        items = list; index = i.coerceIn(0, (list.size - 1).coerceAtLeast(0)); seriesName = series
    }
}

/* ---------- Préférences ---------- */

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) {
        sp = c.getSharedPreferences("fkplayer", Context.MODE_PRIVATE)
    }

    private fun str(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun putS(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun putI(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun putB(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    // Compte : "xtream" ou "m3u" (vide = pas connecté)
    var mode: String get() = str("mode"); set(v) = putS("mode", v)
    var server: String get() = str("server"); set(v) = putS("server", v)
    var user: String get() = str("user"); set(v) = putS("user", v)
    var pass: String get() = str("pass"); set(v) = putS("pass", v)
    var m3u: String get() = str("m3u"); set(v) = putS("m3u", v)

    // Lecteur
    var liveFormat: String get() = str("fmt", "ts"); set(v) = putS("fmt", v)
    var buffer: Int get() = sp.getInt("buf", 1); set(v) = putI("buf", v)
    var ua: Int get() = sp.getInt("ua", 0); set(v) = putI("ua", v)
    var resize: Int get() = sp.getInt("resize", 0); set(v) = putI("resize", v)
    var audioLang: String get() = str("alang", "fr"); set(v) = putS("alang", v)
    var subtitles: Boolean get() = sp.getBoolean("subs", false); set(v) = putB("subs", v)
    var reconnect: Boolean get() = sp.getBoolean("reco", true); set(v) = putB("reco", v)
    var autoNext: Boolean get() = sp.getBoolean("next", true); set(v) = putB("next", v)
    var frFirst: Boolean get() = sp.getBoolean("frfirst", true); set(v) = putB("frfirst", v)

    // Dernière chaîne regardée
    var lastLiveId: String get() = str("lastId"); set(v) = putS("lastId", v)
    var lastLiveName: String get() = str("lastName"); set(v) = putS("lastName", v)
    var lastLiveCat: String get() = str("lastCat"); set(v) = putS("lastCat", v)

    // Favoris
    fun favs(): Set<String> = sp.getStringSet("favs", emptySet()) ?: emptySet()
    fun isFav(key: String) = key in favs()
    fun toggleFav(key: String): Boolean {
        val s = favs().toMutableSet()
        val added = if (key in s) { s.remove(key); false } else { s.add(key); true }
        sp.edit().putStringSet("favs", s).apply()
        return added
    }

    // Reprise de lecture (position + durée en ms)
    fun pos(key: String): Long = sp.getLong("p_$key", 0L)
    fun dur(key: String): Long = sp.getLong("d_$key", 0L)
    fun savePos(key: String, pos: Long, dur: Long) {
        if (dur > 0 && (pos < 30_000 || pos > dur - 90_000)) clearPos(key)
        else {
            sp.edit().putLong("p_$key", pos).putLong("d_$key", dur).putLong("t_$key", System.currentTimeMillis()).apply()
            resumeKeys().drop(100).forEach { clearPos(it) }
        }
    }
    fun clearPos(key: String) = sp.edit().remove("p_$key").remove("d_$key").remove("t_$key").apply()
    fun progress(key: String): Int {
        val d = dur(key); return if (d > 0) ((pos(key) * 1000) / d).toInt() else 0
    }
    /** Clés des contenus en cours, du plus récent au plus ancien. */
    fun resumeKeys(): List<String> = sp.all.keys.filter { it.startsWith("t_") }
        .sortedByDescending { sp.getLong(it, 0L) }.map { it.removePrefix("t_") }

    fun logout() = sp.edit().remove("mode").apply()
}
