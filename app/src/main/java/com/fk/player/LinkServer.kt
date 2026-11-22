package com.fk.player

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Connexion depuis le téléphone.
 * La TV ouvre un mini serveur sur le Wi-Fi local ; le QR code mène à une page où l'on saisit ses codes.
 * Un jeton aléatoire dans l'adresse empêche les autres appareils du réseau d'envoyer des codes.
 */
class LinkServer(private val onReceive: (Map<String, String>) -> Unit) {

    var port = 0; private set
    val token: String = java.security.SecureRandom().let { r -> (1..6).map { "abcdefghjkmnpqrstuvwxyz23456789".random(kotlin.random.Random(r.nextLong())) } }.joinToString("")
    private var server: ServerSocket? = null
    @Volatile private var running = false

    fun url(): String? = localIp()?.let { "http://$it:$port/$token" }

    fun start(): Boolean {
        if (running) return true
        return try {
            // Port 8090 (adresse stable, facile à taper) ; s'il est déjà pris, Android en choisit un libre
            val s = try { ServerSocket(8090) } catch (e: Exception) { ServerSocket(0) }
            port = s.localPort
            server = s; running = true
            thread(name = "fk-link", isDaemon = true) {
                while (running) {
                    try { val c = s.accept(); thread(isDaemon = true) { handle(c) } } catch (e: Exception) { if (!running) break }
                }
            }
            true
        } catch (e: Exception) { false }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handle(c: Socket) = c.use { sock ->
        sock.soTimeout = 15_000
        val inp = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
        val first = inp.readLine() ?: return
        val parts = first.split(" ")
        val method = parts.getOrNull(0) ?: ""
        val path = parts.getOrNull(1) ?: "/"
        var len = 0
        while (true) {
            val h = inp.readLine() ?: break
            if (h.isEmpty()) break
            if (h.lowercase().startsWith("content-length:")) len = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val ok = path.trimEnd('/') == "/$token" || path.startsWith("/$token?")
        val html = when {
            !ok -> page("Lien expiré", "<p>Scanne à nouveau le QR code affiché sur la TV.</p>")
            method == "POST" -> {
                val buf = CharArray(len.coerceIn(0, 8192)); var read = 0
                while (read < buf.size) { val r = inp.read(buf, read, buf.size - read); if (r < 0) break; read += r }
                val form = String(buf, 0, read).split("&").mapNotNull {
                    val k = it.substringBefore("="); val v = it.substringAfter("=", "")
                    if (k.isBlank()) null else URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8").trim()
                }.toMap()
                onReceive(form)
                page("C'est envoyé !", "<p class='ok'>Regarde ta TV : FK PLAYER se connecte.</p><p>Tu peux fermer cette page.</p>")
            }
            else -> page("Connecter ma TV", FORM)
        }
        val body = html.toByteArray(Charsets.UTF_8)
        val out = sock.getOutputStream()
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body); out.flush()
    }

    private fun page(title: String, content: String) = """<!doctype html><html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>FK PLAYER</title><style>
:root{--bg:#07060D;--card:#14121F;--v:#8B5CF6;--c:#22D3EE;--t:#F5F3FF;--m:#B4B0C8}
*{box-sizing:border-box}body{margin:0;min-height:100vh;background:radial-gradient(ellipse at 10% 0%,rgba(139,92,246,.35),transparent 60%),radial-gradient(ellipse at 100% 100%,rgba(34,211,238,.18),transparent 60%),var(--bg);
color:var(--t);font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;padding:24px 16px}
.w{max-width:420px;margin:0 auto}.logo{display:flex;align-items:center;gap:10px;font-weight:900;letter-spacing:2px;font-size:20px;margin-bottom:22px}
.logo i{width:34px;height:34px;border-radius:9px;background:var(--card);border:1.5px solid #5B5675;display:grid;place-items:center;font-style:normal;font-size:13px;color:#E4E1F0}
h1{font-size:26px;margin:0 0 6px}p{color:var(--m);margin:6px 0 16px;line-height:1.45}
.card{background:var(--card);border:1px solid rgba(167,139,250,.25);border-radius:18px;padding:18px}
.tabs{display:flex;gap:8px;margin-bottom:12px}.tabs label{flex:1}.tabs input{display:none}
.tabs span{display:block;text-align:center;padding:11px;border-radius:22px;background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.1);font-weight:600}
.tabs input:checked+span{background:linear-gradient(90deg,#A78BFA,#67E8F9);color:#07060D;border-color:transparent}
input[type=text],input[type=url],input[type=password]{width:100%;padding:14px;margin:6px 0;border-radius:12px;border:1px solid rgba(255,255,255,.15);background:#0D0B18;color:var(--t);font-size:16px}
input:focus{outline:2px solid var(--c);border-color:transparent}
button{width:100%;margin-top:12px;padding:15px;border:0;border-radius:26px;font-weight:800;font-size:16px;color:#fff;background:linear-gradient(90deg,var(--v),var(--c))}
.ok{color:var(--c);font-size:18px;font-weight:700}#m3u{display:none}
</style></head><body><div class="w"><div class="logo"><i>FK</i>FK PLAYER</div><h1>$title</h1>$content</div></body></html>"""

    companion object {
        private val FORM = """<p>Entre les codes de ton abonnement : ils arrivent directement sur ta TV.</p>
<form method="post" class="card" autocomplete="off">
<div class="tabs"><label><input type="radio" name="mode" value="xtream" checked onchange="sw()"><span>Xtream Codes</span></label>
<label><input type="radio" name="mode" value="m3u" onchange="sw()"><span>Lien M3U</span></label></div>
<div id="x"><input type="url" name="server" placeholder="Serveur (http://exemple.com:8080)" inputmode="url" autocapitalize="off">
<input type="text" name="user" placeholder="Nom d'utilisateur" autocapitalize="off" autocorrect="off">
<input type="text" name="pass" placeholder="Mot de passe" autocapitalize="off" autocorrect="off"></div>
<div id="m3u"><input type="url" name="m3u" placeholder="Lien M3U (http://…)" inputmode="url" autocapitalize="off"></div>
<button type="submit">Envoyer à la TV</button></form>
<script>function sw(){var m=document.querySelector('input[name=mode]:checked').value;document.getElementById('x').style.display=m=='m3u'?'none':'block';document.getElementById('m3u').style.display=m=='m3u'?'block':'none'}</script>"""

        /** Adresse IPv4 de la box sur le réseau local (Wi-Fi ou Ethernet). */
        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        } catch (e: Exception) { null }

        fun qr(text: String, size: Int): Bitmap {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
            val px = IntArray(size * size)
            for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m[x, y]) Color.rgb(11, 9, 24) else Color.WHITE
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        }
    }
}
