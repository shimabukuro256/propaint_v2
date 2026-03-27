package com.propaint.app.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ProPaint プロジェクトファイル (.ppaint) の保存・読込。
 *
 * フォーマット: ZIP アーカイブ
 *   - meta.json : キャンバスサイズ、レイヤー情報
 *   - layer_{id}.png : 各レイヤーの straight alpha PNG
 */
object ProjectFile {

    private const val META_FILE = "meta.json"
    private const val FORMAT_VERSION = 1

    /**
     * CanvasDocument をプロジェクトファイルとして保存。
     */
    fun save(doc: CanvasDocument, output: OutputStream) {
        PaintDebug.d(PaintDebug.Layer) { "[ProjectFile.save] width=${doc.width} height=${doc.height} layers=${doc.layers.size}" }

        val zos = ZipOutputStream(output)

        // メタデータ作成
        val meta = JSONObject().apply {
            put("version", FORMAT_VERSION)
            put("width", doc.width)
            put("height", doc.height)
            put("activeLayerId", doc.activeLayerId)
            val layersJson = JSONArray()
            for (layer in doc.layers) {
                layersJson.put(JSONObject().apply {
                    put("id", layer.id)
                    put("name", layer.name)
                    put("opacity", layer.opacity.toDouble())
                    put("blendMode", layer.blendMode)
                    put("isVisible", layer.isVisible)
                    put("isLocked", layer.isLocked)
                    put("isClipToBelow", layer.isClipToBelow)
                    put("isAlphaLocked", layer.isAlphaLocked)
                })
            }
            put("layers", layersJson)
        }

        // meta.json 書き込み
        zos.putNextEntry(ZipEntry(META_FILE))
        zos.write(meta.toString(2).toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        // 各レイヤーを PNG として保存
        for (layer in doc.layers) {
            val pixels = layer.content.toPixelArray()
            // premultiplied → straight alpha に変換
            for (i in pixels.indices) {
                pixels[i] = PixelOps.unpremultiply(pixels[i])
            }
            val bmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
            bmp.setPixels(pixels, 0, doc.width, 0, 0, doc.width, doc.height)

            zos.putNextEntry(ZipEntry("layer_${layer.id}.png"))
            bmp.compress(Bitmap.CompressFormat.PNG, 100, zos)
            zos.closeEntry()
            bmp.recycle()
        }

        zos.finish()
        zos.flush()
    }

    /**
     * プロジェクトファイルから CanvasDocument を復元。
     * @return 復元された CanvasDocument、読み込み失敗時は null
     */
    fun load(input: InputStream): CanvasDocument? {
        val entries = HashMap<String, ByteArray>()

        // ZIP エントリを全てメモリに読み込み
        val zis = ZipInputStream(input)
        var entry = zis.nextEntry
        while (entry != null) {
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var n: Int
            while (zis.read(buf).also { n = it } > 0) {
                bos.write(buf, 0, n)
            }
            entries[entry.name] = bos.toByteArray()
            zis.closeEntry()
            entry = zis.nextEntry
        }
        zis.close()

        // メタデータ解析
        val metaBytes = entries[META_FILE] ?: run {
            PaintDebug.e(PaintDebug.Layer, "[ProjectFile.load] meta.json not found")
            return null
        }
        val meta = JSONObject(String(metaBytes, Charsets.UTF_8))
        val version = meta.optInt("version", 0)
        if (version != FORMAT_VERSION) {
            PaintDebug.e(PaintDebug.Layer, "[ProjectFile.load] unsupported version: $version")
            return null
        }

        val width = meta.getInt("width")
        val height = meta.getInt("height")
        val activeLayerId = meta.getInt("activeLayerId")
        val layersJson = meta.getJSONArray("layers")

        // 防御的チェック
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384) {
            PaintDebug.e(PaintDebug.Layer, "[ProjectFile.load] invalid canvas size: ${width}x${height}")
            return null
        }

        PaintDebug.d(PaintDebug.Layer) { "[ProjectFile.load] width=$width height=$height layers=${layersJson.length()}" }

        // CanvasDocument を構築 (init のデフォルトレイヤーは後で置換)
        val doc = CanvasDocument(width, height)
        // デフォルトレイヤーを削除するため、まず全レイヤーを読み込んでから差し替え
        val loadedLayers = ArrayList<Layer>()
        var maxId = 0

        for (i in 0 until layersJson.length()) {
            val lj = layersJson.getJSONObject(i)
            val id = lj.getInt("id")
            if (id > maxId) maxId = id
            val name = lj.getString("name")
            val opacity = lj.getDouble("opacity").toFloat()
            val blendMode = lj.getInt("blendMode")
            val isVisible = lj.optBoolean("isVisible", true)
            val isLocked = lj.optBoolean("isLocked", false)
            val isClip = lj.optBoolean("isClipToBelow", false)
            val isAlphaLocked = lj.optBoolean("isAlphaLocked", false)

            // レイヤーPNG読み込み
            val pngBytes = entries["layer_${id}.png"]
            val surface = TiledSurface(width, height)
            if (pngBytes != null) {
                val bmp = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
                if (bmp != null) {
                    val px = IntArray(width * height)
                    bmp.getPixels(px, 0, width, 0, 0,
                        minOf(bmp.width, width), minOf(bmp.height, height))
                    bmp.recycle()
                    // straight alpha → premultiplied に変換してタイルに書き込み
                    writePremultipliedToSurface(surface, px, width, height)
                }
            }

            val layer = Layer(id, name, surface, opacity, blendMode, isVisible, isLocked, isClip, isAlphaLocked)
            loadedLayers.add(layer)
        }

        if (loadedLayers.isEmpty()) {
            PaintDebug.e(PaintDebug.Layer, "[ProjectFile.load] no layers found")
            return null
        }

        // doc の内部状態を直接差し替え
        doc.replaceAllLayers(loadedLayers, activeLayerId, maxId + 1)
        return doc
    }

    private fun writePremultipliedToSurface(surface: TiledSurface, pixels: IntArray, w: Int, h: Int) {
        for (ty in 0 until surface.tilesY) for (tx in 0 until surface.tilesX) {
            val bx = tx * Tile.SIZE; val by = ty * Tile.SIZE
            var hasPixel = false
            for (ly in 0 until Tile.SIZE) {
                val py = by + ly; if (py >= h) break
                for (lx in 0 until Tile.SIZE) {
                    val px = bx + lx; if (px >= w) break
                    if (pixels[py * w + px] != 0) { hasPixel = true; break }
                }
                if (hasPixel) break
            }
            if (!hasPixel) continue
            val tile = surface.getOrCreateMutable(tx, ty)
            for (ly in 0 until Tile.SIZE) {
                val py = by + ly; if (py >= h) break
                for (lx in 0 until Tile.SIZE) {
                    val px = bx + lx; if (px >= w) break
                    tile.pixels[ly * Tile.SIZE + lx] = PixelOps.premultiply(pixels[py * w + px])
                }
            }
        }
    }
}
