package com.fk.player

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * Accès aux listes : Xtream Codes (player_api.php) ou playlist M3U.
 * - Les réponses sont gardées sur la box : l'appli s'ouvre instantanément, puis se met à jour en arrière-plan.
 * - Tout le décodage se fait hors du fil d'affichage (pas de gel, même avec 50 000 chaînes).
 */
object Repo {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private lateinit var filesDir: File
    fun init(c: Context) { filesDir = c.filesDir }

    @Volatile var loaded = false
    @Volatile var fromCache = false
    var expDate: Long? = null
    var maxConnections: String? = null

    /** Toutes les listes + index ensemble : une mise à jour en arrière-plan les remplace d'un seul coup. */
    private class Data(
        val liveCats: List<Category> = emptyList(), val vodCats: List<Category> = emptyList(), val serCats: List<Category> = emptyList(),
        val live: List<Item> = emptyList(), val vod: List<Item> = emptyList(), val series: List<Item> = emptyList(),
        val liveByCat: Map<String, List<Item>> = emptyMap(), val vodByCat: Map<String, List<Item>> = emptyMap(),
        val serByCat: Map<String, List<Item>> = emptyMap(),
        val searchIndex: List<Pair<String, Item>> = emptyList(), val byKeyIndex: Map<String, Item> = emptyMap()
    )
    @Volatile private var data = Data()

    val liveCats get() = data.liveCats
    val vodCats get() = data.vodCats
    val serCats get() = data.serCats
    val live get() = data.live
    val vod get() = data.vod
    val series get() = data.series
    val liveByCat get() = data.liveByCat
    val vodByCat get() = data.vodByCat
    val serByCat get() = data.serByCat

    private val epgCache = HashMap<String, Pair<Long, List<Epg>>>()

    val isXtream get() = Prefs.mode == "xtream"

    /* ---------- Réseau ---------- */

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    fun base() = Prefs.server.trim().trimEnd('/')
    private fun apiAt(b: String, action: String?, extra: String = "") =
        "$b/player_api.php?username=${enc(Prefs.user)}&password=${enc(Prefs.pass)}" +
            (if (action != null) "&action=$action" else "") + extra
    private fun api(action: String?, extra: String = "") = apiAt(base(), action, extra)

    /* ---------- Changement d'adresse chez le fournisseur ---------- */

    /** « http://hote:port » d'un lien complet (ou d'une adresse sans http), sinon null. */
    fun origin(link: String): String? = try {
        val t = link.trim()
        val u = java.net.URI(if ("://" in t) t else "http://$t")
        if (u.host.isNullOrBlank()) null else "${u.scheme}://${u.host}" + (if (u.port > 0) ":${u.port}" else "")
    } catch (e: Exception) { null }

    /** Adresse annoncée par le serveur dans « server_info », ou null. */
    fun announced(proto: String, host: String, port: String, httpsPort: String): String? {
        if (host.isBlank()) return null
        val https = proto.equals("https", true)
        val p = (if (https) httpsPort else port).trim()
        return origin((if (https) "https://" else "http://") + host.trim() + (if (p.isNotEmpty() && p != "0") ":$p" else ""))
    }

    /** Adresses à essayer dans l'ordre, sans doublon : actuelle, annoncée par le serveur, secours. */
    fun candidates(current: String, announced: String, backups: String): List<String> =
        (listOf(current, announced) + backups.split('\n', ' ', ',', ';'))
            .mapNotNull { if (it.isBlank()) null else origin(it) }.distinct()

    /**
     * Connexion au compte. Si l'adresse ne répond plus : renvoi définitif suivi, puis adresse annoncée
     * par le serveur, puis adresses de secours. La première qui répond devient l'adresse du compte.
     */
    private suspend fun authOnline(): String = withContext(Dispatchers.IO) {
        var first: Exception? = null  // l'erreur de l'adresse saisie est la plus parlante pour l'utilisateur
        for (s in candidates(Prefs.server, Prefs.announced, Prefs.backups)) {
            try {
                val req = Request.Builder().url(apiAt(s, null)).header("User-Agent", FkPlayer.userAgent()).build()
                val (body, moved) = http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("le serveur répond ${r.code}")
                    val codes = generateSequence(r.priorResponse) { it.priorResponse }.map { it.code }.toList()
                    (r.body?.string() ?: "") to movedTo(codes, r.request.url.toString())
                }
                val o = JSONObject(body)  // page d'erreur ou domaine abandonné : pas du JSON → adresse suivante
                if (!o.has("user_info")) throw IOException("réponse invalide du serveur")
                // Codes refusés : on garde l'adresse saisie (et son message) ; une autre adresse ne devient jamais celle du compte
                if (o.optJSONObject("user_info")?.optInt("auth", 0) != 1) {
                    if (s == Prefs.server) return@withContext body
                    throw IOException("codes refusés par $s")
                }
                switchTo(moved ?: s)
                o.optJSONObject("server_info")?.let { si ->
                    announced(si.optString("server_protocol"), si.optString("url"), si.optString("port"), si.optString("https_port"))
                        ?.let { if (it != Prefs.server) Prefs.announced = it }
                }
                return@withContext body
            } catch (e: Exception) { if (first == null) first = e }
        }
        throw first ?: IOException("serveur injoignable")
    }

    /**
     * Nouvelle adresse du serveur après des renvois (codes HTTP, du premier au dernier), sinon null.
     * Seuls des renvois tous définitifs (301 / 308) changent l'adresse : un 302 mène souvent à un serveur provisoire.
     */
    fun movedTo(codes: List<Int>, finalUrl: String): String? =
        if (codes.isNotEmpty() && codes.all { it == 301 || it == 308 }) origin(finalUrl) else null

    private fun switchTo(s: String) {
        if (s == Prefs.server) return
        if (Prefs.origin.isBlank()) Prefs.origin = Prefs.server  // les listes gardées restent dans le même dossier
        Prefs.server = s
    }

    /** Vérification rapide au démarrage : true si l'appli a basculé sur une autre adresse. */
    suspend fun serverMoved(): Boolean {
        if (!isXtream) return false
        val before = Prefs.server
        authOnline()
        return Prefs.server != before
    }

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", FkPlayer.userAgent()).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("le serveur répond ${r.code}")
            r.body?.string() ?: ""
        }
    }

    /* ---------- Cache disque ---------- */

    private fun cacheDir(): File {
        val id = (Prefs.mode + Prefs.origin.ifBlank { Prefs.server } + Prefs.user + Prefs.m3u).hashCode().toString(16)
        return File(filesDir, "cache/$id").apply { mkdirs() }
    }

    /**
     * Lit depuis le cache si demandé (et assez récent), sinon télécharge directement dans le fichier
     * (jamais tout le catalogue en mémoire), écrit en .tmp puis renommé : une coupure ne l'abîme pas.
     */
    private suspend fun fetch(key: String, url: String, preferCache: Boolean, online: (suspend () -> String)? = null): File = withContext(Dispatchers.IO) {
        val f = File(cacheDir(), "$key.json")
        if (preferCache && f.exists() && System.currentTimeMillis() - f.lastModified() < 24 * 3600_000L) {
            fromCache = true
            return@withContext f
        }
        try {
            val tmp = File(f.path + ".tmp")
            if (online != null) tmp.writeText(online()) else {
            val req = Request.Builder().url(url).header("User-Agent", FkPlayer.userAgent()).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("le serveur répond ${r.code}")
                r.body!!.byteStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
            }
            }
            if (tmp.length() > 2 || !f.exists()) { if (!tmp.renameTo(f)) throw IOException("stockage plein") } else tmp.delete()
            f
        } catch (e: Exception) {
            if (f.exists()) { fromCache = true; f } else throw e
        }
    }

    private fun cacheFile() = File(cacheDir(), if (isXtream) "auth.json" else "m3u.json")
    fun hasCache(): Boolean = cacheFile().exists()
    /** Âge des listes gardées sur la box (ms). */
    fun cacheAge(): Long = System.currentTimeMillis() - cacheFile().lastModified()

    /* ---------- Saisie ---------- */

    /** Normalise le serveur saisi : ajoute http://, retire un éventuel chemin. */
    fun cleanServer(raw: String): String {
        var s = raw.trim()
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        val u = Uri.parse(s)
        val port = if (u.port > 0) ":${u.port}" else ""
        return "${u.scheme}://${u.host}$port"
    }

    /** Un lien M3U « get.php?username=…&password=… » est un compte Xtream : on le convertit. */
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

    /**
     * Connexion et chargement complet.
     * preferCache = true : ouverture instantanée depuis la box si possible.
     */
    suspend fun load(preferCache: Boolean, progress: (String) -> Unit) = withContext(Dispatchers.Default) {
        fromCache = false
        if (isXtream) {
            val auth = fetch("auth", "", preferCache) { authOnline() }
            val o = try { JSONObject(auth.readText()) } catch (e: Exception) { throw IOException("réponse invalide du serveur") }
            val ui = o.optJSONObject("user_info") ?: throw IOException("identifiants refusés")
            if (ui.optInt("auth", 0) != 1) throw IOException("identifiants incorrects")
            val st = ui.optString("status", "Active")
            if (st.isNotBlank() && !st.equals("Active", true)) throw IOException("abonnement $st")
            expDate = ui.optString("exp_date").toLongOrNull()?.times(1000)
            maxConnections = ui.optString("max_connections").ifBlank { null }

            progress(if (preferCache && hasCache()) "Ouverture…" else "Chargement des chaînes, films et séries…")
            coroutineScope {
                val lc = async { cats(fetch("live_cats", api("get_live_categories"), preferCache)) }
                val vc = async { cats(fetch("vod_cats", api("get_vod_categories"), preferCache)) }
                val sc = async { cats(fetch("ser_cats", api("get_series_categories"), preferCache)) }
                val ls = async { parseLive(fetch("live", api("get_live_streams"), preferCache)) }
                val vs = async { parseVod(fetch("vod", api("get_vod_streams"), preferCache)) }
                val ss = async { parseSeries(fetch("series", api("get_series"), preferCache)) }
                publish(lc.await(), vc.await(), sc.await(), ls.await(), vs.await(), ss.await())
            }
        } else {
            expDate = null
            progress("Téléchargement de la playlist…")
            parseM3u(fetch("m3u", Prefs.m3u.trim(), preferCache))
        }
        synchronized(epgCache) { epgCache.clear() }
        val old = System.currentTimeMillis() - 7 * 24 * 3600_000L
        cacheDir().listFiles()?.forEach { if ((it.name.startsWith("serie_") || it.name.startsWith("vinfo_")) && it.lastModified() < old) it.delete() }
        loaded = true
    }

    /** Remplace toutes les listes d'un coup et prépare les index. */
    private fun publish(lc: List<Category>, vc: List<Category>, sc: List<Category>, l: List<Item>, v0: List<Item>, s0: List<Item>) {
        // Films et séries : les derniers ajoutés en premier (le bouton « Trier » change l'affichage)
        val v = v0.sortedByDescending { it.added }
        val s = s0.sortedByDescending { it.added }
        // Les éléments dont la catégorie n'est pas déclarée vont tous dans « Autres »
        fun group(items: List<Item>, cats: List<Category>): Map<String, List<Item>> {
            val ids = cats.mapTo(HashSet()) { it.id }
            return items.groupBy { if (it.catId in ids) it.catId else OTHERS }
        }
        val lbc = group(l, lc); val vbc = group(v, vc); val sbc = group(s, sc)
        val idx = ArrayList<Pair<String, Item>>(l.size + v.size + s.size)
        l.forEach { idx += norm(it.name) to it }; v.forEach { idx += norm(it.name) to it }; s.forEach { idx += norm(it.name) to it }
        val keys = HashMap<String, Item>(l.size + v.size + s.size)
        l.forEach { keys[it.key] = it }; v.forEach { keys[it.key] = it }; s.forEach { keys[it.key] = it }
        // On garde les catégories qui contiennent au moins un élément
        fun withOthers(c: List<Category>, m: Map<String, List<Item>>) =
            sortCats(c.filter { m.containsKey(it.id) }) + (if (m.containsKey(OTHERS)) listOf(Category(OTHERS, "Autres")) else emptyList())
        data = Data(withOthers(lc, lbc), withOthers(vc, vbc), withOthers(sc, sbc), l, v, s, lbc, vbc, sbc, idx, keys)
    }

    private const val OTHERS = "__autres"

    /* ---------- Analyse Xtream (lecture en continu, élément par élément) ---------- */

    /** Parcourt un tableau JSON d'objets depuis le fichier, sans le charger en entier. Erreur au milieu : on garde le début. */
    private fun objects(f: File, each: (Map<String, String>) -> Unit) {
        try {
            JsonReader(f.bufferedReader()).use { r ->
                r.isLenient = true
                if (r.peek() != JsonToken.BEGIN_ARRAY) return
                r.beginArray()
                while (r.hasNext()) {
                    if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                    val m = HashMap<String, String>()
                    r.beginObject()
                    while (r.hasNext()) {
                        val k = r.nextName()
                        when (r.peek()) {
                            JsonToken.STRING, JsonToken.NUMBER -> m[k] = r.nextString()
                            JsonToken.BOOLEAN -> m[k] = r.nextBoolean().toString()
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    each(m)
                }
            }
        } catch (e: Exception) { }
    }

    private fun Map<String, String>.s(k: String): String = this[k] ?: ""
    private fun JSONObject.s(k: String): String = if (isNull(k)) "" else optString(k, "")

    private fun cats(f: File): List<Category> {
        val out = ArrayList<Category>()
        objects(f) { o -> out += Category(o.s("category_id"), o.s("category_name").trim()) }
        return out
    }

    private fun parseLive(f: File): List<Item> {
        val out = ArrayList<Item>()
        objects(f) { o ->
            out += Item(Type.LIVE, o.s("stream_id"), o.s("name").trim(), o.s("stream_icon").ifBlank { null },
                o.s("category_id"), num = o.s("num").toIntOrNull() ?: (out.size + 1), epgId = o.s("epg_channel_id").ifBlank { null },
                archive = if (o.s("tv_archive") == "1") o.s("tv_archive_duration").toIntOrNull()?.coerceAtLeast(1) ?: 1 else 0)
        }
        return out
    }

    private fun parseVod(f: File): List<Item> {
        val out = ArrayList<Item>()
        objects(f) { o ->
            out += Item(Type.MOVIE, o.s("stream_id"), o.s("name").trim(), o.s("stream_icon").ifBlank { null },
                o.s("category_id"), ext = o.s("container_extension").ifBlank { "mp4" }, rating = rating(o.s("rating")),
                added = o.s("added").toLongOrNull() ?: 0, genre = o.s("genre").ifBlank { null },
                people = (o.s("cast") + "," + o.s("director")).trim(',').ifBlank { null },
                year = (o.s("year").ifBlank { o.s("releaseDate") }).take(4).toIntOrNull() ?: 0)
        }
        return out
    }

    private fun parseSeries(f: File): List<Item> {
        val out = ArrayList<Item>()
        objects(f) { o ->
            out += Item(Type.SERIES, o.s("series_id"), o.s("name").trim(), o.s("cover").ifBlank { null },
                o.s("category_id"), rating = rating(o.s("rating")), added = o.s("last_modified").toLongOrNull() ?: 0,
                genre = o.s("genre").ifBlank { null }, people = (o.s("cast") + "," + o.s("director")).trim(',').ifBlank { null },
                year = (o.s("releaseDate").ifBlank { o.s("release_date") }).take(4).toIntOrNull() ?: 0)
        }
        return out
    }

    private fun rating(r: String): String? {
        val v = r.replace(',', '.').toDoubleOrNull() ?: return null
        return if (v > 0) String.format(java.util.Locale.FRANCE, "%.1f", if (v > 10) v / 10 else v) else null
    }

    /** Les catégories françaises passent en premier (réglable). */
    private fun sortCats(l: List<Category>): List<Category> {
        if (!Prefs.frFirst) return l
        return l.sortedBy { if (isFrench(it.name)) 0 else 1 }
    }

    fun isFrench(n: String): Boolean {
        val u = n.uppercase()
        return u.startsWith("FR") || u.contains("FRANCE") || u.contains("FRENCH") || u.contains("|FR|") ||
            u.contains("FR |") || u.contains("FR:") || u.contains(" VF") || u.startsWith("VF") ||
            u.contains("FRANÇAIS") || u.contains("FRANCAIS")
    }

    /* ---------- Séries ---------- */

    /** Fiche d'un film ou d'une série (champs vides si le serveur ne les donne pas). */
    data class Info(
        val plot: String?, val cover: String?, val backdrop: String?, val genre: String?, val year: String?,
        val cast: String?, val director: String?, val trailer: String?, val duration: String?
    )

    private fun info(o: JSONObject?): Info {
        fun f(vararg k: String) = k.firstNotNullOfOrNull { o?.s(it)?.trim()?.ifBlank { null } }
        val bd = o?.optJSONArray("backdrop_path")?.optString(0)?.ifBlank { null } ?: f("backdrop_path")
        return Info(
            f("plot", "description"), f("cover", "movie_image", "cover_big"), bd, f("genre"),
            f("releaseDate", "releasedate", "release_date")?.take(4), f("cast", "actors"), f("director"),
            f("youtube_trailer", "trailer")?.let { ytId(it) }, f("duration")
        )
    }

    /** Identifiant YouTube depuis un id seul ou une adresse complète. */
    fun ytId(s: String): String? {
        val id = when {
            "v=" in s -> s.substringAfter("v=").substringBefore('&')
            "youtu.be/" in s -> s.substringAfter("youtu.be/").substringBefore('?')
            "/embed/" in s -> s.substringAfter("/embed/").substringBefore('?')
            else -> s
        }.trim()
        return id.takeIf { it.matches(Regex("[\\w-]{6,20}")) }
    }

    /** Fiche d'un film, gardée sur la box 24 h (sert aussi aux titres similaires). */
    suspend fun vodInfo(id: String): Info? {
        if (!isXtream) return null
        return try {
            val f = fetch("vinfo_$id", api("get_vod_info", "&vod_id=$id"), preferCache = true)
            withContext(Dispatchers.Default) { info(JSONObject(f.readText()).optJSONObject("info")) }
        } catch (e: Exception) { null }
    }

    /* ---------- Titres similaires ---------- */

    // Mots qui ne disent rien du film : langue, qualité, petits mots, « saison », « partie »…
    private val noise = setOf(
        "the", "les", "des", "une", "and", "von", "der", "die", "das", "del", "los", "las", "film", "movie", "serie", "series",
        "saison", "season", "part", "partie", "vol", "chapitre", "chapter", "vostfr", "vff", "vfq", "multi", "fhd", "uhd", "hdr",
        "1080p", "720p", "2160p", "french", "truefrench", "sub", "dub", "integrale", "complete", "collection", "edition", "version",
        "extended", "remastered", "cut", "eng", "ara", "deu", "ger", "ita", "esp", "spa", "por", "tur", "pol", "nld", "rus"
    )
    // Genres anglais → français, pour comparer des fiches écrites dans les deux langues
    private val genreFr = mapOf(
        "comedy" to "comedie", "drama" to "drame", "horror" to "horreur", "adventure" to "aventure", "family" to "familial",
        "famille" to "familial", "war" to "guerre", "documentary" to "documentaire", "mystery" to "mystere", "fantasy" to "fantastique",
        "music" to "musique", "history" to "histoire", "science fiction" to "sf", "science-fiction" to "sf", "sci-fi" to "sf",
        "sci-fi & fantasy" to "sf", "action & adventure" to "action", "kids" to "enfants", "policier" to "crime", "romantique" to "romance"
    )

    /** Mots qui identifient un titre (sans langue, qualité, année ni numéro). */
    fun titleWords(name: String): Set<String> = Regex("[a-z0-9]+").findAll(norm(name)).map { it.value }
        .filter { it.length >= 3 && it !in noise && !it.all(Char::isDigit) && it != "4k" }.toSet()

    fun genres(s: String?): Set<String> = s.orEmpty().split(',', '/', '|', ';').map { norm(it).trim() }
        .filter { it.length >= 3 }.map { genreFr[it] ?: it }.toSet()

    fun people(s: String?): Set<String> = s.orEmpty().split(',').map { norm(it).trim() }.filter { it.length >= 4 }.toSet()

    class Profile(val words: Set<String>, val genres: Set<String>, val people: Set<String>, val year: Int)

    /**
     * Proximité entre deux titres (0 = aucun rapport) : même saga (mots rares du titre en commun), mêmes genres,
     * mêmes acteurs / réalisateur, puis année proche pour départager.
     */
    fun closeness(a: Profile, b: Profile, idf: (String) -> Double): Double {
        val title = if (a.words.isEmpty()) 0.0 else a.words.filter { it in b.words }.sumOf(idf) / a.words.sumOf(idf)
        val gen = if (a.genres.isEmpty() || b.genres.isEmpty()) 0.0 else a.genres.count { it in b.genres }.toDouble() / (a.genres + b.genres).size
        val ppl = a.people.count { it in b.people }.coerceAtMost(3) / 3.0
        if (title < 0.5 && gen == 0.0 && ppl == 0.0) return 0.0  // seul le rayon en commun : pas similaire
        val yr = if (a.year > 0 && b.year > 0) (1 - kotlin.math.abs(a.year - b.year) / 15.0).coerceAtLeast(0.0) else 0.0
        return 5 * title + 3 * gen + 3 * ppl + 0.5 * yr
    }

    /** Mots de chaque titre (calculés une fois) + rareté de chaque mot : « batman » compte plus que « nuit ». */
    private class SimIndex(val pool: List<Item>, val words: Map<String, Set<String>>, val idf: Map<String, Double>)
    @Volatile private var simIndex: SimIndex? = null

    private fun indexOf(pool: List<Item>): SimIndex {
        simIndex?.let { if (it.pool === pool) return it }
        val words = HashMap<String, Set<String>>(pool.size)
        val df = HashMap<String, Int>()
        pool.forEach { x -> titleWords(x.name).also { words[x.key] = it }.forEach { w -> df[w] = (df[w] ?: 0) + 1 } }
        val n = pool.size.toDouble()
        return SimIndex(pool, words, df.mapValues { (_, c) -> kotlin.math.ln(1 + n / c) }).also { simIndex = it }
    }

    private fun profile(x: Item, i: Info?, words: Map<String, Set<String>>? = null) = Profile(
        words?.get(x.key) ?: titleWords(x.name), genres(i?.genre ?: x.genre),
        people(listOfNotNull(i?.cast, i?.director, x.people).joinToString(",")), i?.year?.toIntOrNull() ?: x.year
    )

    /** Titres vraiment proches de celui affiché (vide si on ne trouve rien de pertinent). */
    suspend fun similar(of: Item, info: Info?): List<Item> = withContext(Dispatchers.Default) {
        val pool = if (of.type == Type.SERIES) series else vod
        val ix = indexOf(pool)
        val idf = { w: String -> ix.idf[w] ?: 1.0 }
        val me = profile(of, info)
        val others = pool.filter { it.id != of.id }
        fun prof(x: Item, i: Info?) = profile(x, i, ix.words)
        // Films sans genre dans la liste du serveur : on lit la fiche (gardée sur la box) de 40 candidats au plus
        val extra = HashMap<String, Info>()
        if (of.type == Type.MOVIE && isXtream && others.none { it.genre != null } && (me.genres.isNotEmpty() || me.people.isNotEmpty())) {
            val cands = (others.filter { closeness(me, prof(it, null), idf) > 0 } + others.filter { it.catId == of.catId }).distinct().take(40)
            cands.chunked(6).forEach { group ->
                coroutineScope { group.map { x -> async { vodInfo(x.id)?.let { x.id to it } } }.map { it.await() } }
                    .filterNotNull().forEach { (id, i) -> extra[id] = i }
            }
        }
        others.map { it to closeness(me, prof(it, extra[it.id]), idf) }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Item, Double>> { it.second }
                .thenByDescending { it.first.rating?.replace(',', '.')?.toDoubleOrNull() ?: 0.0 })
            .take(20).map { it.first }
    }

    data class SeriesInfo(val info: Info, val seasons: Map<Int, List<Episode>>)

    suspend fun seriesInfo(id: String): SeriesInfo = withContext(Dispatchers.Default) {
        val o = JSONObject(fetch("serie_$id", api("get_series_info", "&series_id=$id"), false).readText())
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
        SeriesInfo(info(info), map)
    }

    /* ---------- Replay des chaînes (timeshift Xtream) ---------- */

    data class Replay(val title: String, val start: Long, val end: Long, val url: String)

    /** Émissions passées encore disponibles en replay, de la plus récente à la plus ancienne. */
    suspend fun replays(ch: Item): List<Replay> = withContext(Dispatchers.Default) {
        if (!isXtream || ch.archive <= 0) return@withContext emptyList()
        val a = JSONObject(get(api("get_simple_data_table", "&stream_id=${ch.id}"))).optJSONArray("epg_listings") ?: JSONArray()
        val now = System.currentTimeMillis(); val from = now - ch.archive * 86_400_000L
        val u = Uri.encode(Prefs.user); val p = Uri.encode(Prefs.pass)
        val local = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US)
        List(a.length()) { a.optJSONObject(it) }.mapNotNull { e ->
            e ?: return@mapNotNull null
            val s = (e.s("start_timestamp").toLongOrNull() ?: return@mapNotNull null) * 1000
            val t = (e.s("stop_timestamp").toLongOrNull() ?: return@mapNotNull null) * 1000
            if (t > now || s < from || e.s("has_archive") == "0") return@mapNotNull null
            val st = tsStart(e.s("start")) ?: local.format(java.util.Date(s))
            val min = ((t - s) / 60_000).coerceAtLeast(1)
            Replay(b64(e.s("title")), s, t, "${base()}/timeshift/$u/$p/$min/$st/${ch.id}.ts")
        }.sortedByDescending { it.start }
    }

    /** « 2026-11-23 20:45:00 » (heure du serveur, que le timeshift attend dans son propre fuseau) → « 2026-11-23:20-45 ». */
    fun tsStart(start: String): String? =
        Regex("""^(\d{4}-\d{2}-\d{2})[ T](\d{2}):(\d{2})""").find(start)?.destructured?.let { (d, h, m) -> "$d:$h-$m" }

    /* ---------- URL de lecture ---------- */

    fun streamUrl(it: Item): String {
        it.url?.let { u -> return u }
        val u = Uri.encode(Prefs.user); val p = Uri.encode(Prefs.pass)
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

    /** Guide déjà en mémoire (aucune requête réseau) : pour l'affichage rapide des listes. */
    fun cachedEpg(it: Item): List<Epg>? = synchronized(epgCache) {
        epgCache[it.id]?.second?.filter { e -> e.end > System.currentTimeMillis() }
    }

    suspend fun shortEpg(it: Item): List<Epg> {
        if (!isXtream || it.type != Type.LIVE) return emptyList()
        synchronized(epgCache) {
            epgCache[it.id]?.let { (t, l) ->
                if (System.currentTimeMillis() - t < 5 * 60_000) return l.filter { e -> e.end > System.currentTimeMillis() }
            }
        }
        val list = try {
            withContext(Dispatchers.Default) {
                val o = JSONObject(get(api("get_short_epg", "&stream_id=${it.id}&limit=4")))
                val a = o.optJSONArray("epg_listings") ?: JSONArray()
                List(a.length()) { i ->
                    val e = a.getJSONObject(i)
                    Epg(b64(e.s("title")), (e.s("start_timestamp").toLongOrNull() ?: 0) * 1000, (e.s("stop_timestamp").toLongOrNull() ?: 0) * 1000)
                }.filter { e -> e.end > System.currentTimeMillis() }.sortedBy { e -> e.start }
            }
        } catch (e: Exception) { emptyList() }
        synchronized(epgCache) { epgCache[it.id] = System.currentTimeMillis() to list }
        return list
    }

    private fun b64(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
    } catch (e: Exception) { s }

    /* ---------- Playlist M3U ---------- */

    private val attrRe = Regex("""([\w-]+)="([^"]*)"""")
    private val movieRe = Regex("""\.(mp4|mkv|avi|mov|m4v)(\?|$)""")

    private fun parseM3u(f: File) {
        val p = f.useLines { m3u(it) }
        publish(p.lc, p.mc, p.sc, p.l, p.m, p.s)
    }

    class M3u(val lc: List<Category>, val mc: List<Category>, val sc: List<Category>, val l: List<Item>, val m: List<Item>, val s: List<Item>)

    /** Range chaque ligne de la playlist en direct, film ou épisode (séparé de la lecture du fichier pour être testé). */
    fun m3u(lines: Sequence<String>): M3u {
        var isM3u = false
        val l = ArrayList<Item>(); val m = ArrayList<Item>(); val s = ArrayList<Item>()
        val lc = LinkedHashMap<String, Category>(); val mc = LinkedHashMap<String, Category>(); val sc = LinkedHashMap<String, Category>()
        var name = ""; var logo: String? = null; var group = "Autres"
        lines.forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("#EXTM3U")) isM3u = true
            if (line.startsWith("#EXTINF")) {
                isM3u = true
                val attrs = attrRe.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                name = line.substringAfterLast(",").trim().ifBlank { attrs["tvg-name"] ?: "Sans nom" }
                logo = attrs["tvg-logo"]?.ifBlank { null }
                group = attrs["group-title"]?.ifBlank { null } ?: "Autres"
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                val low = line.lowercase()
                val id = "m3u" + line.hashCode().toUInt().toString(36)  // stable : ne bouge pas si la playlist change d'ordre
                when {
                    low.contains("/series/") -> { sc.getOrPut(group) { Category(group, group) }; s += Item(Type.EPISODE, id, name, logo, group, url = line) }
                    low.contains("/movie/") || movieRe.containsMatchIn(low) -> { mc.getOrPut(group) { Category(group, group) }; m += Item(Type.MOVIE, id, name, logo, group, url = line) }
                    else -> { lc.getOrPut(group) { Category(group, group) }; l += Item(Type.LIVE, id, name, logo, group, url = line, num = l.size + 1) }
                }
                name = ""; logo = null; group = "Autres"
            }
        }
        if (!isM3u) throw IOException("ce lien n'est pas une playlist M3U")
        return M3u(lc.values.toList(), mc.values.toList(), sc.values.toList(), l, m, s)
    }

    /* ---------- Recherche ---------- */

    const val SORT_RECENT = 0; const val SORT_AZ = 1; const val SORT_ZA = 2; const val SORT_RATING = 3
    val sortNames = arrayOf("Ajoutés récemment", "De A à Z", "De Z à A", "Les mieux notés")

    /** Ordre d'affichage choisi avec le bouton « Trier » (sans accents ni majuscules pour l'alphabet). */
    fun sorted(list: List<Item>, how: Int): List<Item> = when (how) {
        SORT_AZ, SORT_ZA -> list.map { norm(it.name) to it }.sortedBy { it.first }.map { it.second }.let { if (how == SORT_ZA) it.reversed() else it }
        SORT_RATING -> list.sortedByDescending { it.rating?.replace(',', '.')?.toDoubleOrNull() ?: -1.0 }
        else -> list.sortedByDescending { it.added }
    }

    fun norm(s: String): String =Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    fun search(q: String, types: Set<Int>, limit: Int = 300): List<Item> {
        val k = norm(q.trim()); if (k.length < 2) return emptyList()
        val out = ArrayList<Item>()
        for ((n, it) in data.searchIndex) {
            val t = if (it.type == Type.EPISODE) Type.SERIES else it.type
            if (t in types && n.contains(k)) { out += it; if (out.size >= limit) break }
        }
        // Les titres qui commencent par la recherche d'abord
        return out.sortedBy { if (norm(it.name).startsWith(k)) 0 else 1 }
    }

    fun byKey(key: String): Item? = data.byKeyIndex[key]
}
