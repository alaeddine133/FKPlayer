package com.fk.player

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Mise à jour depuis GitHub : version.json indique la dernière version et le lien de l'APK.
 * Android impose une dernière confirmation (bouton « Installer ») : impossible de l'éviter sans root.
 */
object Updater {
    private const val INFO = "https://raw.githubusercontent.com/alaeddine133/FKPlayer/main/version.json"
    private var lastCheck = 0L
    private var busy = false

    fun check(a: BaseActivity, manual: Boolean = false) {
        if (busy || (!manual && System.currentTimeMillis() - lastCheck < 6 * 3600_000L)) return
        lastCheck = System.currentTimeMillis()
        a.lifecycleScope.launch {
            val o = try { JSONObject(Repo.get(INFO + "?t=" + System.currentTimeMillis() / 600_000)) } catch (e: Exception) {
                if (manual) a.toast("Impossible de vérifier la mise à jour"); return@launch
            }
            val cur = PackageInfoCompat.getLongVersionCode(a.packageManager.getPackageInfo(a.packageName, 0))
            if (o.optLong("code") <= cur) { if (manual) a.toast("FK PLAYER est à jour"); return@launch }
            if (a.isFinishing) return@launch
            AlertDialog.Builder(a)
                .setTitle("Nouvelle version ${o.optString("name")} disponible")
                .setMessage(o.optString("notes").ifBlank { "Améliorations et corrections." })
                .setPositiveButton("Mettre à jour") { _, _ -> install(a, o.optString("url")) }
                .setNegativeButton("Plus tard", null)
                .showTv()
        }
    }

    private fun install(a: BaseActivity, url: String) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            // Certaines box (Fire TV) n'ont pas cette page : on donne aussi le chemin à la main
            AlertDialog.Builder(a).setTitle("Autorisation nécessaire (une seule fois)")
                .setMessage("Autorise FK PLAYER à installer des applis, puis relance la mise à jour.\n\n" +
                    "Fire TV : Paramètres → Ma Fire TV → Options pour les développeurs → Installer des applis inconnues → FK PLAYER\n\n" +
                    "Android TV : Paramètres → Applications → Accès spécial → Installer des applis inconnues → FK PLAYER")
                .setPositiveButton("Ouvrir les réglages") { _, _ ->
                    try { a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}"))) }
                    catch (_: Exception) { a.toast("Ouvre les réglages de la box à la main") }
                }.setNegativeButton("Plus tard", null).showTv()
            return
        }
        busy = true
        val dlg = AlertDialog.Builder(a).setTitle("Mise à jour").setMessage("Téléchargement… 0 %").setCancelable(false).show()
        a.lifecycleScope.launch {
            val dir = if (Build.VERSION.SDK_INT >= 24) a.cacheDir else (a.externalCacheDir ?: a.cacheDir)
            val f = File(dir, "update.apk")
            try {
                withContext(Dispatchers.IO) {
                    Repo.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (!r.isSuccessful) throw Exception("code ${r.code}")
                        val body = r.body ?: throw Exception("vide")
                        val total = body.contentLength()
                        body.byteStream().use { inp -> f.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024); var done = 0L; var last = -1
                            while (true) {
                                val n = inp.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                val pc = if (total > 0) (done * 100 / total).toInt() else -1
                                if (pc != last) { last = pc; a.runOnUiThread { dlg.setMessage("Téléchargement… $pc %") } }
                            }
                        } }
                    }
                }
                dlg.dismiss()
                val uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(a, "${a.packageName}.files", f) else Uri.fromFile(f)
                a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                dlg.dismiss()
                a.toast("Mise à jour impossible : ${e.message}")
            } finally { busy = false }
        }
    }
}
