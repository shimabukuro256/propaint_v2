package com.propaint.app.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * 64×64 premultiplied ARGB_8888 タイル。Drawpile DP_Tile 準拠。
 * Copy-on-Write: refCount > 1 なら mutableCopy() してから変更。
 */
class Tile private constructor(
    val pixels: IntArray,
    private val _refCount: AtomicInteger,
) {
    constructor() : this(IntArray(LENGTH), AtomicInteger(1))

    val refCount: Int get() = _refCount.get()
    fun incRef(): Tile { _refCount.incrementAndGet(); return this }
    fun decRef(): Int {
        val v = _refCount.decrementAndGet()
        if (v < 0) PaintDebug.assertFail("Tile refCount went negative: $v")
        return v
    }
    fun mutableCopy(): Tile = Tile(pixels.copyOf(), AtomicInteger(1))
    fun clear() { pixels.fill(0) }
    fun fill(color: Int) { pixels.fill(color) }

    fun isBlank(): Boolean {
        for (p in pixels) if (p != 0) return false
        return true
    }

    companion object {
        const val SIZE = 64
        const val LENGTH = SIZE * SIZE
    }
}

/**
 * タイルグリッドのサーフェス。Drawpile DP_LayerContent 準拠。
 * null タイル = 完全透明。
 */
class TiledSurface(val width: Int, val height: Int) {
    init {
        require(width > 0) { "width must be > 0, got $width" }
        require(height > 0) { "height must be > 0, got $height" }
        // Int オーバーフロー防止: 最大 16384x16384
        require(width.toLong() * height.toLong() <= 16384L * 16384L) {
            "canvas too large: ${width}x${height}"
        }
    }
    val tilesX: Int = (width + Tile.SIZE - 1) / Tile.SIZE
    val tilesY: Int = (height + Tile.SIZE - 1) / Tile.SIZE
    val tiles: Array<Tile?> = arrayOfNulls(tilesX * tilesY)

    fun tileIndex(tx: Int, ty: Int): Int = ty * tilesX + tx

    fun getTile(tx: Int, ty: Int): Tile? {
        if (tx < 0 || tx >= tilesX || ty < 0 || ty >= tilesY) return null
        return tiles[tileIndex(tx, ty)]
    }

    fun getOrCreateMutable(tx: Int, ty: Int): Tile {
        if (tx < 0 || tx >= tilesX || ty < 0 || ty >= tilesY)
            throw IndexOutOfBoundsException("Tile ($tx,$ty) out of ($tilesX,$tilesY)")
        val idx = tileIndex(tx, ty)
        val existing = tiles[idx]
        if (existing == null) {
            val t = Tile(); tiles[idx] = t; return t
        }
        if (existing.refCount > 1) {
            existing.decRef()
            val copy = existing.mutableCopy()
            tiles[idx] = copy; return copy
        }
        return existing
    }

    fun setTile(tx: Int, ty: Int, tile: Tile?) {
        if (tx < 0 || tx >= tilesX || ty < 0 || ty >= tilesY) return
        val idx = tileIndex(tx, ty)
        tiles[idx]?.decRef(); tiles[idx] = tile?.incRef()
    }

    fun clear() { for (i in tiles.indices) { tiles[i]?.decRef(); tiles[i] = null } }

    fun pixelToTile(px: Int): Int =
        if (px >= 0) px / Tile.SIZE else (px - Tile.SIZE + 1) / Tile.SIZE

    /** ピクセル座標の色を取得 (premultiplied ARGB) */
    fun getPixelAt(px: Int, py: Int): Int {
        if (px < 0 || px >= width || py < 0 || py >= height) return 0
        val tx = px / Tile.SIZE; val ty = py / Tile.SIZE
        val tile = getTile(tx, ty) ?: return 0
        val lx = px - tx * Tile.SIZE; val ly = py - ty * Tile.SIZE
        return tile.pixels[ly * Tile.SIZE + lx]
    }

    /**
     * 円形範囲の平均色をサンプリング。
     * Drawpile layer_content_sample_color_at / tile_sample 準拠。
     * スポイト・Smudge 共用。
     */
    fun sampleColorAt(cx: Int, cy: Int, radius: Int): Int {
        if (radius <= 0) return getPixelAt(cx, cy)
        val r2 = radius * radius
        // リニアライト空間で平均化 (sRGB 空間の平均は色が濁る)
        var aSum = 0L; var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0L
        val x0 = maxOf(0, cx - radius); val x1 = minOf(width - 1, cx + radius)
        val y0 = maxOf(0, cy - radius); val y1 = minOf(height - 1, cy + radius)
        for (py in y0..y1) {
            val dy = py - cy
            for (px in x0..x1) {
                val dx = px - cx
                if (dx * dx + dy * dy > r2) continue
                val lin = PixelOps.pixelToLinear64(getPixelAt(px, py))
                aSum += (lin ushr 48) and 0xFFFF
                rSum += (lin ushr 32) and 0xFFFF
                gSum += (lin ushr 16) and 0xFFFF
                bSum += lin and 0xFFFF
                count++
            }
        }
        if (count == 0L) return 0
        val avgLin = ((aSum / count) shl 48) or
            (((rSum / count) and 0xFFFF) shl 32) or
            (((gSum / count) and 0xFFFF) shl 16) or
            ((bSum / count) and 0xFFFF)
        return PixelOps.linear64ToPixel(avgLin)
    }

    /** タイル参照をコピー (CoW 用スナップショット) */
    fun snapshot(): TiledSurface {
        val copy = TiledSurface(width, height)
        for (i in tiles.indices) copy.tiles[i] = tiles[i]?.incRef()
        return copy
    }

    /** 全ピクセルを IntArray で取得 (エクスポート用) */
    fun toPixelArray(): IntArray {
        val out = IntArray(width * height)
        toPixelArray(out)
        return out
    }

    /** 全ピクセルを既存バッファに書き込む (フィルタープレビュー等でのアロケーション回避用) */
    fun toPixelArray(out: IntArray) {
        out.fill(0)
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            val tile = getTile(tx, ty) ?: continue
            val bx = tx * Tile.SIZE; val by = ty * Tile.SIZE
            for (ly in 0 until Tile.SIZE) {
                val py = by + ly; if (py >= height) break
                val cw = minOf(Tile.SIZE, width - bx)
                System.arraycopy(tile.pixels, ly * Tile.SIZE, out, py * width + bx, cw)
            }
        }
    }
}
