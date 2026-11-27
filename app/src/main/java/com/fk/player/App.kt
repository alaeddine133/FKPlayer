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
        // Plantage : on garde la cause pour l'afficher au prochain démarrage (pas d'adb sur la box)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { crashFile(this).writeText("${java.util.Date()}\n${android.util.Log.getStackTraceString(e)}") } catch (_: Throwable) { }
            // Filet de sécurité : un thème qui planterait ne doit jamais bloquer l'appli à chaque démarrage
            try { Prefs.resetThemeNow() } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    companion object { fun crashFile(c: Context) = java.io.File(c.filesDir, "crash.txt") }

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
    val sub: String? = null,
    val archive: Int = 0,  // jours de replay disponibles (chaînes)
    val added: Long = 0,   // date d'ajout sur le serveur (secondes)
    // Pour les « titres similaires » (quand le serveur les donne dans la liste)
    val genre: String? = null,
    val people: String? = null,  // acteurs + réalisateur
    val year: Int = 0
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
    // Changement d'adresse : adresse de la connexion d'origine, adresse annoncée par le serveur, adresses de secours
    var origin: String get() = str("origin"); set(v) = putS("origin", v)
    var announced: String get() = str("announced"); set(v) = putS("announced", v)
    var backups: String get() = str("backups"); set(v) = putS("backups", v)
    /** Nouveau compte saisi : on oublie l'historique d'adresses de l'ancien. */
    fun newServer(s: String) { server = s; origin = ""; announced = "" }

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
    var sort: Int get() = sp.getInt("sort", Repo.SORT_RECENT); set(v) = putI("sort", v)
    /** 0 = Mosaïque, 1 = Motion 3D, 2 = Ambilight */
    var theme: Int get() = sp.getInt("theme", 0); set(v) = putI("theme", v)
    /** Synchro du son en ms (négatif = image retardée). */
    var audioDelay: Int get() = sp.getInt("adelay", 0); set(v) = putI("adelay", v)

    /** Séries de Ma liste : nouveaux épisodes depuis la dernière visite de la fiche (date « dernière modification » du fournisseur). */
    fun hasNew(serie: Item): Boolean {
        val seen = sp.getLong("s_${serie.id}", 0L)
        if (seen == 0L) { markSeen(serie); return false }  // première fois : rien n'est « nouveau »
        return serie.added > seen
    }
    fun markSeen(serie: Item) { if (serie.added > 0) sp.edit().putLong("s_${serie.id}", serie.added).apply() }

    /** Retour au thème Mosaïque, écrit tout de suite (appelé quand l'appli plante). */
    fun resetThemeNow() { if (theme != 0) sp.edit().putInt("theme", 0).commit() }

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
    /** meta : nom, image, extension et série du titre, pour le retrouver dans « Continuer à regarder » (épisodes compris). */
    fun savePos(key: String, pos: Long, dur: Long, meta: String? = null) {
        if (key.contains(":rp_")) return  // replays : ne prennent pas la place des films en cours
        if (dur > 0 && (pos < 30_000 || pos > dur - 90_000)) clearPos(key)
        else {
            val e = sp.edit().putLong("p_$key", pos).putLong("d_$key", dur).putLong("t_$key", System.currentTimeMillis())
            if (meta != null) e.putString("m_$key", meta)
            e.apply()
            resumeKeys().drop(100).forEach { clearPos(it) }
        }
    }
    fun clearPos(key: String) = sp.edit().remove("p_$key").remove("d_$key").remove("t_$key").remove("m_$key").apply()
    fun meta(key: String): String? = sp.getString("m_$key", null)

    /* ---------- Sauvegarde (Ma liste, reprises, réglages ; jamais les codes de l'abonnement) ---------- */

    private val accountKeys = setOf("mode", "server", "user", "pass", "m3u", "origin", "announced")

    fun export(): String {
        val o = org.json.JSONObject()
        sp.all.forEach { (k, v) ->
            if (k in accountKeys) return@forEach
            when (v) {
                is String -> o.put("s:$k", v); is Int -> o.put("i:$k", v); is Long -> o.put("l:$k", v)
                is Boolean -> o.put("b:$k", v); is Float -> o.put("f:$k", v.toDouble())
                is Set<*> -> o.put("set:$k", org.json.JSONArray(v.toList()))
            }
        }
        return o.put("fkplayer", 1).toString()
    }

    /** Restaure une sauvegarde ; renvoie le nombre de réglages repris (exception si ce n'est pas une sauvegarde FK PLAYER). */
    fun import(text: String): Int {
        val o = org.json.JSONObject(text)
        if (!o.has("fkplayer")) throw IllegalArgumentException("ce fichier n'est pas une sauvegarde FK PLAYER")
        val e = sp.edit(); var n = 0
        o.keys().forEach { tk ->
            val t = tk.substringBefore(':', ""); val k = tk.substringAfter(':')
            if (t.isEmpty() || k in accountKeys) return@forEach
            when (t) {
                "s" -> e.putString(k, o.getString(tk)); "i" -> e.putInt(k, o.getInt(tk)); "l" -> e.putLong(k, o.getLong(tk))
                "b" -> e.putBoolean(k, o.getBoolean(tk)); "f" -> e.putFloat(k, o.getDouble(tk).toFloat())
                "set" -> { val a = o.getJSONArray(tk); e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
                else -> return@forEach
            }
            n++
        }
        e.commit()
        return n
    }
    fun progress(key: String): Int {
        val d = dur(key); return if (d > 0) ((pos(key) * 1000) / d).toInt() else 0
    }
    /** Clés des contenus en cours, du plus récent au plus ancien. */
    fun resumeKeys(): List<String> = sp.all.keys.filter { it.startsWith("t_") }
        .sortedByDescending { sp.getLong(it, 0L) }.map { it.removePrefix("t_") }

    fun logout() = sp.edit().remove("mode").apply()
}
