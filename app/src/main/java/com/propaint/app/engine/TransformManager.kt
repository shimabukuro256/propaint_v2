package com.propaint.app.engine

import kotlin.math.*

/**
 * レイヤー変形操作（拡大縮小・回転・反転・トリミング）。
 * バイリニア補間で画質を確保。全操作は Undo 対応 (呼び出し元で pushUndo)。
 */
object TransformManager {

    /**
     * レイヤーコンテンツを自由変形する。
     * @param surface 変形対象の TiledSurface（直接書き換え）
     * @param centerX 回転中心 X (ドキュメント座標)
     * @param centerY 回転中心 Y (ドキュメント座標)
     * @param scaleX X方向の拡大率 (1.0 = 等倍、負 = 反転)
     * @param scaleY Y方向の拡大率
     * @param angleDeg 回転角度（度数法、反時計回り正）
     * @param translateX 移動量 X
     * @param translateY 移動量 Y
     * @param selectionMask 選択マスク (null = 選択なし、0..255 = マスク値)
     */
    fun freeTransform(
        surface: TiledSurface,
        centerX: Float, centerY: Float,
        scaleX: Float, scaleY: Float,
        angleDeg: Float,
        translateX: Float, translateY: Float,
        selectionMask: ByteArray? = null,
    ) {
        // 防御的アサーション
        require(!scaleX.isNaN() && !scaleX.isInfinite()) { "scaleX is NaN/Inf" }
        require(!scaleY.isNaN() && !scaleY.isInfinite()) { "scaleY is NaN/Inf" }
        require(!angleDeg.isNaN() && !angleDeg.isInfinite()) { "angleDeg is NaN/Inf" }
        require(!translateX.isNaN() && !translateX.isInfinite()) { "translateX is NaN/Inf" }
        require(!translateY.isNaN() && !translateY.isInfinite()) { "translateY is NaN/Inf" }
        if (abs(scaleX) < 1e-6f || abs(scaleY) < 1e-6f) {
            PaintDebug.d(PaintDebug.Layer) { "[Transform] scale too small, clearing" }
            surface.clear()
            return
        }

        val w = surface.width; val h = surface.height
        val src = surface.toPixelArray()
        surface.clear()

        val rad = Math.toRadians(angleDeg.toDouble()).toFloat()
        val cosA = cos(rad); val sinA = sin(rad)

        // 逆行列で出力ピクセル → 入力ピクセルを計算
        // T = Translate(cx+tx, cy+ty) * Rotate(angle) * Scale(sx, sy) * Translate(-cx, -cy)
        // 逆行列: Translate(cx, cy) * Scale(1/sx, 1/sy) * Rotate(-angle) * Translate(-cx-tx, -cy-ty)
        val invSx = 1f / scaleX; val invSy = 1f / scaleY

        for (dstY in 0 until h) {
            for (dstX in 0 until w) {
                // 選択マスク有効時: 選択範囲内のピクセルのみ処理
                if (selectionMask != null) {
                    val maskVal = selectionMask[dstY * w + dstX].toInt() and 0xFF
                    if (maskVal == 0) continue  // 選択範囲外 → スキップ
                }

                // Step 1: Translate(-cx-tx, -cy-ty)
                val rx = dstX - centerX - translateX
                val ry = dstY - centerY - translateY
                // Step 2: Rotate(-angle)
                val rrx = rx * cosA + ry * sinA
                val rry = -rx * sinA + ry * cosA
                // Step 3: Scale(1/sx, 1/sy)
                val sx = rrx * invSx
                val sy = rry * invSy
                // Step 4: Translate(cx, cy)
                val srcXf = sx + centerX
                val srcYf = sy + centerY

                val pixel = sampleBilinear(src, w, h, srcXf, srcYf)
                if (pixel == 0) continue

                val tx = dstX / Tile.SIZE; val ty = dstY / Tile.SIZE
                if (tx < 0 || tx >= surface.tilesX || ty < 0 || ty >= surface.tilesY) continue
                val tile = surface.getOrCreateMutable(tx, ty)
                val lx = dstX - tx * Tile.SIZE; val ly = dstY - ty * Tile.SIZE
                tile.pixels[ly * Tile.SIZE + lx] = pixel
            }
        }

        PaintDebug.d(PaintDebug.Layer) {
            "[Transform] freeTransform cx=$centerX cy=$centerY sx=$scaleX sy=$scaleY " +
            "angle=$angleDeg tx=$translateX ty=$translateY"
        }
    }

    /** レイヤーを左右反転 */
    fun flipHorizontal(surface: TiledSurface, selectionMask: ByteArray? = null) {
        val w = surface.width; val h = surface.height
        val src = surface.toPixelArray()
        surface.clear()
        for (y in 0 until h) {
            for (x in 0 until w) {
                // 選択マスク有効時: 選択範囲内のピクセルのみ処理
                if (selectionMask != null) {
                    val maskVal = selectionMask[y * w + x].toInt() and 0xFF
                    if (maskVal == 0) continue
                }
                val pixel = src[y * w + (w - 1 - x)]
                if (pixel == 0) continue
                val tx = x / Tile.SIZE; val ty = y / Tile.SIZE
                val tile = surface.getOrCreateMutable(tx, ty)
                tile.pixels[(y - ty * Tile.SIZE) * Tile.SIZE + (x - tx * Tile.SIZE)] = pixel
            }
        }
        PaintDebug.d(PaintDebug.Layer) { "[Transform] flipHorizontal" }
    }

    /** レイヤーを上下反転 */
    fun flipVertical(surface: TiledSurface, selectionMask: ByteArray? = null) {
        val w = surface.width; val h = surface.height
        val src = surface.toPixelArray()
        surface.clear()
        for (y in 0 until h) {
            for (x in 0 until w) {
                // 選択マスク有効時: 選択範囲内のピクセルのみ処理
                if (selectionMask != null) {
                    val maskVal = selectionMask[y * w + x].toInt() and 0xFF
                    if (maskVal == 0) continue
                }
                val pixel = src[(h - 1 - y) * w + x]
                if (pixel == 0) continue
                val tx = x / Tile.SIZE; val ty = y / Tile.SIZE
                val tile = surface.getOrCreateMutable(tx, ty)
                tile.pixels[(y - ty * Tile.SIZE) * Tile.SIZE + (x - tx * Tile.SIZE)] = pixel
            }
        }
        PaintDebug.d(PaintDebug.Layer) { "[Transform] flipVertical" }
    }

    /** レイヤーを90度回転（時計回り）。キャンバスサイズは変わらない（クリップ） */
    fun rotate90CW(surface: TiledSurface, selectionMask: ByteArray? = null) {
        val w = surface.width; val h = surface.height
        val src = surface.toPixelArray()
        surface.clear()
        val cx = w / 2f; val cy = h / 2f
        for (dstY in 0 until h) {
            for (dstX in 0 until w) {
                // 選択マスク有効時: 選択範囲内のピクセルのみ処理
                if (selectionMask != null) {
                    val maskVal = selectionMask[dstY * w + dstX].toInt() and 0xFF
                    if (maskVal == 0) continue
                }
                // 90度時計回り逆変換: srcX = dstY, srcY = w-1-dstX (中心補正あり)
                val srcX = (dstY - cy + cx).toInt()
                val srcY = ((w - 1 - dstX) - cx + cy).toInt()
                if (srcX < 0 || srcX >= w || srcY < 0 || srcY >= h) continue
                val pixel = src[srcY * w + srcX]
                if (pixel == 0) continue
                val tx = dstX / Tile.SIZE; val ty = dstY / Tile.SIZE
                val tile = surface.getOrCreateMutable(tx, ty)
                tile.pixels[(dstY - ty * Tile.SIZE) * Tile.SIZE + (dstX - tx * Tile.SIZE)] = pixel
            }
        }
        PaintDebug.d(PaintDebug.Layer) { "[Transform] rotate90CW" }
    }

    /**
     * キャンバスをトリミング（切り抜き）。
     * CanvasDocument レベルで呼ぶ（新しいドキュメントを生成するため、ここでは
     * レイヤーコンテンツの切り出しのみ行う）。
     * @return トリミングされたピクセル配列 (newW * newH)
     */
    fun cropLayerContent(
        surface: TiledSurface,
        cropLeft: Int, cropTop: Int, cropWidth: Int, cropHeight: Int,
    ): IntArray {
        require(cropWidth > 0 && cropHeight > 0) { "crop size must be > 0" }
        val result = IntArray(cropWidth * cropHeight)
        for (y in 0 until cropHeight) {
            val srcY = y + cropTop
            if (srcY < 0 || srcY >= surface.height) continue
            for (x in 0 until cropWidth) {
                val srcX = x + cropLeft
                if (srcX < 0 || srcX >= surface.width) continue
                result[y * cropWidth + x] = surface.getPixelAt(srcX, srcY)
            }
        }
        return result
    }

    // ── バイリニア補間 ─────────────────────────────────────────

    /**
     * premultiplied ARGB 画像のバイリニア補間サンプリング。
     * 範囲外は透明 (0) を返す。premultiplied 空間で補間するのが正しい
     * (unpremultiply → 補間 → premultiply だと半透明境界で白フリンジが出る)。
     */
    private fun sampleBilinear(pixels: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = x - x0; val fy = y - y0

        // 4隣接ピクセル取得 (範囲外 = 0)
        val c00 = safeGet(pixels, w, h, x0, y0)
        val c10 = safeGet(pixels, w, h, x0 + 1, y0)
        val c01 = safeGet(pixels, w, h, x0, y0 + 1)
        val c11 = safeGet(pixels, w, h, x0 + 1, y0 + 1)

        // 全部透明ならスキップ
        if (c00 == 0 && c10 == 0 && c01 == 0 && c11 == 0) return 0
        // 全部同じ色なら補間不要
        if (c00 == c10 && c10 == c01 && c01 == c11) return c00

        val ifx = 1f - fx; val ify = 1f - fy
        val w00 = ifx * ify; val w10 = fx * ify
        val w01 = ifx * fy; val w11 = fx * fy

        val a = (PixelOps.alpha(c00) * w00 + PixelOps.alpha(c10) * w10 +
                 PixelOps.alpha(c01) * w01 + PixelOps.alpha(c11) * w11 + 0.5f).toInt()
        val r = (PixelOps.red(c00) * w00 + PixelOps.red(c10) * w10 +
                 PixelOps.red(c01) * w01 + PixelOps.red(c11) * w11 + 0.5f).toInt()
        val g = (PixelOps.green(c00) * w00 + PixelOps.green(c10) * w10 +
                 PixelOps.green(c01) * w01 + PixelOps.green(c11) * w11 + 0.5f).toInt()
        val b = (PixelOps.blue(c00) * w00 + PixelOps.blue(c10) * w10 +
                 PixelOps.blue(c01) * w01 + PixelOps.blue(c11) * w11 + 0.5f).toInt()

        // premultiplied 条件の保証: R,G,B <= A
        val ca = a.coerceIn(0, 255)
        return PixelOps.pack(ca, r.coerceIn(0, ca), g.coerceIn(0, ca), b.coerceIn(0, ca))
    }

    private fun safeGet(pixels: IntArray, w: Int, h: Int, x: Int, y: Int): Int {
        if (x < 0 || x >= w || y < 0 || y >= h) return 0
        return pixels[y * w + x]
    }
}
