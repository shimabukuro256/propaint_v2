package com.propaint.app.engine

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PSD (Adobe Photoshop Document) インポーター。
 *
 * 対応機能:
 *  - 8bit/channel RGB/RGBA レイヤー
 *  - レイヤー名、不透明度、可視性
 *  - クリッピングマスク (isClipToBelow)
 *  - 主要ブレンドモード
 *  - RLE 圧縮レイヤーデータ
 *
 * 非対応 (将来拡張候補):
 *  - 16bit/32bit チャンネル
 *  - CMYK / Grayscale カラーモード
 *  - レイヤーマスク
 *  - グループレイヤー (フォルダ)
 *  - スマートオブジェクト
 *  - 調整レイヤー
 */
object PsdImporter {

    fun import(input: InputStream): CanvasDocument? {
        return try {
            val data = input.readBytes()
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            parsePsd(buf)
        } catch (e: Exception) {
            PaintDebug.e(PaintDebug.Layer, "[PsdImporter] failed: ${e.message}")
            null
        }
    }

    private fun parsePsd(buf: ByteBuffer): CanvasDocument? {
        // ── File Header Section ──
        val sig = buf.int
        if (sig != 0x38425053) { // "8BPS"
            PaintDebug.e(PaintDebug.Layer, "[PSD] invalid signature: ${Integer.toHexString(sig)}")
            return null
        }
        val version = buf.short.toInt()
        if (version != 1 && version != 2) {
            PaintDebug.e(PaintDebug.Layer, "[PSD] unsupported version: $version")
            return null
        }

        buf.position(buf.position() + 6) // reserved
        val channels = buf.short.toInt()
        val height = buf.int
        val width = buf.int
        val depth = buf.short.toInt() // bits per channel
        val colorMode = buf.short.toInt() // 3=RGB

        // 防御的チェック
        if (width <= 0 || width > 16384 || height <= 0 || height > 16384) {
            PaintDebug.e(PaintDebug.Layer, "[PSD] invalid size: ${width}x${height}")
            return null
        }
        if (depth != 8) {
            PaintDebug.e(PaintDebug.Layer, "[PSD] only 8-bit depth supported, got $depth")
            return null
        }
        if (colorMode != 3) { // 3 = RGB
            PaintDebug.e(PaintDebug.Layer, "[PSD] only RGB color mode supported, got $colorMode")
            return null
        }

        PaintDebug.d(PaintDebug.Layer) { "[PSD] header: ${width}x${height} ch=$channels depth=$depth mode=$colorMode" }

        // ── Color Mode Data Section ──
        val colorModeLen = buf.int
        buf.position(buf.position() + colorModeLen)

        // ── Image Resources Section ──
        val imgResLen = buf.int
        buf.position(buf.position() + imgResLen)

        // ── Layer and Mask Information Section ──
        val layerMaskLen = buf.int
        if (layerMaskLen == 0) {
            PaintDebug.e(PaintDebug.Layer, "[PSD] no layer data")
            return null
        }
        val layerMaskEnd = buf.position() + layerMaskLen

        // Layer Info
        val layerInfoLen = buf.int
        if (layerInfoLen == 0) {
            PaintDebug.e(PaintDebug.Layer, "[PSD] no layer info")
            return null
        }

        var layerCount = buf.short.toInt()
        val hasAlpha = layerCount < 0
        if (layerCount < 0) layerCount = -layerCount

        PaintDebug.d(PaintDebug.Layer) { "[PSD] layerCount=$layerCount hasAlpha=$hasAlpha" }

        data class PsdLayer(
            val top: Int, val left: Int, val bottom: Int, val right: Int,
            val channelCount: Int,
            val channelIds: IntArray, val channelDataLens: IntArray,
            val blendMode: String, val opacity: Int,
            val clipping: Int, // 0=base, 1=non-base (clipping)
            val flags: Int,
            val name: String,
        )

        val psdLayers = ArrayList<PsdLayer>(layerCount)

        // ── レイヤーレコード読み取り ──
        for (i in 0 until layerCount) {
            val top = buf.int; val left = buf.int
            val bottom = buf.int; val right = buf.int
            val chCount = buf.short.toInt()

            val chIds = IntArray(chCount)
            val chLens = IntArray(chCount)
            for (c in 0 until chCount) {
                chIds[c] = buf.short.toInt()
                chLens[c] = buf.int
            }

            val blendSig = buf.int // "8BIM"
            val blendKey = ByteArray(4); buf.get(blendKey)
            val blendModeStr = String(blendKey)
            val opacity = buf.get().toInt() and 0xFF
            val clipping = buf.get().toInt() and 0xFF
            val flags = buf.get().toInt() and 0xFF
            buf.get() // filler

            // Extra data
            val extraLen = buf.int
            val extraEnd = buf.position() + extraLen

            // Layer mask data
            val maskLen = buf.int
            buf.position(buf.position() + maskLen)

            // Layer blending ranges
            val blendRangeLen = buf.int
            buf.position(buf.position() + blendRangeLen)

            // Layer name (Pascal string, padded to 4 bytes)
            val nameLen = buf.get().toInt() and 0xFF
            val nameBytes = ByteArray(nameLen)
            buf.get(nameBytes)
            val paddedNameLen = ((nameLen + 1 + 3) / 4) * 4
            val skipBytes = paddedNameLen - nameLen - 1
            if (skipBytes > 0) buf.position(buf.position() + skipBytes)

            // Unicode name from additional layer info (if present)
            var layerName = String(nameBytes, Charsets.ISO_8859_1)

            // Skip remaining extra data (additional layer info blocks)
            // Search for luni (Unicode name) block
            val savedPos = buf.position()
            while (buf.position() + 12 < extraEnd) {
                val addSig = buf.int
                if (addSig != 0x3842494D && addSig != 0x38423634) { // "8BIM" or "8B64"
                    buf.position(buf.position() - 3) // try next byte
                    continue
                }
                val addKey = ByteArray(4); buf.get(addKey)
                val addKeyStr = String(addKey)
                val addLen = buf.int
                val addEnd = buf.position() + addLen
                if (addKeyStr == "luni" && buf.remaining() >= 4) {
                    val unicodeLen = buf.int
                    if (unicodeLen > 0 && unicodeLen < 256 && buf.remaining() >= unicodeLen * 2) {
                        val sb = StringBuilder()
                        for (u in 0 until unicodeLen) {
                            sb.append(buf.short.toInt().toChar())
                        }
                        layerName = sb.toString()
                    }
                }
                buf.position(minOf(addEnd, extraEnd))
            }

            buf.position(extraEnd)

            psdLayers.add(PsdLayer(top, left, bottom, right, chCount,
                chIds, chLens, blendModeStr, opacity, clipping, flags, layerName))
        }

        // ── チャンネルデータ読み取り ──
        val doc = CanvasDocument(width, height)
        val loadedLayers = ArrayList<Layer>()
        var nextId = 1

        for (psd in psdLayers) {
            val lw = psd.right - psd.left
            val lh = psd.bottom - psd.top

            // 空レイヤーやグループフォルダを除外
            if (lw <= 0 || lh <= 0) {
                // チャンネルデータを読み飛ばす
                for (c in 0 until psd.channelCount) {
                    buf.position(buf.position() + psd.channelDataLens[c])
                }
                continue
            }

            // チャンネルデータ読み取り
            val channelData = HashMap<Int, ByteArray>() // channelId -> pixel bytes

            for (c in 0 until psd.channelCount) {
                val chId = psd.channelIds[c]
                val chDataLen = psd.channelDataLens[c]
                val chStart = buf.position()

                if (chDataLen < 2) {
                    buf.position(chStart + chDataLen)
                    continue
                }

                val compression = buf.short.toInt()
                val pixels = ByteArray(lw * lh)

                when (compression) {
                    0 -> { // Raw
                        val toRead = minOf(lw * lh, chDataLen - 2)
                        buf.get(pixels, 0, toRead)
                    }
                    1 -> { // RLE (PackBits)
                        // RLE: 各行のバイトカウントを先に読む (lh 行分)
                        val scanlineLens = IntArray(lh)
                        for (row in 0 until lh) scanlineLens[row] = buf.short.toInt() and 0xFFFF

                        for (row in 0 until lh) {
                            val rowOff = row * lw
                            var col = 0
                            val rowEnd = rowOff + lw
                            while (col < lw && buf.hasRemaining()) {
                                val n = buf.get().toInt()
                                if (n >= 0) {
                                    // n+1 bytes literal
                                    val count = n + 1
                                    for (j in 0 until count) {
                                        if (col < lw && buf.hasRemaining()) {
                                            pixels[rowOff + col] = buf.get()
                                            col++
                                        }
                                    }
                                } else if (n != -128) {
                                    // 1-n+1 repetitions of next byte
                                    val count = -n + 1
                                    val v = if (buf.hasRemaining()) buf.get() else 0
                                    for (j in 0 until count) {
                                        if (col < lw) {
                                            pixels[rowOff + col] = v
                                            col++
                                        }
                                    }
                                }
                                // n == -128: no-op
                            }
                        }
                    }
                    else -> {
                        // Unsupported compression, skip
                        PaintDebug.e(PaintDebug.Layer, "[PSD] unsupported compression: $compression")
                        buf.position(chStart + chDataLen)
                        continue
                    }
                }

                // 読み残し/オーバーランの修正
                buf.position(chStart + chDataLen)
                channelData[chId] = pixels
            }

            // ピクセルを ARGB premultiplied タイルに書き込み
            val surface = TiledSurface(width, height)
            val red = channelData[0]
            val green = channelData[1]
            val blue = channelData[2]
            val alpha = channelData[-1]

            if (red != null && green != null && blue != null) {
                for (py in 0 until lh) {
                    val dstY = psd.top + py
                    if (dstY < 0 || dstY >= height) continue
                    for (px in 0 until lw) {
                        val dstX = psd.left + px
                        if (dstX < 0 || dstX >= width) continue
                        val srcIdx = py * lw + px
                        val r = red[srcIdx].toInt() and 0xFF
                        val g = green[srcIdx].toInt() and 0xFF
                        val b = blue[srcIdx].toInt() and 0xFF
                        val a = alpha?.let { it[srcIdx].toInt() and 0xFF } ?: 255
                        if (a == 0) continue
                        val straightArgb = PixelOps.pack(a, r, g, b)
                        val premulArgb = PixelOps.premultiply(straightArgb)

                        val tx = dstX / Tile.SIZE; val ty = dstY / Tile.SIZE
                        val tile = surface.getOrCreateMutable(tx, ty)
                        val lx = dstX - tx * Tile.SIZE; val ly = dstY - ty * Tile.SIZE
                        tile.pixels[ly * Tile.SIZE + lx] = premulArgb
                    }
                }
            }

            // ブレンドモード変換
            val blendMode = psdBlendModeToId(psd.blendMode)
            val isVisible = (psd.flags and 0x02) == 0 // bit 1 = hidden

            val layer = Layer(
                id = nextId++,
                name = psd.name,
                content = surface,
                opacity = psd.opacity / 255f,
                blendMode = blendMode,
                isVisible = isVisible,
                isClipToBelow = psd.clipping == 1,
            )
            loadedLayers.add(layer)
        }

        if (loadedLayers.isEmpty()) {
            PaintDebug.e(PaintDebug.Layer, "[PSD] no valid layers found")
            return null
        }

        doc.replaceAllLayers(loadedLayers, loadedLayers.last().id, nextId)
        PaintDebug.d(PaintDebug.Layer) { "[PSD] imported ${loadedLayers.size} layers, ${width}x${height}" }
        return doc
    }

    /** PSD ブレンドモードキー → PixelOps ブレンドモード ID */
    private fun psdBlendModeToId(key: String): Int = when (key) {
        "norm" -> PixelOps.BLEND_NORMAL
        "mul " -> PixelOps.BLEND_MULTIPLY
        "scrn" -> PixelOps.BLEND_SCREEN
        "over" -> PixelOps.BLEND_OVERLAY
        "dark" -> PixelOps.BLEND_DARKEN
        "lite" -> PixelOps.BLEND_LIGHTEN
        "div " -> PixelOps.BLEND_COLOR_DODGE
        "idiv" -> PixelOps.BLEND_COLOR_BURN
        "hLit" -> PixelOps.BLEND_HARD_LIGHT
        "sLit" -> PixelOps.BLEND_SOFT_LIGHT
        "diff" -> PixelOps.BLEND_DIFFERENCE
        "smud" -> PixelOps.BLEND_EXCLUSION
        "lbrn" -> PixelOps.BLEND_LINEAR_BURN
        "lddg" -> PixelOps.BLEND_LINEAR_BURN
        "vLit" -> PixelOps.BLEND_VIVID_LIGHT
        "lLit" -> PixelOps.BLEND_LINEAR_LIGHT
        "pLit" -> PixelOps.BLEND_PIN_LIGHT
        "hue " -> PixelOps.BLEND_HUE
        "sat " -> PixelOps.BLEND_SATURATION
        "colr" -> PixelOps.BLEND_COLOR
        "lum " -> PixelOps.BLEND_LUMINOSITY
        "pass" -> PixelOps.BLEND_NORMAL // pass-through (group)
        else -> {
            PaintDebug.d(PaintDebug.Layer) { "[PSD] unknown blend mode '$key', defaulting to Normal" }
            PixelOps.BLEND_NORMAL
        }
    }
}
