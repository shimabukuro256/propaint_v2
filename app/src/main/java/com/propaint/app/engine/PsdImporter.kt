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
 *  - レイヤーグループ (フォルダ) — lsct セクションディバイダー
 *  - 空（未描画）レイヤーの保持
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
        val depth = buf.short.toInt()
        val colorMode = buf.short.toInt()

        if (width <= 0 || width > 16384 || height <= 0 || height > 16384) {
            PaintDebug.e(PaintDebug.Layer, "[PSD] invalid size: ${width}x${height}")
            return null
        }
        if (depth != 8) {
            PaintDebug.e(PaintDebug.Layer, "[PSD] only 8-bit depth supported, got $depth")
            return null
        }
        if (colorMode != 3) {
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
            val clipping: Int,
            val flags: Int,
            val name: String,
            val lsctType: Int, // 0=not group, 1=open folder, 2=closed folder, 3=bounding section divider
            val lsctBlendMode: String?, // lsct 内のブレンドモード (null=なし)
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

            val blendSig = buf.int
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

            var layerName = String(nameBytes, Charsets.ISO_8859_1)
            var lsctType = 0
            var lsctBlendMode: String? = null

            // Additional layer info blocks を解析 (luni + lsct)
            while (buf.position() + 12 <= extraEnd) {
                val addSig = buf.int
                if (addSig != 0x3842494D && addSig != 0x38423634) { // "8BIM" or "8B64"
                    buf.position(buf.position() - 3)
                    continue
                }
                val addKey = ByteArray(4); buf.get(addKey)
                val addKeyStr = String(addKey)
                val addLen = buf.int
                val addEnd = buf.position() + addLen

                when (addKeyStr) {
                    "luni" -> {
                        if (buf.remaining() >= 4) {
                            val unicodeLen = buf.int
                            if (unicodeLen > 0 && unicodeLen < 256 && buf.remaining() >= unicodeLen * 2) {
                                val sb = StringBuilder()
                                for (u in 0 until unicodeLen) {
                                    sb.append(buf.short.toInt().toChar())
                                }
                                layerName = sb.toString()
                            }
                        }
                    }
                    "lsct", "lsdk" -> {
                        // Section divider setting
                        if (buf.remaining() >= 4) {
                            lsctType = buf.int
                            // lsct data が 12 バイト以上あればブレンドモードも読む
                            if (addLen >= 12 && buf.remaining() >= 8) {
                                buf.int // blend sig "8BIM"
                                val bk = ByteArray(4); buf.get(bk)
                                lsctBlendMode = String(bk)
                            }
                        }
                    }
                }
                buf.position(minOf(addEnd, extraEnd))
            }

            buf.position(extraEnd)

            psdLayers.add(PsdLayer(top, left, bottom, right, chCount,
                chIds, chLens, blendModeStr, opacity, clipping, flags, layerName,
                lsctType, lsctBlendMode))

            PaintDebug.d(PaintDebug.Layer) {
                "[PSD] layer[$i] name='$layerName' lsct=$lsctType bounds=($left,$top,$right,$bottom)"
            }
        }

        // ── チャンネルデータ読み取り ──
        val doc = CanvasDocument(width, height)
        val loadedLayers = ArrayList<Layer>()
        var nextId = 1
        var nextGroupId = 1

        // グループスタック: lsct=3 (GroupEnd) で開始、lsct=1or2 (GroupStart) で終了
        // PSD はボトム→トップ順で格納されるため:
        //   lsct=3 → グループ開始（子レイヤーがここから始まる）
        //   lsct=1or2 → グループ終了（ヘッダー）
        val groupStack = ArrayList<Int>() // 現在開いているグループIDのスタック

        for (psd in psdLayers) {
            val lw = psd.right - psd.left
            val lh = psd.bottom - psd.top

            // グループ終了マーカー (lsct=3) → 新しいグループを開始
            if (psd.lsctType == 3) {
                // チャンネルデータを読み飛ばす
                for (c in 0 until psd.channelCount) {
                    buf.position(buf.position() + psd.channelDataLens[c])
                }
                // グループID はまだ割り当てない（GroupStart で名前等を取得してから作成）
                // 仮の ID をスタックにプッシュ
                groupStack.add(-1) // placeholder
                continue
            }

            // グループ開始マーカー (lsct=1 or 2) → グループ情報を確定
            if (psd.lsctType == 1 || psd.lsctType == 2) {
                // チャンネルデータを読み飛ばす
                for (c in 0 until psd.channelCount) {
                    buf.position(buf.position() + psd.channelDataLens[c])
                }
                if (groupStack.isNotEmpty()) {
                    val gId = nextGroupId++
                    val blendMode = if (psd.lsctBlendMode != null) {
                        psdBlendModeToId(psd.lsctBlendMode)
                    } else {
                        psdBlendModeToId(psd.blendMode)
                    }
                    val isVisible = (psd.flags and 0x02) == 0
                    val isExpanded = psd.lsctType == 1

                    val group = LayerGroupInfo(
                        id = gId,
                        name = psd.name,
                        isExpanded = isExpanded,
                        isVisible = isVisible,
                        opacity = psd.opacity / 255f,
                        blendMode = blendMode,
                    )
                    doc.importLayerGroup(group)

                    // スタック内の placeholder を実際の groupId に更新
                    val stackIdx = groupStack.lastIndexOf(-1)
                    if (stackIdx >= 0) {
                        groupStack[stackIdx] = gId
                    }

                    // このグループ内のレイヤーに groupId を設定
                    // (スタックの最後の -1 → gId に変換済み)
                    // 子レイヤーは GroupEnd と GroupStart の間に配置されている
                    // → 既に追加済みのレイヤーで groupId == 0 かつスタック内にあるものを設定
                    for (layer in loadedLayers) {
                        if (layer.groupId == -(groupStack.size)) {
                            layer.groupId = gId
                        }
                    }

                    groupStack.removeAt(groupStack.size - 1)
                }
                continue
            }

            // ── 通常レイヤー ──
            // 空レイヤー (lw <= 0 || lh <= 0) でもスキップしない
            val channelData = HashMap<Int, ByteArray>()

            for (c in 0 until psd.channelCount) {
                val chId = psd.channelIds[c]
                val chDataLen = psd.channelDataLens[c]
                val chStart = buf.position()

                if (chDataLen < 2 || lw <= 0 || lh <= 0) {
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
                        val scanlineLens = IntArray(lh)
                        for (row in 0 until lh) scanlineLens[row] = buf.short.toInt() and 0xFFFF

                        for (row in 0 until lh) {
                            val rowOff = row * lw
                            var col = 0
                            while (col < lw && buf.hasRemaining()) {
                                val n = buf.get().toInt()
                                if (n >= 0) {
                                    val count = n + 1
                                    for (j in 0 until count) {
                                        if (col < lw && buf.hasRemaining()) {
                                            pixels[rowOff + col] = buf.get()
                                            col++
                                        }
                                    }
                                } else if (n != -128) {
                                    val count = -n + 1
                                    val v = if (buf.hasRemaining()) buf.get() else 0
                                    for (j in 0 until count) {
                                        if (col < lw) {
                                            pixels[rowOff + col] = v
                                            col++
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        PaintDebug.e(PaintDebug.Layer, "[PSD] unsupported compression: $compression")
                        buf.position(chStart + chDataLen)
                        continue
                    }
                }

                buf.position(chStart + chDataLen)
                channelData[chId] = pixels
            }

            // ピクセルを ARGB premultiplied タイルに書き込み
            val surface = TiledSurface(width, height)

            if (lw > 0 && lh > 0) {
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
            }

            val blendMode = psdBlendModeToId(psd.blendMode)
            val isVisible = (psd.flags and 0x02) == 0

            val layer = Layer(
                id = nextId++,
                name = psd.name,
                content = surface,
                opacity = psd.opacity / 255f,
                blendMode = blendMode,
                isVisible = isVisible,
                isClipToBelow = psd.clipping == 1,
            )

            // グループスタックが開いていれば、このレイヤーは現在のグループに属する
            // groupId は仮の負値で保持し、GroupStart で正のIDに変換する
            if (groupStack.isNotEmpty()) {
                layer.groupId = -(groupStack.size) // 仮のマーカー
            }

            loadedLayers.add(layer)
        }

        if (loadedLayers.isEmpty()) {
            PaintDebug.e(PaintDebug.Layer, "[PSD] no valid layers found")
            return null
        }

        doc.replaceAllLayers(loadedLayers, loadedLayers.last().id, nextId)
        PaintDebug.d(PaintDebug.Layer) {
            "[PSD] imported ${loadedLayers.size} layers, ${doc.layerGroups.size} groups, ${width}x${height}"
        }
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
        "lddg" -> PixelOps.BLEND_ADD
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
