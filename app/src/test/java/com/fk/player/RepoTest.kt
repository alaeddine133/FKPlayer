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

    @Test fun tri() {
        fun f(n: String, added: Long, r: String?) = Item(Type.MOVIE, n, n, null, "c", rating = r, added = added)
        val l = listOf(f("zorro", 2, "6,5"), f("Été", 3, null), f("avatar", 1, "8"))
        assertEquals(listOf("Été", "zorro", "avatar"), Repo.sorted(l, Repo.SORT_RECENT).map { it.name })
        assertEquals(listOf("avatar", "Été", "zorro"), Repo.sorted(l, Repo.SORT_AZ).map { it.name })
        assertEquals(listOf("zorro", "Été", "avatar"), Repo.sorted(l, Repo.SORT_ZA).map { it.name })
        assertEquals(listOf("avatar", "zorro", "Été"), Repo.sorted(l, Repo.SORT_RATING).map { it.name })
    }

    @Test fun adresses() {
        assertEquals("http://a.com:8080", Repo.origin("a.com:8080/player_api.php?x=1"))
        assertEquals("https://b.tv", Repo.origin("https://b.tv/"))
        assertNull(Repo.origin("  "))
        // Adresse annoncée : port selon le protocole, « 0 » = pas de port
        assertEquals("http://new.tv:80", Repo.announced("http", "new.tv", "80", "443"))
        assertEquals("https://new.tv:443", Repo.announced("https", "new.tv", "80", "443"))
        assertEquals("http://new.tv", Repo.announced("", "new.tv", "0", ""))
        assertNull(Repo.announced("http", "", "80", ""))
        // Ordre : actuelle, annoncée, secours ; sans doublon ni ligne vide
        assertEquals(listOf("http://old.tv:80", "http://new.tv:80", "http://s1.tv:8080", "https://s2.tv"),
            Repo.candidates("http://old.tv:80", "http://new.tv:80", "s1.tv:8080\n\nhttp://old.tv:80, https://s2.tv"))
    }

    @Test fun similaires() {
        // Langue, qualité, année et numéro ne comptent pas
        assertEquals(setOf("fast", "furious"), Repo.titleWords("FR - Fast & Furious 7 (2015) 4K MULTI"))
        assertEquals(setOf("action", "sf", "comedie"), Repo.genres("Action, Science-Fiction / Comedy"))

        val idf = { w: String -> if (w == "nuit") 0.3 else 2.0 }  // « nuit » : mot courant
        fun p(n: String, g: String = "", who: String = "", y: Int = 0) = Repo.Profile(Repo.titleWords(n), Repo.genres(g), Repo.people(who), y)
        val me = p("Fast & Furious 7", "Action, Thriller", "Vin Diesel, Paul Walker, James Wan", 2015)
        val saga = p("Fast & Furious 8", "", "", 2017)
        val memeGenre = p("John Wick", "Action, Thriller", "Keanu Reeves", 2014)
        val memeActeur = p("Riddick", "Science-Fiction", "Vin Diesel", 2013)
        val rien = p("Le Roi Lion", "Animation, Famille", "Jon Favreau", 2019)
        val s = listOf(saga, memeGenre, memeActeur, rien).map { Repo.closeness(me, it, idf) }
        org.junit.Assert.assertTrue("ordre attendu : saga > même genre > même acteur : $s", s[0] > s[1] && s[1] > s[2] && s[2] > 0)
        assertEquals(0.0, s[3], 0.0)  // aucun rapport : pas affiché
        // Un seul mot courant en commun ne fait pas une saga
        assertEquals(0.0, Repo.closeness(p("La nuit des morts vivants"), p("Une nuit au musée"), idf), 0.0)
    }

    @Test(expected = java.io.IOException::class) fun pasUneM3u() { Repo.m3u(sequenceOf("<html>erreur</html>")) }
}
