package com.esafirm.sharingan.data

import java.io.File

data class SeriesItem(val manga: Manga, val parsed: ParsedName) {
    val displayLabel: String get() = parsed.label.ifBlank { manga.title }
    /** 0..1 reading progress, treating Completed as 1 and Unread as 0. */
    val fraction: Float
        get() = when (manga.status) {
            ReadStatus.COMPLETED -> 1f
            ReadStatus.UNREAD -> 0f
            ReadStatus.READING -> manga.progress
        }
}

/** Everything that belongs to one title: volumes/chapters sorted in reading order. */
data class Series(
    val key: String,
    val title: String,
    val items: List<SeriesItem>,
    val coverFile: File?,
) {
    val sortTitle: String = title.lowercase().removePrefix("the ")
    val lastReadAt: Long = items.maxOf { it.manga.lastReadAt }
    val addedAt: Long = items.maxOf { it.manga.addedAt }
    val completed: Int = items.count { it.manga.status == ReadStatus.COMPLETED }
    val reading: Int = items.count { it.manga.status == ReadStatus.READING }
    val unread: Int = items.count { it.manga.status == ReadStatus.UNREAD }

    val status: ReadStatus =
        when {
            completed == items.size -> ReadStatus.COMPLETED
            unread == items.size -> ReadStatus.UNREAD
            else -> ReadStatus.READING
        }

    val progress: Float = (items.sumOf { it.fraction.toDouble() } / items.size).toFloat()

    val unitName: String = if (items.count { it.parsed.isVolumeBased } * 2 >= items.size) "volume" else "chapter"

    /** What "Continue" should open: the book in progress, else the first unread, else the first. */
    val continueItem: SeriesItem =
        items.filter { it.manga.status == ReadStatus.READING }.maxByOrNull { it.manga.lastReadAt }
            ?: items.firstOrNull { it.manga.status == ReadStatus.UNREAD }
            ?: items.first()
}

private val itemOrder: Comparator<SeriesItem> =
    compareBy<SeriesItem>(
        { it.parsed.volume ?: it.parsed.number ?: Double.MAX_VALUE },
        { it.parsed.chapter ?: Double.MAX_VALUE },
    ).thenComparator { a, b -> naturalCompare(a.manga.title, b.manga.title) }

fun buildSeries(mangas: List<Manga>, customCovers: Map<String, String?>, coverDir: File): List<Series> =
    mangas.map { SeriesItem(it, FileNameParser.parse(it.title)) }
        .groupBy { it.parsed.key }
        .map { (key, items) ->
            val sorted = items.sortedWith(itemOrder)
            val title = sorted.groupingBy { it.parsed.series }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.length })
                .first().key
            val custom = customCovers[key]?.let { File(coverDir, it) }?.takeIf { it.exists() }
            val cover = custom ?: sorted.firstNotNullOfOrNull { it.manga.coverName }?.let { File(coverDir, it) }
            Series(key, title, sorted, cover)
        }

fun List<Series>.nextAfter(id: Long): SeriesItem? {
    val s = firstOrNull { ser -> ser.items.any { it.manga.id == id } } ?: return null
    val i = s.items.indexOfFirst { it.manga.id == id }
    return s.items.getOrNull(i + 1)
}
