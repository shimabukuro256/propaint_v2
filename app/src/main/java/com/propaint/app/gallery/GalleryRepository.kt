package com.propaint.app.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.propaint.app.engine.CanvasDocument
import com.propaint.app.engine.PixelOps
import com.propaint.app.engine.ProjectFile
import com.propaint.app.engine.PsdExporter
import com.propaint.app.engine.PsdImporter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
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

    /**
     * 既存プロジェクト名と衝突しない連番付き名前を生成する。
     * 例: "無題", "無題 (2)", "無題 (3)"
     */
    fun generateUniqueName(baseName: String): String {
        val existingNames = listProjects().map { it.name }.toSet()
        if (baseName !in existingNames) return baseName
        var n = 2
        while ("$baseName ($n)" in existingNames) n++
        return "$baseName ($n)"
    }

    /** 全プロジェクト一覧を更新日時の降順で取得 */
    fun listProjects(): List<GalleryItem> {
        val metaFile = File(projectsDir, "gallery_meta.txt")
        if (!metaFile.exists()) return emptyList()
        return metaFile.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> parseMeta(line) }
            .sortedByDescending { it.modifiedAt }
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
        val tmpFile = File(projectsDir, "$id.ppaint.tmp")
        try {
            // 一時ファイルに書き込み → 成功したらリネーム (書込み途中で壊れるのを防止)
            FileOutputStream(tmpFile).use { ProjectFile.save(doc, it) }
            if (tmpFile.length() > 0) {
                file.delete()
                tmpFile.renameTo(file)
            } else {
                tmpFile.delete()
            }
            generateThumbnail(id, doc)
            updateMeta(id, doc.width, doc.height)
        } catch (e: Exception) {
            tmpFile.delete()
            android.util.Log.e("GalleryRepo", "saveProject failed: ${e.message}", e)
        }
    }

    /** プロジェクトを読み込み */
    fun loadProject(id: String): CanvasDocument? {
        val file = File(projectsDir, "$id.ppaint")
        if (!file.exists()) return null
        return try {
            FileInputStream(file).use { ProjectFile.load(it) }
        } catch (e: Exception) {
            android.util.Log.e("GalleryRepo", "loadProject failed for $id: ${e.message}", e)
            null
        }
    }

    /** プロジェクトを削除 */
    fun deleteProject(id: String) {
        File(projectsDir, "$id.ppaint").delete()
        File(thumbnailsDir, "$id.jpg").delete()
        removeMeta(id)
    }

    /** プロジェクト名を変更（重複する場合は連番を付与） */
    fun renameProject(id: String, newName: String) {
        val metaFile = File(projectsDir, "gallery_meta.txt")
        if (!metaFile.exists()) return
        // 自分自身を除外して重複チェック
        val otherNames = listProjects().filter { it.id != id }.map { it.name }.toSet()
        var uniqueName = newName
        if (uniqueName in otherNames) {
            var n = 2
            while ("$newName ($n)" in otherNames) n++
            uniqueName = "$newName ($n)"
        }
        val lines = metaFile.readLines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split("|")
            if (parts.size >= 6 && parts[0] == id) {
                "$id|$uniqueName|${parts[2]}|${parts[3]}|${parts[4]}|${System.currentTimeMillis()}"
            } else line
        }
        metaFile.writeText(lines.joinToString("\n") + "\n")
    }

    /** サムネイルの Bitmap を取得 */
    fun loadThumbnail(id: String): Bitmap? {
        val file = File(thumbnailsDir, "$id.jpg")
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    fun getProjectFile(id: String): File = File(projectsDir, "$id.ppaint")

    // ── ギャラリーレベルのインポート/エクスポート ──

    /** 画像 (PNG/JPEG/WebP) をインポートして新規プロジェクトとして作成 */
    fun importImage(inputStream: InputStream, baseName: String = "インポート"): String? {
        val bmp = BitmapFactory.decodeStream(inputStream) ?: return null
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()
        val name = generateUniqueName(baseName)
        val doc = CanvasDocument(w, h)
        doc.importImageAsLayer(name, pixels, w, h)
        val id = UUID.randomUUID().toString().take(12)
        val file = File(projectsDir, "$id.ppaint")
        FileOutputStream(file).use { ProjectFile.save(doc, it) }
        generateThumbnail(id, doc)
        appendMeta(id, name, w, h)
        return id
    }

    /** PSD ファイルをインポートして新規プロジェクトとして作成 */
    fun importPsd(inputStream: InputStream, baseName: String = "PSD インポート"): String? {
        val doc = PsdImporter.import(inputStream) ?: return null
        val name = generateUniqueName(baseName)
        val id = UUID.randomUUID().toString().take(12)
        val file = File(projectsDir, "$id.ppaint")
        FileOutputStream(file).use { ProjectFile.save(doc, it) }
        generateThumbnail(id, doc)
        appendMeta(id, name, doc.width, doc.height)
        return id
    }

    /** プロジェクトファイル (.ppaint) をインポート */
    fun importProject(inputStream: InputStream, baseName: String = "プロジェクト"): String? {
        val doc = ProjectFile.load(inputStream) ?: return null
        val name = generateUniqueName(baseName)
        val id = UUID.randomUUID().toString().take(12)
        val file = File(projectsDir, "$id.ppaint")
        FileOutputStream(file).use { ProjectFile.save(doc, it) }
        generateThumbnail(id, doc)
        appendMeta(id, name, doc.width, doc.height)
        return id
    }

    /** プロジェクトを PNG としてエクスポート */
    fun exportPng(id: String, outputStream: OutputStream): Boolean {
        val doc = loadProject(id) ?: return false
        val pixels = doc.getCompositePixels()
        for (i in pixels.indices) pixels[i] = PixelOps.unpremultiply(pixels[i])
        val bmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, doc.width, 0, 0, doc.width, doc.height)
        bmp.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        bmp.recycle()
        return true
    }

    /** プロジェクトを JPEG としてエクスポート */
    fun exportJpeg(id: String, outputStream: OutputStream): Boolean {
        val doc = loadProject(id) ?: return false
        val pixels = doc.getCompositePixels()
        for (i in pixels.indices) pixels[i] = PixelOps.unpremultiply(pixels[i])
        val bmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, doc.width, 0, 0, doc.width, doc.height)
        val jpegBmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(jpegBmp)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(bmp, 0f, 0f, null)
        bmp.recycle()
        jpegBmp.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
        jpegBmp.recycle()
        return true
    }

    /** プロジェクトを PSD としてエクスポート */
    fun exportPsd(id: String, outputStream: OutputStream): Boolean {
        val doc = loadProject(id) ?: return false
        PsdExporter.export(doc, outputStream)
        return true
    }

    /** プロジェクトを .ppaint としてエクスポート */
    fun exportProject(id: String, outputStream: OutputStream): Boolean {
        val doc = loadProject(id) ?: return false
        ProjectFile.save(doc, outputStream)
        return true
    }

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
        val lines = metaFile.readLines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split("|")
            if (parts.size >= 6 && parts[0] == id) {
                "${parts[0]}|${parts[1]}|${width}x${height}|$width|$height|${System.currentTimeMillis()}"
            } else line
        }
        metaFile.writeText(lines.joinToString("\n") + "\n")
    }

    private fun removeMeta(id: String) {
        val metaFile = File(projectsDir, "gallery_meta.txt")
        if (!metaFile.exists()) return
        val lines = metaFile.readLines().filter { it.isNotBlank() && !it.startsWith("$id|") }
        metaFile.writeText(lines.joinToString("\n") + "\n")
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
