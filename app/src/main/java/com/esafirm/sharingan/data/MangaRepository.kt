package com.esafirm.sharingan.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MangaRepository(private val context: Context) {
    private val dao = Room.databaseBuilder(context, AppDatabase::class.java, "sharingan.db")
        .addMigrations(object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE manga ADD COLUMN contentHash TEXT")
            }
        })
        .build().mangaDao()

    /** Outlives any screen so progress is never lost when the reader is torn down. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val pdfDir = File(context.filesDir, "pdf").apply { mkdirs() }
    val coverDir = File(context.filesDir, "covers").apply { mkdirs() }

    fun observeAll(): Flow<List<Manga>> = dao.observeAll()

    suspend fun get(id: Long): Manga? = dao.get(id)

    fun pdfFile(m: Manga) = File(pdfDir, m.fileName)

    /**
     * Copies the PDF into app storage (so later opens are plain local file reads, with no
     * SAF round-trips), reads the page count and renders a small cover.
     */
    suspend fun import(uri: Uri): Result<Manga> = withContext(Dispatchers.IO) {
        val fileName = "${UUID.randomUUID()}.pdf"
        val target = File(pdfDir, fileName)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)!!.use { raw ->
                DigestInputStream(raw, digest).use { input ->
                    target.outputStream().use { input.copyTo(it, 256 * 1024) }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            dao.findByHash(hash)?.let { existing ->
                target.delete()
                return@withContext Result.success(existing) // already in the library
            }
            val coverName = "${fileName.removeSuffix(".pdf")}.jpg"
            val pages = ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    renderCover(r, File(coverDir, coverName))
                    r.pageCount
                }
            }
            require(pages > 0) { "PDF has no pages" }
            val manga = Manga(
                title = displayName(uri),
                fileName = fileName,
                coverName = coverName.takeIf { File(coverDir, it).exists() },
                pageCount = pages,
                addedAt = System.currentTimeMillis(),
                contentHash = hash,
            )
            Result.success(manga.copy(id = dao.insert(manga)))
        } catch (t: Throwable) {
            target.delete()
            Result.failure(t)
        }
    }

    private fun renderCover(r: PdfRenderer, out: File) {
        runCatching {
            r.openPage(0).use { page ->
                val w = 360
                val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                bmp.recycle()
            }
        }
    }

    private fun displayName(uri: Uri): String {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: uri.lastPathSegment ?: "Untitled"
        return name.removeSuffix(".pdf").removeSuffix(".PDF")
    }

    /** Fire-and-forget; status is derived from the page. */
    fun saveProgress(m: Manga, page: Int) {
        val p = page.coerceIn(0, m.pageCount - 1)
        scope.launch { dao.updateProgress(m.id, p, statusFor(p, m.pageCount), System.currentTimeMillis()) }
    }

    suspend fun markUnread(m: Manga) = dao.updateProgress(m.id, 0, ReadStatus.UNREAD, m.lastReadAt)

    suspend fun markCompleted(m: Manga) =
        dao.updateProgress(m.id, m.pageCount - 1, ReadStatus.COMPLETED, System.currentTimeMillis())

    suspend fun rename(m: Manga, title: String) = dao.rename(m.id, title.trim().ifEmpty { m.title })

    suspend fun delete(m: Manga) = withContext(Dispatchers.IO) {
        dao.delete(m.id)
        pdfFile(m).delete()
        m.coverName?.let { File(coverDir, it).delete() }
    }

    private fun statusFor(page: Int, count: Int) = when {
        page >= count - 1 -> ReadStatus.COMPLETED
        page > 0 -> ReadStatus.READING
        else -> ReadStatus.UNREAD
    }
}
