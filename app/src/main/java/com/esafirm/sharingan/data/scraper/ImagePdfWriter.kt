package com.esafirm.sharingan.data.scraper

import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream

/** Size and colour layout of a baseline/progressive JPEG, read from its SOF header. */
data class JpegInfo(val width: Int, val height: Int, val components: Int)

object Jpeg {
    /** Null if [bytes] isn't a JPEG or its header is unreadable. */
    fun readInfo(bytes: ByteArray): JpegInfo? {
        fun u8(i: Int) = bytes[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        if (bytes.size < 4 || u8(0) != 0xFF || u8(1) != 0xD8) return null
        var i = 2
        while (i + 3 < bytes.size) {
            if (u8(i) != 0xFF) return null
            val marker = u8(i + 1)
            when {
                marker == 0xFF -> { i++; continue } // fill byte
                marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 -> { i += 2; continue } // no length
                marker == 0xD9 || marker == 0xDA -> return null // end / scan data before any frame header
            }
            val len = u16(i + 2)
            val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isSof) {
                if (i + 9 >= bytes.size) return null
                val h = u16(i + 5)
                val w = u16(i + 7)
                val c = u8(i + 9)
                return if (w > 0 && h > 0) JpegInfo(w, h, c) else null
            }
            if (len < 2) return null
            i += 2 + len
        }
        return null
    }
}

/**
 * Writes JPEG files as a PDF with one image per page, embedding the JPEG bytes untouched
 * (`/DCTDecode`), so there's no re-compression loss and the PDF is barely larger than the images.
 * Only 1- (grey) and 3-component (RGB/YCbCr) JPEGs are accepted; convert anything else first.
 */
object ImagePdfWriter {
    /** PDF viewers cap a page side at 14 400 points; larger images are scaled down to fit (pixels untouched). */
    private const val MAX_SIDE = 14_400.0

    fun write(pages: List<File>, out: OutputStream) {
        require(pages.isNotEmpty()) { "No pages" }
        val w = CountingStream(out.buffered(256 * 1024))
        val offsets = mutableListOf<Long>()
        fun obj(num: Int, body: () -> Unit) {
            check(offsets.size == num - 1) { "objects must be written in order" }
            offsets += w.count
            w.ascii("$num 0 obj\n")
            body()
            w.ascii("\nendobj\n")
        }

        w.ascii("%PDF-1.4\n")
        w.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

        // Object numbers are fixed up front: 1 catalog, 2 page tree, then (page, image, contents) per page.
        fun pageObj(i: Int) = 3 + i * 3
        obj(1) { w.ascii("<< /Type /Catalog /Pages 2 0 R >>") }
        obj(2) {
            val kids = pages.indices.joinToString(" ") { "${pageObj(it)} 0 R" }
            w.ascii("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>")
        }
        pages.forEachIndexed { i, file ->
            val bytes = file.readBytes()
            val info = Jpeg.readInfo(bytes) ?: throw IllegalArgumentException("${file.name} is not a JPEG")
            val colorSpace = when (info.components) {
                1 -> "/DeviceGray"
                3 -> "/DeviceRGB"
                else -> throw IllegalArgumentException("${file.name}: unsupported JPEG with ${info.components} components")
            }
            val scale = minOf(1.0, MAX_SIDE / maxOf(info.width, info.height))
            val pw = fmt(info.width * scale)
            val ph = fmt(info.height * scale)
            val p = pageObj(i)
            obj(p) {
                w.ascii(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pw $ph] " +
                        "/Resources << /XObject << /Im0 ${p + 1} 0 R >> >> /Contents ${p + 2} 0 R >>",
                )
            }
            obj(p + 1) {
                w.ascii(
                    "<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} " +
                        "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode /Length ${bytes.size} >>\nstream\n",
                )
                w.write(bytes)
                w.ascii("\nendstream")
            }
            obj(p + 2) {
                val content = "q $pw 0 0 $ph 0 0 cm /Im0 Do Q".toByteArray(Charsets.US_ASCII)
                w.ascii("<< /Length ${content.size} >>\nstream\n")
                w.write(content)
                w.ascii("\nendstream")
            }
        }

        val xref = w.count
        w.ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { w.ascii(String.format(java.util.Locale.US, "%010d 00000 n \n", it)) }
        w.ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        w.flush()
    }

    private fun fmt(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v)

    private class CountingStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b); count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len); count += len
        }

        fun ascii(s: String) = write(s.toByteArray(Charsets.US_ASCII))
    }
}
