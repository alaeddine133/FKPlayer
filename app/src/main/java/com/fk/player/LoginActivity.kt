package com.fk.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Écran de démarrage : connexion automatique si un compte existe, sinon formulaire. */
class LoginActivity : BaseActivity() {

    private lateinit var splash: View
    private lateinit var splashStatus: TextView
    private lateinit var form: View
    private lateinit var tabX: TextView
    private lateinit var tabM: TextView
    private lateinit var server: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var m3u: EditText
    private lateinit var status: TextView
    private var useM3u = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_login)
        splash = findViewById(R.id.splash); splashStatus = findViewById(R.id.splashStatus)
        form = findViewById(R.id.form); status = findViewById(R.id.status)
        tabX = findViewById(R.id.tabXtream); tabM = findViewById(R.id.tabM3u)
        server = findViewById(R.id.server); user = findViewById(R.id.user); pass = findViewById(R.id.pass); m3u = findViewById(R.id.m3u)

        server.setText(Prefs.server); user.setText(Prefs.user); pass.setText(Prefs.pass); m3u.setText(Prefs.m3u)
        tabX.setOnClickListener { setTab(false) }
        tabM.setOnClickListener { setTab(true) }
        findViewById<View>(R.id.connect).setOnClickListener { submit() }
        pass.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
        m3u.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
        setTab(Prefs.mode == "m3u")

        findViewById<View>(R.id.splashLogo).animate().rotationBy(360f).setDuration(1200).start()
        if (Prefs.mode.isNotEmpty()) connect() else showForm(null)
    }

    private fun setTab(m: Boolean) {
        useM3u = m
        tabX.isActivated = !m; tabM.isActivated = m
        listOf(server, user, pass).forEach { it.visibility = if (m) View.GONE else View.VISIBLE }
        m3u.visibility = if (m) View.VISIBLE else View.GONE
    }

    private fun showForm(err: String?) {
        splash.visibility = View.GONE; form.visibility = View.VISIBLE
        status.text = err ?: ""
        val target: View = if (useM3u) m3u else if (server.text.isEmpty()) server else findViewById<View>(R.id.connect)
        target.requestFocus()
    }

    private fun submit() {
        if (useM3u) {
            val link = m3u.text.toString().trim()
            if (!link.startsWith("http")) { status.text = "Colle un lien M3U qui commence par http"; return }
            val x = Repo.xtreamFromM3u(link)
            if (x != null) {
                // Lien get.php = compte Xtream : plus rapide, avec guide TV et séries complètes
                Prefs.mode = "xtream"; Prefs.server = x.first; Prefs.user = x.second; Prefs.pass = x.third
            } else {
                Prefs.mode = "m3u"
            }
            Prefs.m3u = link
        } else {
            val s = server.text.toString(); val u = user.text.toString().trim(); val p = pass.text.toString().trim()
            if (s.isBlank() || u.isBlank() || p.isBlank()) { status.text = "Remplis le serveur, l'utilisateur et le mot de passe"; return }
            Prefs.server = Repo.cleanServer(s); Prefs.user = u; Prefs.pass = p; Prefs.mode = "xtream"
        }
        connect()
    }

    private fun connect() {
        form.visibility = View.GONE; splash.visibility = View.VISIBLE
        splashStatus.text = "Connexion au serveur…"
        lifecycleScope.launch {
            try {
                Repo.login()
                Repo.loadAll { msg -> runOnUiThread { splashStatus.text = msg } }
                if (Repo.live.isEmpty() && Repo.vod.isEmpty() && Repo.series.isEmpty()) throw Exception("aucun contenu trouvé sur ce compte")
                startActivity(Intent(this@LoginActivity, HomeActivity::class.java))
                finish()
            } catch (e: Exception) {
                Prefs.mode = ""
                showForm("Connexion impossible : ${e.message ?: "erreur réseau"}")
            }
        }
    }
}
