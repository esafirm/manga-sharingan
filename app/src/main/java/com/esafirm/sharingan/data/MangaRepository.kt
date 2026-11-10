package com.esafirm.sharingan.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InputStream
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
        .addMigrations(object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `series` (`seriesKey` TEXT NOT NULL, `coverName` TEXT, PRIMARY KEY(`seriesKey`))")
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
    suspend fun import(uri: Uri): Result<Manga> =
        importFrom(title = { displayName(uri) }) { context.contentResolver.openInputStream(uri)!! }

    /** Adds a PDF that's already on disk (e.g. one built by the web scraper) under [title]. */
    suspend fun importFile(file: File, title: String): Result<Manga> =
        importFrom(title = { title.trim().ifEmpty { file.nameWithoutExtension } }) { file.inputStream() }

    private suspend fun importFrom(title: () -> String, open: () -> InputStream): Result<Manga> = withContext(Dispatchers.IO) {
        val fileName = "${UUID.randomUUID()}.pdf"
        val target = File(pdfDir, fileName)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            open().use { raw ->
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
                title = title(),
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

    // ---- Series covers -------------------------------------------------------------------

    fun observeSeriesMeta(): Flow<List<SeriesMeta>> = dao.observeSeriesMeta()

    private val coverMutex = Mutex()

    /**
     * Looks up covers online for series that have never been checked. Runs sequentially and
     * politely; stops quietly when offline so it simply retries on a later launch.
     */
    suspend fun fetchMissingCovers(wanted: Map<String, String>) = coverMutex.withLock {
        withContext(Dispatchers.IO) {
            for ((key, title) in wanted) {
                if (dao.getSeriesMeta(key) != null) continue
                try {
                    val best = CoverFinder.bestMatch(title, CoverFinder.search(title))
                    if (best != null) saveSeriesCover(key, CoverFinder.download(best.imageUrl))
                    else dao.upsertSeriesMeta(SeriesMeta(key, null))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    return@withContext // offline / rate limited: try again later
                } catch (e: Exception) {
                    dao.upsertSeriesMeta(SeriesMeta(key, null))
                }
                delay(400)
            }
        }
    }

    suspend fun setSeriesCoverFromUrl(key: String, url: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { saveSeriesCover(key, CoverFinder.download(url)) }
    }

    suspend fun setSeriesCoverFromUri(key: String, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            saveSeriesCover(key, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

    /** Back to the first page of the first volume (and don't auto-search again). */
    suspend fun clearSeriesCover(key: String) = withContext(Dispatchers.IO) { replaceSeriesCover(key, null) }

    private suspend fun saveSeriesCover(key: String, bytes: ByteArray) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 720) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Not a valid image")
        val name = "series_${UUID.randomUUID()}.jpg"
        File(coverDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        replaceSeriesCover(key, name)
    }

    private suspend fun replaceSeriesCover(key: String, name: String?) {
        val old = dao.getSeriesMeta(key)?.coverName
        dao.upsertSeriesMeta(SeriesMeta(key, name))
        if (old != null && old != name) File(coverDir, old).delete()
    }

    suspend fun markAllCompleted(items: List<Manga>) = items.forEach { markCompleted(it) }

    suspend fun markAllUnread(items: List<Manga>) = items.forEach { markUnread(it) }

    suspend fun deleteSeries(key: String, items: List<Manga>) {
        items.forEach { delete(it) }
        withContext(Dispatchers.IO) {
            dao.getSeriesMeta(key)?.coverName?.let { File(coverDir, it).delete() }
            dao.deleteSeriesMeta(key)
        }
    }

    private fun statusFor(page: Int, count: Int) = when {
        page >= count - 1 -> ReadStatus.COMPLETED
        page > 0 -> ReadStatus.READING
        else -> ReadStatus.UNREAD
    }
}
