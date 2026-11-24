package com.fk.player

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/** Paramètres du lecteur et du compte. Chaque ligne change de valeur quand on appuie sur OK. */
class SettingsActivity : BaseActivity() {

    private class Row(val name: String, val hint: String, val value: () -> String, val action: () -> Unit)

    private lateinit var ad: Adapter<Row>

    private val langs = listOf("fr" to "Français", "en" to "Anglais", "ar" to "Arabe", "orig" to "Version originale")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (needData()) return
        setContentView(R.layout.activity_settings)
        setTop("Paramètres")

        val rows = listOfNotNull(
            Row("Thème", "Mosaïque : tuiles classiques · Motion 3D : carrousels en relief", { if (Prefs.theme == 1) "Motion 3D" else "Mosaïque" }) {
                Prefs.theme = 1 - Prefs.theme
                // Tous les écrans sont redessinés avec le nouveau thème, en repartant de l'accueil
                startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            },
            Row("Format du direct", "TS est le plus stable sur la plupart des serveurs", { if (Prefs.liveFormat == "ts") "TS · recommandé" else "HLS (m3u8)" }) {
                Prefs.liveFormat = if (Prefs.liveFormat == "ts") "m3u8" else "ts"
            },
            Row("Mémoire tampon", "Plus elle est grande, moins ça coupe (mais le zapping est plus lent)", { FkPlayer.bufferNames[Prefs.buffer] }) {
                Prefs.buffer = (Prefs.buffer + 1) % 3
            },
            Row("User-Agent", "Si une chaîne refuse de se lancer, essaie VLC", { FkPlayer.uaNames[Prefs.ua] }) {
                Prefs.ua = (Prefs.ua + 1) % 3
            },
            Row("Format d'image", "Adapter : image entière · Remplir : étirée · Zoom : sans bandes noires", { FkPlayer.resizeNames[Prefs.resize] }) {
                Prefs.resize = (Prefs.resize + 1) % 3
            },
            Row("Langue audio préférée", "Choisie automatiquement quand le flux propose plusieurs langues", { langs.first { it.first == Prefs.audioLang }.second }) {
                val i = langs.indexOfFirst { it.first == Prefs.audioLang }
                Prefs.audioLang = langs[(i + 1) % langs.size].first
            },
            Row("Sous-titres", "Afficher automatiquement les sous-titres disponibles", { if (Prefs.subtitles) "Activés" else "Désactivés" }) {
                Prefs.subtitles = !Prefs.subtitles
            },
            Row("Reconnexion automatique", "Relance le flux tout seul en cas de coupure", { if (Prefs.reconnect) "Oui" else "Non" }) {
                Prefs.reconnect = !Prefs.reconnect
            },
            Row("Épisode suivant automatique", "Enchaîne les épisodes d'une série", { if (Prefs.autoNext) "Oui" else "Non" }) {
                Prefs.autoNext = !Prefs.autoNext
            },
            Row("Catégories françaises en premier", "Appliqué à la prochaine actualisation", { if (Prefs.frFirst) "Oui" else "Non" }) {
                Prefs.frFirst = !Prefs.frFirst
            },
            if (!Repo.isXtream) null else Row("Adresses de secours du serveur", "Si le serveur ne répond plus, FK PLAYER essaie ces adresses toute seule",
                { Repo.candidates("", "", Prefs.backups).size.let { if (it == 0) "Aucune" else "$it adresse" + (if (it > 1) "s" else "") } }) { editBackups() },
            Row("Mise à jour de l'appli", "FK PLAYER vérifie aussi tout seul à chaque démarrage", { "Rechercher" }) { Updater.check(this, manual = true) },
            Row("Actualiser les listes", "Recharge chaînes, films et séries depuis le serveur", { "Actualiser" }) { reload() },
            Row("Changer de compte", "Se déconnecter et saisir un autre abonnement", { "Déconnexion" }) { logout() }
        )

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        ad = Adapter(R.layout.item_setting, 1.02f) { v, r, _ ->
            v.findViewById<TextView>(R.id.name).text = r.name
            v.findViewById<TextView>(R.id.hint).text = r.hint
            v.findViewById<TextView>(R.id.value).text = r.value()
        }
        ad.onClick = { r, i -> r.action(); ad.notifyItemChanged(i) }
        ad.items = rows
        list.adapter = ad
        Fx3d.list(list, horizontal = false)
        list.post { list.getChildAt(0)?.requestFocus() }

        findViewById<TextView>(R.id.version).text = "FK PLAYER · version ${packageManager.getPackageInfo(packageName, 0).versionName}"
        val exp = Repo.expDate?.let { java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.FRANCE).format(java.util.Date(it)) }
        findViewById<TextView>(R.id.account).text = if (Repo.isXtream) {
            "Serveur : ${Prefs.server}\nUtilisateur : ${Prefs.user}\nExpire le : ${exp ?: "illimité"}" +
                (Repo.maxConnections?.let { "\nConnexions max : $it" } ?: "") +
                "\n\n${Repo.live.size} chaînes · ${Repo.vod.size} films · ${Repo.series.size} séries"
        } else {
            "Playlist M3U\n${Prefs.m3u.take(60)}…\n\n${Repo.live.size} chaînes · ${Repo.vod.size} films · ${Repo.series.size} épisodes"
        }
    }

    private fun reload() {
        toast("Actualisation en cours…")
        lifecycleScope.launch {
            try {
                Repo.load(preferCache = false) { }
                toast("Listes à jour : ${Repo.live.size} chaînes, ${Repo.vod.size} films, ${Repo.series.size} séries")
            } catch (e: Exception) {
                toast("Échec : ${e.message}")
            }
        }
    }

    private fun editBackups() {
        val box = android.widget.EditText(this).apply {
            setText(Prefs.backups); hint = "http://adresse2.com:8080 (une par ligne)"
            minLines = 3; setSingleLine(false)
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.FrameLayout(this).apply { setPadding(pad, 0, pad, 0); addView(box) }
        AlertDialog.Builder(this)
            .setTitle("Adresses de secours")
            .setMessage("Celles que ton fournisseur t'a données. Mêmes identifiants, autre adresse.")
            .setView(wrap)
            .setPositiveButton("Enregistrer") { _, _ ->
                Prefs.backups = Repo.candidates("", "", box.text.toString()).joinToString("\n")
                ad.notifyDataSetChanged()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun logout() {
        AlertDialog.Builder(this)
            .setTitle("Changer de compte ?")
            .setMessage("Tes favoris et réglages sont conservés.")
            .setPositiveButton("Déconnexion") { _, _ ->
                Prefs.logout(); Repo.loaded = false
                startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
            .setNegativeButton("Annuler", null)
            .showTv(android.content.DialogInterface.BUTTON_NEGATIVE)  // « Annuler » d'abord : pas de déconnexion par erreur
    }
}
