package com.esafirm.sharingan.data.source

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MangaDexParserTest {
    @Test fun searchResults() {
        val json = JSONObject(
            """
            {"result":"ok","data":[
              {"id":"m1","type":"manga","attributes":{"title":{"en":"One-Punch Man"},"altTitles":[],"status":"ongoing","year":2012},
               "relationships":[{"id":"a","type":"author"},{"id":"c","type":"cover_art","attributes":{"fileName":"cover.jpg"}}]},
              {"id":"m2","type":"manga","attributes":{"title":{"ja":"ワンパンマン"},"altTitles":[{"ja-ro":"Wanpanman"}],"status":null,"year":null},
               "relationships":[]}
            ]}
            """,
        )
        val r = MangaDexParser.searchResults(json)
        assertEquals(2, r.size)
        assertEquals("One-Punch Man", r[0].title)
        assertEquals("https://uploads.mangadex.org/covers/m1/cover.jpg.256.jpg", r[0].coverUrl)
        assertEquals("Ongoing · 2012", r[0].subtitle)
        assertEquals("ワンパンマン", r[1].title)
        assertNull(r[1].coverUrl)
        assertNull(r[1].subtitle)
    }

    @Test fun chaptersSkipExternalAndDedupe() {
        val json = JSONObject(
            """
            {"data":[
              {"id":"c1","attributes":{"volume":"1","chapter":"1","title":"One Punch","externalUrl":null},
               "relationships":[{"type":"scanlation_group","attributes":{"name":"Group A"}}]},
              {"id":"c1b","attributes":{"volume":"1","chapter":"1","title":null,"externalUrl":null},"relationships":[]},
              {"id":"c2","attributes":{"volume":null,"chapter":"2.5","title":"","externalUrl":null},"relationships":[]},
              {"id":"c3","attributes":{"volume":null,"chapter":"3","title":null,"externalUrl":"https://mangaplus.example/x"},"relationships":[]},
              {"id":"c4","attributes":{"volume":null,"chapter":null,"title":"Special","externalUrl":null},"relationships":[]}
            ],"total":5}
            """,
        )
        val all = MangaDexParser.chapters(json)
        assertEquals(listOf("c1", "c1b", "c2", "c4"), all.map { it.id })
        assertEquals("Vol. 1 Ch. 1 – One Punch", all[0].name)
        assertEquals("Group A", all[0].group)
        assertEquals("Ch. 2.5", all[2].name)
        assertEquals("Oneshot – Special", all[3].name)
        val deduped = MangaDexParser.dedupe(all)
        assertEquals(listOf("c1", "c2", "c4"), deduped.map { it.id })
    }

    @Test fun pageUrls() {
        val json = JSONObject("""{"baseUrl":"https://h.example","chapter":{"hash":"abc","data":["1.png","2.png"]}}""")
        assertEquals(listOf("https://h.example/data/abc/1.png", "https://h.example/data/abc/2.png"), MangaDexParser.pageUrls(json))
    }
}
