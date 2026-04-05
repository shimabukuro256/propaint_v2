package com.propaint.app.engine

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PSD (Adobe Photoshop Document) エクスポーター。
 *
 * 対応機能:
 *  - 8bit/channel RGBA レイヤー
 *  - レイヤー名 (Unicode luni)、不透明度、可視性
 *  - クリッピングマスク (isClipToBelow)
 *  - 主要ブレンドモード
 *  - RLE 圧縮チャンネルデータ
 */
object PsdExporter {

    fun export(doc: CanvasDocument, output: OutputStream) {
        val w = doc.width
        val h = doc.height
        val layers = doc.layers

        PaintDebug.d(PaintDebug.Layer) {
            "[PsdExporter] width=$w height=$h layers=${layers.size}"
        }

        require(w > 0 && h > 0) { "[PsdExporter] invalid canvas size: ${w}x${h}" }

        if (layers.isEmpty()) {
            PaintDebug.d(PaintDebug.Layer) { "[PsdExporter] WARNING: no layers to export" }
        }

        val buf = ByteArrayOutputStream(w * h * 4)
        val out = PsdOutputStream(buf)

        // ── File Header ──
        out.writeInt(0x38425053) // "8BPS"
        out.writeShort(1) // version
        out.writeZeros(6) // reserved
        out.writeShort(4) // channels (RGBA)
        out.writeInt(h)
        out.writeInt(w)
        out.writeShort(8) // depth (8 bits/channel)
        out.writeShort(3) // color mode (RGB)

        // ── Color Mode Data ──
        out.writeInt(0)

        // ── Image Resources ──
        out.writeInt(0)

        // ── Layer and Mask Information ──
        // この部分全体の長さをあとで埋める
        val layerMaskLenPos = buf.size()
        out.writeInt(0) // placeholder

        // ── Layer Info ──
        val layerInfoLenPos = buf.size()
        out.writeInt(0) // placeholder

        out.writeShort(layers.size) // layer count

        // ── 各レイヤーのピクセルデータを事前に抽出 ──
        data class LayerPixels(
            val red: ByteArray, val green: ByteArray,
            val blue: ByteArray, val alpha: ByteArray,
            val top: Int, val left: Int, val bottom: Int, val right: Int,
        )

        val layerPixelsList = ArrayList<LayerPixels>(layers.size)

        for (layer in layers) {
            // レイヤーの有効バウンディングボックスを計算
            val pixels = layer.content.toPixelArray()
            var minX = w; var minY = h; var maxX = 0; var maxY = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val c = pixels[y * w + x]
                    if (PixelOps.alpha(c) > 0) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            // 空レイヤーは全域 0 として扱う
            if (maxX < minX) { minX = 0; minY = 0; maxX = 0; maxY = 0 }
            else { maxX++; maxY++ } // exclusive

            val lw = maxX - minX
            val lh = maxY - minY
            val red = ByteArray(lw * lh)
            val green = ByteArray(lw * lh)
            val blue = ByteArray(lw * lh)
            val alpha = ByteArray(lw * lh)

            for (y in 0 until lh) {
                for (x in 0 until lw) {
                    val c = pixels[(minY + y) * w + (minX + x)]
                    // premultiplied → straight alpha に変換
                    val straight = PixelOps.unpremultiply(c)
                    red[y * lw + x] = (PixelOps.red(straight) and 0xFF).toByte()
                    green[y * lw + x] = (PixelOps.green(straight) and 0xFF).toByte()
                    blue[y * lw + x] = (PixelOps.blue(straight) and 0xFF).toByte()
                    alpha[y * lw + x] = (PixelOps.alpha(straight) and 0xFF).toByte()
                }
            }

            layerPixelsList.add(LayerPixels(red, green, blue, alpha, minY, minX, maxY, maxX))
        }

        // ── Layer Records ──
        // 各レイヤーのチャンネルデータを RLE 圧縮して長さを事前計算
        data class CompressedChannel(val data: ByteArray)

        val allCompressed = ArrayList<List<CompressedChannel>>(layers.size)

        for (i in layers.indices) {
            val lp = layerPixelsList[i]
            val lw = lp.right - lp.left
            val lh = lp.bottom - lp.top
            val channels = listOf(lp.alpha, lp.red, lp.green, lp.blue)
            val compressed = channels.map { chData ->
                CompressedChannel(compressRLE(chData, lw, lh))
            }
            allCompressed.add(compressed)
        }

        for (i in layers.indices) {
            val layer = layers[i]
            val lp = layerPixelsList[i]
            val compressed = allCompressed[i]

            // Rectangle
            out.writeInt(lp.top)
            out.writeInt(lp.left)
            out.writeInt(lp.bottom)
            out.writeInt(lp.right)

            // Channel count
            out.writeShort(4) // A, R, G, B

            // Channel info: id + data length (compression type 2 bytes + compressed data)
            val chIds = intArrayOf(-1, 0, 1, 2) // alpha, red, green, blue
            for (c in 0 until 4) {
                out.writeShort(chIds[c])
                out.writeInt(compressed[c].data.size + 2) // +2 for compression type
            }

            // Blend mode signature
            out.writeInt(0x3842494D) // "8BIM"
            // Blend mode key
            out.writeBytes(blendModeToKey(layer.blendMode))
            // Opacity
            out.writeByte((layer.opacity * 255f).toInt().coerceIn(0, 255))
            // Clipping
            out.writeByte(if (layer.isClipToBelow) 1 else 0)
            // Flags
            var flags = 0
            if (!layer.isVisible) flags = flags or 0x02
            out.writeByte(flags)
            // Filler
            out.writeByte(0)

            // Extra data length
            val layerName = layer.name
            val nameBytes = layerName.toByteArray(Charsets.ISO_8859_1)
            // Pascal string: 1 byte length + name + padding to 4-byte boundary
            val pascalLen = 1 + nameBytes.size
            val paddedPascalLen = ((pascalLen + 3) / 4) * 4

            // luni block size: 4 (sig) + 4 (key) + 4 (len) + 4 (unicode len) + name.length * 2
            val luniDataLen = 4 + layerName.length * 2
            val luniBlockLen = 4 + 4 + 4 + luniDataLen

            val extraLen = 4 + 4 + paddedPascalLen + luniBlockLen // mask(4) + blendRanges(4) + name + luni
            out.writeInt(extraLen)

            // Layer mask data length
            out.writeInt(0)

            // Layer blending ranges
            out.writeInt(0)

            // Layer name (Pascal string)
            out.writeByte(nameBytes.size)
            out.writeRawBytes(nameBytes)
            // Padding
            val pad = paddedPascalLen - pascalLen
            for (p in 0 until pad) out.writeByte(0)

            // Unicode name (luni)
            out.writeInt(0x3842494D) // "8BIM"
            out.writeBytes("luni")
            out.writeInt(luniDataLen)
            out.writeInt(layerName.length)
            for (ch in layerName) {
                out.writeShort(ch.code)
            }
        }

        // ── Channel Image Data ──
        for (i in layers.indices) {
            val compressed = allCompressed[i]
            for (c in 0 until 4) {
                out.writeShort(1) // compression type: RLE
                out.writeRawBytes(compressed[c].data)
            }
        }

        // Layer info length を埋める
        val layerInfoEnd = buf.size()
        val layerInfoLen = layerInfoEnd - layerInfoLenPos - 4
        patchInt(buf, layerInfoLenPos, layerInfoLen)

        // Layer and Mask info length を埋める
        val layerMaskEnd = buf.size()
        val layerMaskLen = layerMaskEnd - layerMaskLenPos - 4
        patchInt(buf, layerMaskLenPos, layerMaskLen)

        // ── Image Data (composite) ──
        // Photoshop は composite image data を必須とする
        out.writeShort(0) // compression: raw
        val composite = doc.getCompositePixels()
        // R, G, B, A の順でチャンネルを書き出し
        for (ch in 0 until 4) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val c = PixelOps.unpremultiply(composite[y * w + x])
                    val v = when (ch) {
                        0 -> PixelOps.red(c)
                        1 -> PixelOps.green(c)
                        2 -> PixelOps.blue(c)
                        else -> PixelOps.alpha(c)
                    }
                    out.writeByte(v)
                }
            }
        }

        val totalBytes = buf.size()
        PaintDebug.d(PaintDebug.Layer) {
            "[PsdExporter] writing $totalBytes bytes to output"
        }
        buf.writeTo(output)
        output.flush()
    }

    // ── RLE (PackBits) 圧縮 ──

    private fun compressRLE(data: ByteArray, w: Int, h: Int): ByteArray {
        if (w == 0 || h == 0) return ByteArray(h * 2) // scanline byte counts only

        val result = ByteArrayOutputStream(data.size)
        val scanlineLens = IntArray(h)
        val scanlineData = Array(h) { ByteArray(0) }

        for (y in 0 until h) {
            val rowOff = y * w
            val rowBuf = ByteArrayOutputStream(w + w / 128 + 2)
            var x = 0
            while (x < w) {
                // run を探す
                var runLen = 1
                while (x + runLen < w && runLen < 128 &&
                    data[rowOff + x + runLen] == data[rowOff + x]) {
                    runLen++
                }
                if (runLen >= 3) {
                    // RLE run
                    rowBuf.write((-(runLen - 1)) and 0xFF)
                    rowBuf.write(data[rowOff + x].toInt() and 0xFF)
                    x += runLen
                } else {
                    // Literal run
                    var litLen = 1
                    while (x + litLen < w && litLen < 128) {
                        // 次の 2 バイトが同じならリテラル終了 (run に切り替え)
                        if (x + litLen + 1 < w &&
                            data[rowOff + x + litLen] == data[rowOff + x + litLen + 1]) {
                            break
                        }
                        litLen++
                    }
                    rowBuf.write(litLen - 1)
                    for (j in 0 until litLen) {
                        rowBuf.write(data[rowOff + x + j].toInt() and 0xFF)
                    }
                    x += litLen
                }
            }
            scanlineData[y] = rowBuf.toByteArray()
            scanlineLens[y] = scanlineData[y].size
        }

        // scanline byte counts (2 bytes each)
        for (y in 0 until h) {
            result.write((scanlineLens[y] shr 8) and 0xFF)
            result.write(scanlineLens[y] and 0xFF)
        }
        // compressed data
        for (y in 0 until h) {
            result.write(scanlineData[y])
        }

        return result.toByteArray()
    }

    // ── ブレンドモード変換 ──

    private fun blendModeToKey(mode: Int): String = when (mode) {
        PixelOps.BLEND_NORMAL -> "norm"
        PixelOps.BLEND_MULTIPLY -> "mul "
        PixelOps.BLEND_SCREEN -> "scrn"
        PixelOps.BLEND_OVERLAY -> "over"
        PixelOps.BLEND_DARKEN -> "dark"
        PixelOps.BLEND_LIGHTEN -> "lite"
        PixelOps.BLEND_COLOR_DODGE -> "div "
        PixelOps.BLEND_COLOR_BURN -> "idiv"
        PixelOps.BLEND_HARD_LIGHT -> "hLit"
        PixelOps.BLEND_SOFT_LIGHT -> "sLit"
        PixelOps.BLEND_DIFFERENCE -> "diff"
        PixelOps.BLEND_EXCLUSION -> "smud"
        PixelOps.BLEND_ADD -> "lddg" // Linear Dodge = Add
        PixelOps.BLEND_SUBTRACT -> "fsub"
        PixelOps.BLEND_LINEAR_BURN -> "lbrn"
        PixelOps.BLEND_LINEAR_LIGHT -> "lLit"
        PixelOps.BLEND_VIVID_LIGHT -> "vLit"
        PixelOps.BLEND_PIN_LIGHT -> "pLit"
        PixelOps.BLEND_HUE -> "hue "
        PixelOps.BLEND_SATURATION -> "sat "
        PixelOps.BLEND_COLOR -> "colr"
        PixelOps.BLEND_LUMINOSITY -> "lum "
        else -> "norm"
    }

    // ── ByteArray パッチ ──

    private fun patchInt(bos: ByteArrayOutputStream, pos: Int, value: Int) {
        val arr = bos.toByteArray()
        arr[pos] = ((value shr 24) and 0xFF).toByte()
        arr[pos + 1] = ((value shr 16) and 0xFF).toByte()
        arr[pos + 2] = ((value shr 8) and 0xFF).toByte()
        arr[pos + 3] = (value and 0xFF).toByte()
        // ByteArrayOutputStream doesn't support random access,
        // so we need to rebuild. Use the writable buffer approach instead.
        bos.reset()
        bos.write(arr)
    }
}

/** PSD バイナリ書き込みヘルパー (Big-Endian) */
private class PsdOutputStream(private val out: ByteArrayOutputStream) {
    fun writeInt(v: Int) {
        out.write((v shr 24) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }
    fun writeShort(v: Int) {
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }
    fun writeByte(v: Int) { out.write(v and 0xFF) }
    fun writeZeros(n: Int) { for (i in 0 until n) out.write(0) }
    fun writeBytes(s: String) {
        for (c in s) out.write(c.code and 0xFF)
    }
    fun writeRawBytes(b: ByteArray) { out.write(b) }
}
