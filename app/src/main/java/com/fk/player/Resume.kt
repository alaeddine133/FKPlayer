package com.fk.player

import org.json.JSONObject

/** « Continuer à regarder » : films et épisodes commencés, retrouvés même s'ils ne sont pas dans les listes chargées. */
object Resume {

    /** Ce qu'il faut garder d'un titre en cours pour l'afficher et le relancer plus tard. */
    fun meta(it: Item, series: String?): String =
        JSONObject().put("n", it.name).put("i", it.icon ?: "").put("e", it.ext ?: "").put("s", series ?: "").toString()

    /** Titres en cours, du plus récent au plus ancien, avec le nom de la série pour un épisode. */
    fun items(limit: Int = 15): List<Pair<Item, String?>> = Prefs.resumeKeys().mapNotNull { k ->
        Repo.byKey(k)?.let { return@mapNotNull it to null }
        // Épisode : il n'est pas dans les listes, on le reconstruit depuis ce qui a été gardé à la lecture
        if (k.substringBefore(':').toIntOrNull() != Type.EPISODE) return@mapNotNull null  // film retiré du catalogue
        val m = Prefs.meta(k)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@mapNotNull null
        Item(Type.EPISODE, k.substringAfter(':'), m.optString("n"), m.optString("i").ifBlank { null }, "",
            ext = m.optString("e").ifBlank { null }) to m.optString("s").ifBlank { null }
    }.take(limit)
}
