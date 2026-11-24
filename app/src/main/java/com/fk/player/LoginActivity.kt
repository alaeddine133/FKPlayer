package com.fk.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Écran de démarrage : ouverture instantanée si un compte existe (listes gardées sur la box),
 * sinon formulaire + QR code pour saisir ses codes depuis le téléphone.
 */
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
    private var link: LinkServer? = null
    private var job: Job? = null

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
        if (Prefs.mode.isNotEmpty()) connect(preferCache = true) else showForm(null)
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
        // Le clavier à l'écran ne doit pas cacher le message d'erreur
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(form.windowToken, 0)
        startLink()
        val target: View = if (useM3u) m3u else if (server.text.isEmpty()) server else findViewById<View>(R.id.connect)
        target.requestFocus()
    }

    /** Lance le serveur local et affiche le QR code. */
    private fun startLink() {
        if (link != null) return
        val l = LinkServer { data -> runOnUiThread { fromPhone(data) } }
        // Le serveur d'abord : l'adresse du QR contient le port qu'il a obtenu
        val url = if (l.start()) l.url() else null
        val qr = findViewById<ImageView>(R.id.qr)
        val qrUrl = findViewById<TextView>(R.id.qrUrl)
        if (url == null) {
            l.stop()
            findViewById<View>(R.id.qrBox).alpha = 0.4f
            qrUrl.text = "Wi-Fi introuvable : connecte la box au réseau"
            return
        }
        link = l
        qrUrl.text = url.removePrefix("http://")
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.Default) { LinkServer.qr(url, 420) }
            qr.setImageBitmap(bmp)
        }
    }

    private fun stopLink() { link?.stop(); link = null }

    /** Codes reçus depuis le téléphone. */
    private fun fromPhone(d: Map<String, String>) {
        if (d["mode"] == "m3u") {
            setTab(true); m3u.setText(d["m3u"] ?: "")
        } else {
            setTab(false)
            server.setText(d["server"] ?: ""); user.setText(d["user"] ?: ""); pass.setText(d["pass"] ?: "")
        }
        toast("Codes reçus du téléphone")
        submit()
    }

    private fun submit() {
        if (job?.isActive == true) return
        if (useM3u) {
            val linkTxt = m3u.text.toString().trim()
            if (!linkTxt.startsWith("http")) { status.text = "Colle un lien M3U qui commence par http"; return }
            val x = Repo.xtreamFromM3u(linkTxt)
            if (x != null) {
                // Lien get.php = compte Xtream : plus rapide, avec guide TV et séries complètes
                Prefs.mode = "xtream"; Prefs.newServer(x.first); Prefs.user = x.second; Prefs.pass = x.third
            } else {
                Prefs.mode = "m3u"
            }
            Prefs.m3u = linkTxt
        } else {
            val s = server.text.toString(); val u = user.text.toString().trim(); val p = pass.text.toString().trim()
            if (s.isBlank() || u.isBlank() || p.isBlank()) { status.text = "Remplis le serveur, l'utilisateur et le mot de passe"; return }
            Prefs.newServer(Repo.cleanServer(s)); Prefs.user = u; Prefs.pass = p; Prefs.mode = "xtream"
        }
        connect(preferCache = false)
    }

    private fun connect(preferCache: Boolean) {
        form.visibility = View.GONE; splash.visibility = View.VISIBLE
        splashStatus.text = "Connexion au serveur…"
        job = lifecycleScope.launch {
            try {
                Repo.load(preferCache) { msg -> runOnUiThread { splashStatus.text = msg } }
                if (Repo.live.isEmpty() && Repo.vod.isEmpty() && Repo.series.isEmpty()) throw Exception("aucun contenu trouvé sur ce compte")
                stopLink()
                startActivity(Intent(this@LoginActivity, HomeActivity::class.java).putExtra("refresh", Repo.fromCache))
                finish()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e  // écran quitté : ne pas relancer le serveur QR
                // On garde le compte enregistré : il suffit de réessayer si c'était une coupure réseau
                showForm("Connexion impossible : ${e.message ?: "erreur réseau"}")
            }
        }
    }

    override fun onDestroy() {
        stopLink()
        super.onDestroy()
    }
}
