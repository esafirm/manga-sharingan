package com.esafirm.sharingan.data.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

class ImagePdfWriterTest {
    private fun jpeg(w: Int, h: Int, type: Int): ByteArray {
        val img = BufferedImage(w, h, type)
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    @Test fun readsJpegHeader() {
        assertEquals(JpegInfo(120, 340, 3), Jpeg.readInfo(jpeg(120, 340, BufferedImage.TYPE_INT_RGB)))
        assertEquals(JpegInfo(64, 32, 1), Jpeg.readInfo(jpeg(64, 32, BufferedImage.TYPE_BYTE_GRAY)))
        assertNull(Jpeg.readInfo(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertNull(Jpeg.readInfo(byteArrayOf()))
    }

    @Test fun writesAValidPdfWithOnePagePerImage() {
        val dir = createTempDir()
        try {
            val files = listOf(
                File(dir, "a.jpg").apply { writeBytes(jpeg(100, 150, BufferedImage.TYPE_INT_RGB)) },
                File(dir, "b.jpg").apply { writeBytes(jpeg(80, 20_000, BufferedImage.TYPE_BYTE_GRAY)) },
            )
            val bytes = ByteArrayOutputStream().also { ImagePdfWriter.write(files, it) }.toByteArray()
            val text = String(bytes, Charsets.ISO_8859_1)

            assertTrue(text.startsWith("%PDF-1.4\n"))
            assertTrue(text.trimEnd().endsWith("%%EOF"))
            assertTrue(text.contains("/Count 2"))
            assertTrue(text.contains("/MediaBox [0 0 100 150]"))
            assertTrue(text.contains("/ColorSpace /DeviceGray"))
            // A 20 000 pt tall page exceeds the PDF limit, so it's scaled down to 14 400.
            assertTrue(text.contains("/MediaBox [0 0 57.60 14400]"))

            // Every xref offset must point exactly at its object header.
            val startXref = text.substringAfterLast("startxref\n").substringBefore('\n').trim().toInt()
            assertTrue(text.startsWith("xref\n", startXref))
            val entries = text.substring(startXref).lines().drop(3).takeWhile { it.endsWith(" n ") }
            assertEquals(2 + 2 * 3, entries.size)
            entries.forEachIndexed { i, e ->
                val offset = e.substring(0, 10).toInt()
                assertTrue("object ${i + 1}", text.startsWith("${i + 1} 0 obj\n", offset))
            }
            // Each xref line is exactly 20 bytes, as the spec requires.
            assertTrue(entries.all { it.length + 1 == 20 })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonJpeg() {
        val dir = createTempDir()
        try {
            val f = File(dir, "x.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            ImagePdfWriter.write(listOf(f), ByteArrayOutputStream())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun createTempDir(): File = kotlin.io.path.createTempDirectory("pdfw").toFile()
}
