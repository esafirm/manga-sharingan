package com.esafirm.sharingan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNameParserTest {
    @Test fun chapterRangeWithTags() {
        val p = FileNameParser.parse("20th_Century_Boys_Ch156-160_upscaled2x")
        assertEquals("20th Century Boys", p.series)
        assertEquals(156.0, p.chapter!!, 0.0)
        assertEquals(160.0, p.chapterEnd!!, 0.0)
        assertEquals("Ch. 156–160", p.label)
    }

    @Test fun sameSeriesDifferentSeparators() {
        val a = FileNameParser.parse("20th_Century_Boys_Ch156-160_upscaled2x")
        val b = FileNameParser.parse("20th Century Boys - Vol. 03 (Digital) [Group].pdf")
        assertEquals(a.key, b.key)
        assertEquals(3.0, b.volume!!, 0.0)
        assertEquals("Vol. 3", b.label)
    }

    @Test fun volumeAndChapter() {
        val p = FileNameParser.parse("Berserk v12 c100.5")
        assertEquals("Berserk", p.series)
        assertEquals(12.0, p.volume!!, 0.0)
        assertEquals(100.5, p.chapter!!, 0.0)
        assertEquals("Vol. 12 · Ch. 100.5", p.label)
    }

    @Test fun bareTrailingNumber() {
        val p = FileNameParser.parse("Fullmetal Alchemist 3")
        assertEquals("Fullmetal Alchemist", p.series)
        assertEquals(3.0, p.number!!, 0.0)
        assertEquals("#3", p.label)
    }

    @Test fun titleWordsAreNotMarkers() {
        val p = FileNameParser.parse("Century Vagabond Chapter 5")
        assertEquals("Century Vagabond", p.series)
        assertEquals(5.0, p.chapter!!, 0.0)
        assertNull(p.volume)
    }

    @Test fun noNumbersAtAll() {
        val p = FileNameParser.parse("One_Punch_Man")
        assertEquals("One Punch Man", p.series)
        assertEquals("", p.label)
    }

    @Test fun trailingTagStripped() {
        assertEquals("Dorohedoro", FileNameParser.parse("Dorohedoro upscaled 2x").series)
    }

    @Test fun naturalOrder() {
        assertTrue(naturalCompare("Ch 2", "Ch 10") < 0)
        assertTrue(naturalCompare("20th Century Boys", "7th Garden") > 0)
    }

    @Test fun coverMatching() {
        val r = listOf(
            CoverResult("Berserk of Gluttony", null, "u1", listOf("Berserk of Gluttony")),
            CoverResult("Berserk", null, "u2", listOf("Berserk")),
        )
        assertEquals("u2", CoverFinder.bestMatch("Berserk", r)!!.imageUrl)
        assertNull(CoverFinder.bestMatch("Totally Different", r))
    }
}
