package com.fk.player

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/** Accès aux listes : Xtream Codes (API player_api.php) ou playlist M3U. */
object Repo {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile var loaded = false
    var expDate: Long? = null
    var maxConnections: String? = null

    var liveCats: List<Category> = emptyList()
    var vodCats: List<Category> = emptyList()
    var serCats: List<Category> = emptyList()
    var live: List<Item> = emptyList()
    var vod: List<Item> = emptyList()
    var series: List<Item> = emptyList()

    private val epgCache = HashMap<String, Pair<Long, List<Epg>>>()

    val isXtream get() = Prefs.mode == "xtream"

    /* ---------- Réseau ---------- */

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    fun base() = Prefs.server.trim().trimEnd('/')
    private fun api(action: String?, extra: String = "") =
        "${base()}/player_api.php?username=${enc(Prefs.user)}&password=${enc(Prefs.pass)}" +
            (if (action != null) "&action=$action" else "") + extra

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", FkPlayer.userAgent()).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("le serveur répond ${r.code}")
            r.body?.string() ?: ""
        }
    }

    /** Normalise le serveur saisi : ajoute http://, retire un éventuel chemin. */
    fun cleanServer(raw: String): String {
        var s = raw.trim()
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        val u = Uri.parse(s)
        val port = if (u.port > 0) ":${u.port}" else ""
        return "${u.scheme}://${u.host}$port"
    }

    /** Un lien M3U « get.php?username=…&password=… » est en fait un compte Xtream : on le convertit. */
    fun xtreamFromM3u(link: String): Triple<String, String, String>? {
        return try {
            val u = Uri.parse(link.trim())
            val user = u.getQueryParameter("username")
            val pass = u.getQueryParameter("password")
            if (u.path?.contains("get.php") == true && !user.isNullOrBlank() && !pass.isNullOrBlank()) {
                val port = if (u.port > 0) ":${u.port}" else ""
                Triple("${u.scheme}://${u.host}$port", user, pass)
            } else null
        } catch (e: Exception) { null }
    }

    /* ---------- Connexion + chargement ---------- */

    suspend fun login() {
        if (isXtream) {
            val txt = get(api(null))
            val o = try { JSONObject(txt) } catch (e: Exception) { throw IOException("réponse invalide du serveur") }
            val ui = o.optJSONObject("user_info") ?: throw IOException("identifiants refusés")
            if (ui.optInt("auth", 0) != 1) throw IOException("identifiants incorrects")
            val st = ui.optString("status", "Active")
            if (st.isNotBlank() && !st.equals("Active", true)) throw IOException("abonnement $st")
            expDate = ui.optString("exp_date").toLongOrNull()?.times(1000)
            maxConnections = ui.optString("max_connections").ifBlank { null }
        } else {
            expDate = null
        }
    }

    suspend fun loadAll(progress: (String) -> Unit) {
        loaded = false
        if (isXtream) {
            coroutineScope {
                progress("Chargement des chaînes, films et séries…")
                val lc = async { cats(get(api("get_live_categories"))) }
                val vc = async { cats(get(api("get_vod_categories"))) }
                val sc = async { cats(get(api("get_series_categories"))) }
                val ls = async { parseLive(get(api("get_live_streams"))) }
                val vs = async { parseVod(get(api("get_vod_streams"))) }
                val ss = async { parseSeries(get(api("get_series"))) }
                liveCats = sortCats(lc.await()); vodCats = sortCats(vc.await()); serCats = sortCats(sc.await())
                live = ls.await(); vod = vs.await(); series = ss.await()
            }
        } else {
            progress("Téléchargement de la playlist…")
            parseM3u(get(Prefs.m3u.trim()))
        }
        epgCache.clear()
        loaded = true
    }

    /* ---------- Analyse Xtream ---------- */

    private fun arr(s: String): JSONArray = try {
        if (s.trimStart().startsWith("[")) JSONArray(s) else JSONArray()
    } catch (e: Exception) { JSONArray() }

    private fun JSONObject.s(k: String): String = if (isNull(k)) "" else optString(k, "")

    private fun cats(s: String): List<Category> {
        val a = arr(s)
        return List(a.length()) { val o = a.getJSONObject(it); Category(o.s("category_id"), o.s("category_name").trim()) }
    }

    private fun parseLive(s: String): List<Item> {
        val a = arr(s); val out = ArrayList<Item>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out += Item(Type.LIVE, o.s("stream_id"), o.s("name").trim(), o.s("stream_icon").ifBlank { null },
                o.s("category_id"), num = o.optInt("num", i + 1), epgId = o.s("epg_channel_id").ifBlank { null })
        }
        return out
    }

    private fun parseVod(s: String): List<Item> {
        val a = arr(s); val out = ArrayList<Item>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out += Item(Type.MOVIE, o.s("stream_id"), o.s("name").trim(), o.s("stream_icon").ifBlank { null },
                o.s("category_id"), ext = o.s("container_extension").ifBlank { "mp4" }, rating = rating(o.s("rating")))
        }
        return out
    }

    private fun parseSeries(s: String): List<Item> {
        val a = arr(s); val out = ArrayList<Item>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out += Item(Type.SERIES, o.s("series_id"), o.s("name").trim(), o.s("cover").ifBlank { null },
                o.s("category_id"), rating = rating(o.s("rating")))
        }
        return out
    }

    private fun rating(r: String): String? {
        val v = r.replace(',', '.').toDoubleOrNull() ?: return null
        return if (v > 0) String.format("%.1f", if (v > 10) v / 10 else v) else null
    }

    /** Les catégories françaises passent en premier (réglable). */
    private fun sortCats(l: List<Category>): List<Category> {
        if (!Prefs.frFirst) return l
        return l.sortedBy { if (isFrench(it.name)) 0 else 1 }
    }

    fun isFrench(n: String): Boolean {
        val u = n.uppercase()
        return u.startsWith("FR") || u.contains("FRANCE") || u.contains("FRENCH") || u.contains("|FR|") ||
            u.contains("FR |") || u.contains("FR:") || u.contains("VF") || u.contains("FRANÇAIS") || u.contains("FRANCAIS")
    }

    /* ---------- Séries ---------- */

    data class SeriesInfo(val plot: String?, val cover: String?, val genre: String?, val year: String?, val seasons: Map<Int, List<Episode>>)

    suspend fun seriesInfo(id: String): SeriesInfo {
        val o = JSONObject(get(api("get_series_info", "&series_id=$id")))
        val info = o.optJSONObject("info")
        val eps = o.opt("episodes")
        val map = sortedMapOf<Int, MutableList<Episode>>()
        fun add(e: JSONObject, seasonHint: Int) {
            val inf = e.optJSONObject("info")
            val season = e.optString("season").toIntOrNull() ?: seasonHint
            val ep = Episode(
                id = e.s("id"),
                title = e.s("title").ifBlank { "Épisode ${e.optInt("episode_num")}" },
                ext = e.s("container_extension").ifBlank { "mp4" },
                season = season,
                num = e.optInt("episode_num", 0),
                icon = inf?.s("movie_image")?.ifBlank { null },
                plot = inf?.s("plot")?.ifBlank { null }
            )
            map.getOrPut(season) { mutableListOf() }.add(ep)
        }
        when (eps) {
            is JSONObject -> eps.keys().forEach { k ->
                val a = eps.optJSONArray(k) ?: return@forEach
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { add(it, k.toIntOrNull() ?: 1) }
            }
            is JSONArray -> for (i in 0 until eps.length()) {
                when (val x = eps.opt(i)) {
                    is JSONArray -> for (j in 0 until x.length()) x.optJSONObject(j)?.let { add(it, i + 1) }
                    is JSONObject -> add(x, 1)
                }
            }
        }
        map.values.forEach { l -> l.sortBy { it.num } }
        return SeriesInfo(
            info?.s("plot")?.ifBlank { null }, info?.s("cover")?.ifBlank { null },
            info?.s("genre")?.ifBlank { null }, info?.s("releaseDate")?.take(4)?.ifBlank { null }, map
        )
    }

    /* ---------- URL de lecture ---------- */

    fun streamUrl(it: Item): String {
        it.url?.let { u -> return u }
        val u = enc(Prefs.user); val p = enc(Prefs.pass)
        return when (it.type) {
            Type.LIVE -> "${base()}/live/$u/$p/${it.id}.${Prefs.liveFormat}"
            Type.MOVIE -> "${base()}/movie/$u/$p/${it.id}.${it.ext ?: "mp4"}"
            Type.EPISODE -> "${base()}/series/$u/$p/${it.id}.${it.ext ?: "mp4"}"
            else -> ""
        }
    }

    fun episodeItem(e: Episode, cover: String?): Item =
        Item(Type.EPISODE, e.id, "S${e.season} · É${e.num} — ${e.title}", e.icon ?: cover, "", ext = e.ext, sub = e.plot)

    /* ---------- Guide TV (EPG court) ---------- */

    suspend fun shortEpg(it: Item): List<Epg> {
        if (!isXtream || it.type != Type.LIVE) return emptyList()
        epgCache[it.id]?.let { (t, l) -> if (System.currentTimeMillis() - t < 5 * 60_000) return l }
        val list = try {
            val o = JSONObject(get(api("get_short_epg", "&stream_id=${it.id}&limit=4")))
            val a = o.optJSONArray("epg_listings") ?: JSONArray()
            List(a.length()) { i ->
                val e = a.getJSONObject(i)
                Epg(b64(e.s("title")), (e.s("start_timestamp").toLongOrNull() ?: 0) * 1000, (e.s("stop_timestamp").toLongOrNull() ?: 0) * 1000)
            }.filter { e -> e.end > System.currentTimeMillis() }
        } catch (e: Exception) { emptyList() }
        epgCache[it.id] = System.currentTimeMillis() to list
        return list
    }

    private fun b64(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
    } catch (e: Exception) { s }

    /* ---------- Playlist M3U ---------- */

    private val attrRe = Regex("""([\w-]+)="([^"]*)"""")

    private fun parseM3u(text: String) {
        if (!text.trimStart().startsWith("#EXTM3U") && !text.contains("#EXTINF")) throw IOException("ce lien n'est pas une playlist M3U")
        val l = ArrayList<Item>(); val m = ArrayList<Item>(); val s = ArrayList<Item>()
        val lc = LinkedHashMap<String, Category>(); val mc = LinkedHashMap<String, Category>(); val sc = LinkedHashMap<String, Category>()
        var name = ""; var logo: String? = null; var group = "Autres"; var n = 0
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) {
                val attrs = attrRe.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                name = line.substringAfterLast(",").trim().ifBlank { attrs["tvg-name"] ?: "Sans nom" }
                logo = attrs["tvg-logo"]?.ifBlank { null }
                group = attrs["group-title"]?.ifBlank { null } ?: "Autres"
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                n++
                val low = line.lowercase()
                val isMovie = low.contains("/movie/") || Regex("""\.(mp4|mkv|avi|mov)(\?|$)""").containsMatchIn(low)
                val isSeries = low.contains("/series/")
                val id = "m3u$n"
                when {
                    isSeries -> { sc.getOrPut(group) { Category(group, group) }; s += Item(Type.EPISODE, id, name, logo, group, url = line) }
                    isMovie -> { mc.getOrPut(group) { Category(group, group) }; m += Item(Type.MOVIE, id, name, logo, group, url = line) }
                    else -> { lc.getOrPut(group) { Category(group, group) }; l += Item(Type.LIVE, id, name, logo, group, url = line, num = l.size + 1) }
                }
                name = ""; logo = null; group = "Autres"
            }
        }
        live = l; vod = m; series = s
        liveCats = sortCats(lc.values.toList()); vodCats = sortCats(mc.values.toList()); serCats = sortCats(sc.values.toList())
    }

    /* ---------- Recherche ---------- */

    fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    fun search(q: String, types: Set<Int>, limit: Int = 300): List<Item> {
        val k = norm(q.trim()); if (k.length < 2) return emptyList()
        val out = ArrayList<Item>()
        if (Type.LIVE in types) live.filterTo(out) { norm(it.name).contains(k) }
        if (Type.MOVIE in types) vod.filterTo(out) { norm(it.name).contains(k) }
        if (Type.SERIES in types) series.filterTo(out) { norm(it.name).contains(k) }
        return out.take(limit)
    }

    fun byKey(key: String): Item? {
        val t = key.substringBefore(':').toIntOrNull() ?: return null
        val id = key.substringAfter(':')
        return when (t) {
            Type.LIVE -> live.firstOrNull { it.id == id }
            Type.MOVIE -> vod.firstOrNull { it.id == id }
            Type.SERIES, Type.EPISODE -> series.firstOrNull { it.id == id }
            else -> null
        }
    }
}
