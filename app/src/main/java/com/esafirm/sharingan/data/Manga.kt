package com.esafirm.sharingan.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ReadStatus(val label: String) {
    UNREAD("Unread"),
    READING("Reading"),
    COMPLETED("Completed"),
}

@Entity(tableName = "manga")
data class Manga(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** File name inside [MangaRepository.pdfDir]. */
    val fileName: String,
    /** File name inside [MangaRepository.coverDir], null if cover rendering failed. */
    val coverName: String?,
    val pageCount: Int,
    /** Zero-based index of the last page the reader settled on. */
    val lastPage: Int = 0,
    val status: ReadStatus = ReadStatus.UNREAD,
    val addedAt: Long,
    val lastReadAt: Long = 0,
    /** SHA-256 of the PDF bytes, used to skip re-imports of the same file. */
    val contentHash: String? = null,
) {
    val progress: Float
        get() = if (pageCount <= 1) 0f else (lastPage.toFloat() / (pageCount - 1)).coerceIn(0f, 1f)
}
