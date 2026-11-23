package com.fk.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Formats envoyés par les fournisseurs : bande-annonce YouTube et heure du replay. */
class RepoTest {
    @Test fun youtube() {
        assertEquals("dQw4w9WgXcQ", Repo.ytId("dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", Repo.ytId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=3"))
        assertEquals("dQw4w9WgXcQ", Repo.ytId("https://youtu.be/dQw4w9WgXcQ?si=x"))
        assertEquals("dQw4w9WgXcQ", Repo.ytId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertNull(Repo.ytId(""))
        assertNull(Repo.ytId("pas de bande annonce"))
    }

    @Test fun replayStart() {
        assertEquals("2026-11-23:20-45", Repo.tsStart("2026-11-23 20:45:00"))
        assertEquals("2026-11-23:07-05", Repo.tsStart("2026-11-23T07:05"))
        assertNull(Repo.tsStart(""))
        assertNull(Repo.tsStart("20:45"))
    }

    @Test fun m3u() {
        val list = """
            #EXTM3U
            #EXTINF:-1 tvg-logo="http://l/tf1.png" group-title="FR",TF1 HD
            http://srv/live/u/p/1.ts
            #EXTINF:-1 group-title="Films",Le Film
            http://srv/movie/u/p/2.mkv
            #EXTINF:-1 group-title="Séries",Épisode 1
            http://srv/series/u/p/3.mp4
        """.trimIndent()
        val a = Repo.m3u(list.lineSequence())
        assertEquals(listOf("TF1 HD"), a.l.map { it.name })
        assertEquals("http://l/tf1.png", a.l[0].icon)
        assertEquals(listOf("Le Film"), a.m.map { it.name })
        assertEquals(listOf("Épisode 1"), a.s.map { it.name })
        assertEquals(listOf("FR"), a.lc.map { it.name })
        // Même identifiant à chaque lecture, même si une ligne est ajoutée avant
        val b = Repo.m3u(("#EXTM3U\n#EXTINF:-1,Nouvelle\nhttp://srv/live/u/p/9.ts\n" + list.substringAfter("\n")).lineSequence())
        assertEquals(a.l[0].id, b.l.first { it.name == "TF1 HD" }.id)
    }

    @Test(expected = java.io.IOException::class) fun pasUneM3u() { Repo.m3u(sequenceOf("<html>erreur</html>")) }
}
