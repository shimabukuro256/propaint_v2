package com.propaint.app.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.propaint.app.engine.CanvasDocument
import com.propaint.app.engine.PixelOps
import com.propaint.app.engine.ProjectFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class GalleryItem(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val modifiedAt: Long,
    val filePath: String,
    val thumbnailPath: String,
)

class GalleryRepository(private val context: Context) {

    private val projectsDir: File get() = File(context.filesDir, "projects").also { it.mkdirs() }
    private val thumbnailsDir: File get() = File(context.cacheDir, "thumbnails").also { it.mkdirs() }

    /** 全プロジェクト一覧を更新日時の降順で取得 */
    fun listProjects(): List<GalleryItem> {
        val metaFile = File(projectsDir, "gallery_meta.txt")
        if (!metaFile.exists()) return emptyList()
        return metaFile.readLines().mapNotNull { line ->
            parseMeta(line)
        }.sortedByDescending { it.modifiedAt }
    }

    /** 新規プロジェクト作成 → id を返す */
    fun createProject(name: String, width: Int, height: Int): String {
        val id = UUID.randomUUID().toString().take(12)
        val doc = CanvasDocument(width, height)
        val file = File(projectsDir, "$id.ppaint")
        FileOutputStream(file).use { ProjectFile.save(doc, it) }
        // サムネイル生成 (白キャンバス)
        generateThumbnail(id, doc)
        // メタデータ追記
        appendMeta(id, name, width, height)
        return id
    }

    /** プロジェクトを保存 (上書き) + サムネイル更新 + メタデータ解像度同期 */
    fun saveProject(id: String, doc: CanvasDocument) {
        val file = File(projectsDir, "$id.ppaint")
        FileOutputStream(file).use { ProjectFile.save(doc, it) }
        generateThumbnail(id, doc)
        updateMeta(id, doc.width, doc.height)
    }

    /** プロジェクトを読み込み */
    fun loadProject(id: String): CanvasDocument? {
        val file = File(projectsDir, "$id.ppaint")
        if (!file.exists()) return null
        return FileInputStream(file).use { ProjectFile.load(it) }
    }

    /** プロジェクトを削除 */
    fun deleteProject(id: String) {
        File(projectsDir, "$id.ppaint").delete()
        File(thumbnailsDir, "$id.jpg").delete()
        removeMeta(id)
    }

    /** プロジェクト名を変更 */
    fun renameProject(id: String, newName: String) {
        val metaFile = File(projectsDir, "gallery_meta.txt")
        if (!metaFile.exists()) return
        val lines = metaFile.readLines().map { line ->
            val parts = line.split("|")
            if (parts.size >= 6 && parts[0] == id) {
                "$id|$newName|${parts[2]}|${parts[3]}|${parts[4]}|${System.currentTimeMillis()}"
            } else line
        }
        metaFile.writeText(lines.joinToString("\n"))
    }

    /** サムネイルの Bitmap を取得 */
    fun loadThumbnail(id: String): Bitmap? {
        val file = File(thumbnailsDir, "$id.jpg")
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    fun getProjectFile(id: String): File = File(projectsDir, "$id.ppaint")

    // ── 内部 ──

    private fun generateThumbnail(id: String, doc: CanvasDocument) {
        val pixels = doc.getCompositePixels()
        for (i in pixels.indices) pixels[i] = PixelOps.unpremultiply(pixels[i])
        val bmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, doc.width, 0, 0, doc.width, doc.height)
        // リサイズ (長辺 256px)
        val scale = 256f / maxOf(doc.width, doc.height)
        val tw = maxOf(1, (doc.width * scale).toInt())
        val th = maxOf(1, (doc.height * scale).toInt())
        val thumb = Bitmap.createScaledBitmap(bmp, tw, th, true)
        bmp.recycle()
        val file = File(thumbnailsDir, "$id.jpg")
        FileOutputStream(file).use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        thumb.recycle()
    }

    private fun appendMeta(id: String, name: String, width: Int, height: Int) {
        val metaFile = File(projectsDir, "gallery_meta.txt")
        val line = "$id|$name|${width}x${height}|${width}|${height}|${System.currentTimeMillis()}"
        metaFile.appendText(line + "\n")
    }

    /** メタデータの解像度とタイムスタンプを更新 */
    private fun updateMeta(id: String, width: Int, height: Int) {
        val metaFile = File(projectsDir, "gallery_meta.txt")
        if (!metaFile.exists()) return
        val lines = metaFile.readLines().map { line ->
            val parts = line.split("|")
            if (parts.size >= 6 && parts[0] == id) {
                "${parts[0]}|${parts[1]}|${width}x${height}|$width|$height|${System.currentTimeMillis()}"
            } else line
        }
        metaFile.writeText(lines.joinToString("\n"))
    }

    private fun removeMeta(id: String) {
        val metaFile = File(projectsDir, "gallery_meta.txt")
        if (!metaFile.exists()) return
        val lines = metaFile.readLines().filter { !it.startsWith("$id|") }
        metaFile.writeText(lines.joinToString("\n"))
    }

    private fun parseMeta(line: String): GalleryItem? {
        val parts = line.split("|")
        if (parts.size < 5) return null
        val id = parts[0]
        val name = parts[1]
        // 旧形式 (5フィールド) と新形式 (6フィールド) の両方をサポート
        val w: Int; val h: Int; val mod: Long
        if (parts.size >= 6) {
            w = parts[3].toIntOrNull() ?: return null
            h = parts[4].toIntOrNull() ?: return null
            mod = parts[5].toLongOrNull() ?: 0L
        } else {
            // 旧形式: id|name|WxH|width|timestamp (heightフィールド欠落)
            // WxH パースにフォールバック
            val res = parts[2].split("x")
            w = res.getOrNull(0)?.toIntOrNull() ?: return null
            h = res.getOrNull(1)?.toIntOrNull() ?: return null
            mod = parts.getOrNull(4)?.toLongOrNull() ?: parts.getOrNull(3)?.toLongOrNull() ?: 0L
        }
        val file = File(projectsDir, "$id.ppaint")
        if (!file.exists()) return null
        return GalleryItem(id, name, w, h, mod, file.absolutePath,
            File(thumbnailsDir, "$id.jpg").absolutePath)
    }

    companion object {
        fun formatDate(millis: Long): String {
            val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
            return fmt.format(Date(millis))
        }
    }
}
