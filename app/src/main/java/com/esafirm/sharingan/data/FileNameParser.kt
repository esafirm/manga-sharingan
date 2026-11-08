package com.esafirm.sharingan.data

/** What we could figure out from a file name like `20th_Century_Boys_Ch156-160_upscaled2x`. */
data class ParsedName(
    val series: String,
    val key: String,
    val volume: Double? = null,
    val volumeEnd: Double? = null,
    val chapter: Double? = null,
    val chapterEnd: Double? = null,
    /** A bare trailing number ("Naruto 12") when no Vol/Ch marker is present. */
    val number: Double? = null,
    val numberEnd: Double? = null,
) {
    /** Short human label: "Vol. 3 · Ch. 12", "Ch. 156–160", "#7". Empty if nothing was detected. */
    val label: String
        get() {
            val parts = mutableListOf<String>()
            volume?.let { parts += "Vol. ${range(it, volumeEnd)}" }
            chapter?.let { parts += "Ch. ${range(it, chapterEnd)}" }
            if (parts.isEmpty() && number != null) parts += "#${range(number, numberEnd)}"
            return parts.joinToString(" · ")
        }

    val isVolumeBased: Boolean get() = volume != null || (chapter == null && number != null)

    private fun range(a: Double, b: Double?) = if (b == null) fmt(a) else "${fmt(a)}–${fmt(b)}"
    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
}

object FileNameParser {
    /**
     * All patterns live in their own holder so a pattern problem can never crash class loading of
     * [FileNameParser]. NOTE: Android compiles these with the ICU engine, which is stricter than the
     * JVM's (e.g. a bare `}` or `]` is an error), so every special character is escaped explicitly.
     */
    private object Patterns {
        val brackets = Regex("""\[[^\]]*\]|\([^\)]*\)|\{[^\}]*\}""")
        val dotNotBetweenDigits = Regex("""(?<!\d)\.|\.(?!\d)""")
        val spaces = Regex("""\s+""")
        val pdfExt = Regex("""(?i)\.pdf$""")

        val marker = Regex(
            """(?<![\p{L}\d])(chapters?|chap|ch|episodes?|ep|volumes?|vol|tome|book|bk|c|v)(?![\p{L}])\s*(\d+(?:\.\d+)?)""" +
                """(?:\s*(?:-|–|—|~|to)\s*(?:(?:chapters?|chap|ch|c|volumes?|vol|v)\s*)?(\d+(?:\.\d+)?))?""",
            RegexOption.IGNORE_CASE,
        )

        val trailingNumber = Regex("""^(.*?)[\s\-–:#]+(\d+(?:\.\d+)?)(?:\s*[\-–~]\s*(\d+(?:\.\d+)?))?$""")

        val tagTail = Regex(
            """(?i)[\s\-–—:,]+(upscaled?\d*x?|upscale|\d+x|x\d+|digital|scans?|raw|hq|hd|colou?red|\d{3,4}p|repack|complete|official|fixed)$""",
        )
    }

    private val volumeWords = setOf("volume", "volumes", "vol", "tome", "book", "bk", "v")

    /** Never throws: if anything goes wrong we just fall back to the plain file name. */
    fun parse(rawTitle: String): ParsedName =
        try {
            parseInternal(rawTitle)
        } catch (t: Throwable) {
            val plain = rawTitle.replace('_', ' ').trim().ifEmpty { rawTitle }
            ParsedName(plain, plain.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { rawTitle.lowercase() })
        }

    private fun parseInternal(rawTitle: String): ParsedName {
        val cleaned = clean(rawTitle)

        var volume: Double? = null
        var volumeEnd: Double? = null
        var chapter: Double? = null
        var chapterEnd: Double? = null
        var number: Double? = null
        var numberEnd: Double? = null
        var titlePart = cleaned

        val matches = Patterns.marker.findAll(cleaned).toList()
        if (matches.isNotEmpty()) {
            var titleEnd = cleaned.length
            for (m in matches) {
                val word = m.groupValues[1].lowercase()
                val a = m.groupValues[2].toDouble()
                val b = m.groupValues[3].takeIf { it.isNotEmpty() }?.toDouble()
                if (word in volumeWords) {
                    if (volume == null) { volume = a; volumeEnd = b }
                } else if (chapter == null) {
                    chapter = a; chapterEnd = b
                }
                titleEnd = minOf(titleEnd, m.range.first)
            }
            titlePart = cleaned.substring(0, titleEnd)
        } else {
            Patterns.trailingNumber.find(cleaned)?.let { m ->
                titlePart = m.groupValues[1]
                number = m.groupValues[2].toDouble()
                numberEnd = m.groupValues[3].takeIf { it.isNotEmpty() }?.toDouble()
            }
        }

        var series = tidy(stripTags(titlePart))
        if (series.isBlank()) series = tidy(stripTags(cleaned)).ifBlank { rawTitle.replace('_', ' ').trim() }

        val key = series.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { rawTitle.lowercase() }
        return ParsedName(series, key, volume, volumeEnd, chapter, chapterEnd, number, numberEnd)
    }

    private fun clean(raw: String): String =
        raw.replace(Patterns.pdfExt, "")
            .replace(Patterns.brackets, " ")
            .replace('_', ' ')
            .replace(Patterns.dotNotBetweenDigits, " ")
            .replace(Patterns.spaces, " ")
            .trim()

    private fun stripTags(s: String): String {
        var cur = s.trim()
        while (true) {
            val next = Patterns.tagTail.replace(cur, "").trim()
            if (next == cur) return cur
            cur = next
        }
    }

    private fun tidy(s: String) = s.trim { it.isWhitespace() || it in "-–—:,._" }
}

/** Splits into alternating digit / non-digit runs (no regex). */
private fun chunks(s: String): List<String> {
    val out = ArrayList<String>()
    var start = 0
    for (i in 1..s.length) {
        if (i == s.length || s[i].isDigit() != s[i - 1].isDigit()) {
            if (i > start) out.add(s.substring(start, i))
            start = i
        }
    }
    return out
}

/** Case-insensitive "natural" order: "Ch 2" < "Ch 10". */
fun naturalCompare(a: String, b: String): Int {
    val ca = chunks(a)
    val cb = chunks(b)
    for (i in 0 until minOf(ca.size, cb.size)) {
        val x = ca[i]
        val y = cb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) {
            val tx = x.trimStart('0')
            val ty = y.trimStart('0')
            if (tx.length != ty.length) tx.length - ty.length else tx.compareTo(ty)
        } else {
            x.compareTo(y, ignoreCase = true)
        }
        if (c != 0) return c
    }
    return ca.size - cb.size
}
