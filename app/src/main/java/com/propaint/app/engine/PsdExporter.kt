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
 *  - レイヤーグループ (フォルダ) — lsct セクションディバイダー
 */
object PsdExporter {

    /**
     * PSD レイヤーリストの論理アイテム。
     * 通常レイヤー or グループ開始マーカー or グループ終了マーカー。
     */
    private sealed class PsdLayerItem {
        /** 通常レイヤー */
        data class Regular(val layer: Layer) : PsdLayerItem()
        /** グループ開始（ヘッダー）— lsct type=1(open) or 2(closed) */
        data class GroupStart(val group: LayerGroupInfo) : PsdLayerItem()
        /** グループ終了マーカー — lsct type=3 */
        data class GroupEnd(val group: LayerGroupInfo) : PsdLayerItem()
    }

    fun export(doc: CanvasDocument, output: OutputStream) {
        val w = doc.width
        val h = doc.height
        val layers = doc.layers
        val groups = doc.layerGroups

        PaintDebug.d(PaintDebug.Layer) {
            "[PsdExporter] width=$w height=$h layers=${layers.size} groups=${groups.size}"
        }

        require(w > 0 && h > 0) { "[PsdExporter] invalid canvas size: ${w}x${h}" }

        if (layers.isEmpty()) {
            PaintDebug.d(PaintDebug.Layer) { "[PsdExporter] WARNING: no layers to export" }
        }

        // ── PSD レイヤー順序を構築 ──
        // PSD はボトム→トップの順で格納。
        // グループ構造: [GroupEnd] → [子レイヤー...] → [GroupStart]
        val psdItems = buildPsdLayerOrder(layers, groups)

        PaintDebug.d(PaintDebug.Layer) {
            "[PsdExporter] psdItems=${psdItems.size} (layers=${layers.size} groups=${groups.size})"
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
        val layerMaskLenPos = buf.size()
        out.writeInt(0) // placeholder

        // ── Layer Info ──
        val layerInfoLenPos = buf.size()
        out.writeInt(0) // placeholder

        out.writeShort(psdItems.size) // layer count

        // ── 各アイテムのピクセルデータと圧縮データを事前計算 ──
        data class LayerPixels(
            val red: ByteArray, val green: ByteArray,
            val blue: ByteArray, val alpha: ByteArray,
            val top: Int, val left: Int, val bottom: Int, val right: Int,
        )

        data class CompressedChannel(val data: ByteArray)

        val layerPixelsList = ArrayList<LayerPixels>(psdItems.size)
        val allCompressed = ArrayList<List<CompressedChannel>>(psdItems.size)

        for (item in psdItems) {
            when (item) {
                is PsdLayerItem.Regular -> {
                    val layer = item.layer
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
                    // 空レイヤーは 0x0 バウンディングボックス
                    if (maxX < minX) {
                        minX = 0; minY = 0; maxX = 0; maxY = 0
                    } else {
                        maxX++; maxY++ // exclusive
                    }

                    val lw = maxX - minX
                    val lh = maxY - minY
                    val red = ByteArray(lw * lh)
                    val green = ByteArray(lw * lh)
                    val blue = ByteArray(lw * lh)
                    val alpha = ByteArray(lw * lh)

                    for (y in 0 until lh) {
                        for (x in 0 until lw) {
                            val c = pixels[(minY + y) * w + (minX + x)]
                            val straight = PixelOps.unpremultiply(c)
                            red[y * lw + x] = (PixelOps.red(straight) and 0xFF).toByte()
                            green[y * lw + x] = (PixelOps.green(straight) and 0xFF).toByte()
                            blue[y * lw + x] = (PixelOps.blue(straight) and 0xFF).toByte()
                            alpha[y * lw + x] = (PixelOps.alpha(straight) and 0xFF).toByte()
                        }
                    }

                    val lp = LayerPixels(red, green, blue, alpha, minY, minX, maxY, maxX)
                    layerPixelsList.add(lp)

                    val channels = listOf(lp.alpha, lp.red, lp.green, lp.blue)
                    allCompressed.add(channels.map { chData ->
                        CompressedChannel(compressRLE(chData, lw, lh))
                    })
                }
                is PsdLayerItem.GroupStart, is PsdLayerItem.GroupEnd -> {
                    // グループマーカーはピクセルなし (0x0)
                    val empty = ByteArray(0)
                    layerPixelsList.add(LayerPixels(empty, empty, empty, empty, 0, 0, 0, 0))
                    allCompressed.add(listOf(
                        CompressedChannel(ByteArray(0)),
                        CompressedChannel(ByteArray(0)),
                        CompressedChannel(ByteArray(0)),
                        CompressedChannel(ByteArray(0)),
                    ))
                }
            }
        }

        // ── Layer Records ──
        for (i in psdItems.indices) {
            val item = psdItems[i]
            val lp = layerPixelsList[i]
            val compressed = allCompressed[i]

            // Rectangle
            out.writeInt(lp.top)
            out.writeInt(lp.left)
            out.writeInt(lp.bottom)
            out.writeInt(lp.right)

            // Channel count
            out.writeShort(4) // A, R, G, B

            // Channel info
            val chIds = intArrayOf(-1, 0, 1, 2)
            for (c in 0 until 4) {
                out.writeShort(chIds[c])
                out.writeInt(compressed[c].data.size + 2) // +2 for compression type
            }

            when (item) {
                is PsdLayerItem.Regular -> {
                    val layer = item.layer
                    // Blend mode signature
                    out.writeInt(0x3842494D) // "8BIM"
                    out.writeBytes(blendModeToKey(layer.blendMode))
                    // Opacity
                    out.writeByte((layer.opacity * 255f).toInt().coerceIn(0, 255))
                    // Clipping
                    out.writeByte(if (layer.isClipToBelow) 1 else 0)
                    // Flags
                    var flags = 0
                    if (!layer.isVisible) flags = flags or 0x02
                    out.writeByte(flags)
                    out.writeByte(0) // Filler

                    // Extra data
                    writeLayerExtraData(out, buf, layer.name, null)
                }
                is PsdLayerItem.GroupStart -> {
                    val group = item.group
                    // Blend mode: pass-through for groups
                    out.writeInt(0x3842494D) // "8BIM"
                    out.writeBytes(blendModeToKey(group.blendMode))
                    // Opacity
                    out.writeByte((group.opacity * 255f).toInt().coerceIn(0, 255))
                    // Clipping
                    out.writeByte(0)
                    // Flags
                    var flags = 0
                    if (!group.isVisible) flags = flags or 0x02
                    out.writeByte(flags)
                    out.writeByte(0) // Filler

                    // lsct type: 1=open folder, 2=closed folder
                    val lsctType = if (group.isExpanded) 1 else 2
                    writeLayerExtraData(out, buf, group.name, lsctType)
                }
                is PsdLayerItem.GroupEnd -> {
                    // 終了マーカー: blend=norm, opacity=255, hidden
                    out.writeInt(0x3842494D) // "8BIM"
                    out.writeBytes("norm")
                    out.writeByte(255) // Opacity
                    out.writeByte(0) // Clipping
                    out.writeByte(0x02) // Flags: hidden
                    out.writeByte(0) // Filler

                    // lsct type: 3=bounding section divider
                    writeLayerExtraData(out, buf, "</Layer group>", 3)
                }
            }
        }

        // ── Channel Image Data ──
        for (i in psdItems.indices) {
            val compressed = allCompressed[i]
            for (c in 0 until 4) {
                out.writeShort(1) // compression type: RLE
                out.writeRawBytes(compressed[c].data)
            }
        }

        // Layer info length を埋める (偶数パディング)
        val layerInfoEnd = buf.size()
        var layerInfoLen = layerInfoEnd - layerInfoLenPos - 4
        if (layerInfoLen % 2 != 0) {
            out.writeByte(0) // pad to even
            layerInfoLen++
        }
        patchInt(buf, layerInfoLenPos, layerInfoLen)

        // Layer and Mask info length を埋める
        val layerMaskEnd = buf.size()
        val layerMaskLen = layerMaskEnd - layerMaskLenPos - 4
        patchInt(buf, layerMaskLenPos, layerMaskLen)

        // ── Image Data (composite) ──
        out.writeShort(0) // compression: raw
        val composite = doc.getCompositePixels()
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

    /**
     * PSD レイヤー順序を構築する。
     * PSD はボトム→トップで格納:
     *   非グループレイヤー (下位) ... GroupEnd → 子レイヤー → GroupStart ... 非グループレイヤー (上位)
     *
     * doc.layers は UI の下→上順（index 0 = 最下層）。
     * グループの表示順は doc.layerGroups の LinkedHashMap 順。
     */
    private fun buildPsdLayerOrder(
        layers: List<Layer>,
        groups: Map<Int, LayerGroupInfo>,
    ): List<PsdLayerItem> {
        val result = ArrayList<PsdLayerItem>()

        // グループに属さないレイヤー
        val ungroupedLayers = layers.filter { it.groupId == 0 }

        // グループごとの子レイヤー (layers 内の出現順を維持)
        val groupChildren = LinkedHashMap<Int, MutableList<Layer>>()
        for (g in groups.values) {
            groupChildren[g.id] = mutableListOf()
        }
        for (layer in layers) {
            if (layer.groupId != 0 && groupChildren.containsKey(layer.groupId)) {
                groupChildren[layer.groupId]!!.add(layer)
            }
        }

        // layers の順序に基づいて、グループの挿入位置を決定
        // 各グループは「そのグループ内の最初のレイヤーの位置」に挿入する
        // グループ内にレイヤーがない場合は、layerGroups の順序で末尾に配置

        data class OrderedItem(val index: Int, val item: Any) // Any = Layer | Int(groupId)

        val orderedItems = ArrayList<OrderedItem>()

        // 各レイヤーの元のインデックスを記録
        val processedGroups = HashSet<Int>()
        for ((idx, layer) in layers.withIndex()) {
            if (layer.groupId == 0) {
                orderedItems.add(OrderedItem(idx, layer))
            } else if (!processedGroups.contains(layer.groupId) && groups.containsKey(layer.groupId)) {
                // このグループの最初の出現 → グループ全体をここに挿入
                processedGroups.add(layer.groupId)
                orderedItems.add(OrderedItem(idx, layer.groupId))
            }
        }

        // レイヤーを持たないグループを末尾に追加
        for (g in groups.values) {
            if (!processedGroups.contains(g.id)) {
                orderedItems.add(OrderedItem(layers.size + g.id, g.id))
            }
        }

        // PSD アイテムリストを構築
        for (oi in orderedItems) {
            when (val obj = oi.item) {
                is Layer -> {
                    result.add(PsdLayerItem.Regular(obj))
                }
                is Int -> {
                    val groupId = obj
                    val group = groups[groupId] ?: continue
                    val children = groupChildren[groupId] ?: emptyList()
                    // PSD 順序: GroupEnd → 子レイヤー → GroupStart
                    result.add(PsdLayerItem.GroupEnd(group))
                    for (child in children) {
                        result.add(PsdLayerItem.Regular(child))
                    }
                    result.add(PsdLayerItem.GroupStart(group))
                }
            }
        }

        return result
    }

    /**
     * レイヤーの Extra Data セクションを書き込む。
     * @param lsctType null=通常レイヤー、1=open folder、2=closed folder、3=bounding section divider
     */
    private fun writeLayerExtraData(
        out: PsdOutputStream,
        buf: ByteArrayOutputStream,
        layerName: String,
        lsctType: Int?,
    ) {
        val nameBytes = layerName.toByteArray(Charsets.ISO_8859_1)
        val pascalLen = 1 + nameBytes.size
        val paddedPascalLen = ((pascalLen + 3) / 4) * 4

        // luni block
        val luniDataLen = 4 + layerName.length * 2
        val luniBlockLen = 4 + 4 + 4 + luniDataLen

        // lsct block (if group marker)
        // lsct: 4(sig) + 4(key) + 4(len) + 4(type) [+ 4(sig) + 4(blend mode key)]
        val lsctBlockLen = if (lsctType != null) {
            4 + 4 + 4 + 12 // sig + key + len + (type + blend sig + blend key)
        } else {
            0
        }

        val extraLen = 4 + 4 + paddedPascalLen + luniBlockLen + lsctBlockLen
        out.writeInt(extraLen)

        // Layer mask data
        out.writeInt(0)

        // Layer blending ranges
        out.writeInt(0)

        // Layer name (Pascal string)
        out.writeByte(nameBytes.size)
        out.writeRawBytes(nameBytes)
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

        // Section divider (lsct) — グループマーカーの場合のみ
        if (lsctType != null) {
            out.writeInt(0x3842494D) // "8BIM"
            out.writeBytes("lsct")
            out.writeInt(12) // data length: type(4) + blend sig(4) + blend key(4)
            out.writeInt(lsctType)
            out.writeInt(0x3842494D) // "8BIM"
            out.writeBytes("pass") // pass-through blend mode for groups
        }
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
                var runLen = 1
                while (x + runLen < w && runLen < 128 &&
                    data[rowOff + x + runLen] == data[rowOff + x]) {
                    runLen++
                }
                if (runLen >= 3) {
                    rowBuf.write((-(runLen - 1)) and 0xFF)
                    rowBuf.write(data[rowOff + x].toInt() and 0xFF)
                    x += runLen
                } else {
                    var litLen = 1
                    while (x + litLen < w && litLen < 128) {
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

        for (y in 0 until h) {
            result.write((scanlineLens[y] shr 8) and 0xFF)
            result.write(scanlineLens[y] and 0xFF)
        }
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
        PixelOps.BLEND_ADD -> "lddg"
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
