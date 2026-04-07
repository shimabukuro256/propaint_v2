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
                var maskVal = 255  // デフォルト = 完全選択
                if (selectionMask != null) {
                    maskVal = selectionMask[dstY * w + dstX].toInt() and 0xFF
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

                var pixel = sampleBilinear(src, w, h, srcXf, srcYf)
                if (pixel == 0) continue

                // 部分選択時: アルファを減衰
                if (maskVal < 255) {
                    val fadeAlpha = maskVal / 255f
                    val origAlpha = PixelOps.alpha(pixel)
                    val newAlpha = (origAlpha * fadeAlpha).toInt().coerceIn(0, 255)
                    pixel = PixelOps.pack(newAlpha, PixelOps.red(pixel), PixelOps.green(pixel), PixelOps.blue(pixel))
                }

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
                var pixel = src[y * w + (w - 1 - x)]
                if (pixel == 0) continue

                // 選択マスク有効時: 部分選択ピクセルのアルファを減衰
                if (selectionMask != null) {
                    val maskVal = selectionMask[y * w + x].toInt() and 0xFF
                    if (maskVal == 0) continue  // 完全非選択: スキップ
                    if (maskVal < 255) {  // 部分選択: アルファ減衰
                        val fadeAlpha = maskVal / 255f
                        val origAlpha = PixelOps.alpha(pixel)
                        val newAlpha = (origAlpha * fadeAlpha).toInt().coerceIn(0, 255)
                        pixel = PixelOps.pack(newAlpha, PixelOps.red(pixel), PixelOps.green(pixel), PixelOps.blue(pixel))
                    }
                }

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
                var pixel = src[(h - 1 - y) * w + x]
                if (pixel == 0) continue

                // 選択マスク有効時: 部分選択ピクセルのアルファを減衰
                if (selectionMask != null) {
                    val maskVal = selectionMask[y * w + x].toInt() and 0xFF
                    if (maskVal == 0) continue  // 完全非選択: スキップ
                    if (maskVal < 255) {  // 部分選択: アルファ減衰
                        val fadeAlpha = maskVal / 255f
                        val origAlpha = PixelOps.alpha(pixel)
                        val newAlpha = (origAlpha * fadeAlpha).toInt().coerceIn(0, 255)
                        pixel = PixelOps.pack(newAlpha, PixelOps.red(pixel), PixelOps.green(pixel), PixelOps.blue(pixel))
                    }
                }

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
                // 90度時計回り逆変換: srcX = dstY, srcY = w-1-dstX (中心補正あり)
                val srcX = (dstY - cy + cx).toInt()
                val srcY = ((w - 1 - dstX) - cx + cy).toInt()
                if (srcX < 0 || srcX >= w || srcY < 0 || srcY >= h) continue
                var pixel = src[srcY * w + srcX]
                if (pixel == 0) continue

                // 選択マスク有効時: 部分選択ピクセルのアルファを減衰
                if (selectionMask != null) {
                    val maskVal = selectionMask[dstY * w + dstX].toInt() and 0xFF
                    if (maskVal == 0) continue  // 完全非選択: スキップ
                    if (maskVal < 255) {  // 部分選択: アルファ減衰
                        val fadeAlpha = maskVal / 255f
                        val origAlpha = PixelOps.alpha(pixel)
                        val newAlpha = (origAlpha * fadeAlpha).toInt().coerceIn(0, 255)
                        pixel = PixelOps.pack(newAlpha, PixelOps.red(pixel), PixelOps.green(pixel), PixelOps.blue(pixel))
                    }
                }

                val tx = dstX / Tile.SIZE; val ty = dstY / Tile.SIZE
                val tile = surface.getOrCreateMutable(tx, ty)
                tile.pixels[(dstY - ty * Tile.SIZE) * Tile.SIZE + (dstX - tx * Tile.SIZE)] = pixel
            }
        }
        PaintDebug.d(PaintDebug.Layer) { "[Transform] rotate90CW" }
    }

    // ── ディストート（パースペクティブ変形） ─────────────────────

    /**
     * 4コーナーを自由に移動してパースペクティブ変形する。
     * @param corners 変形先の4コーナー座標 [topLeft, topRight, bottomRight, bottomLeft] 各(x,y)
     */
    fun distort(
        surface: TiledSurface,
        corners: FloatArray, // [tlX, tlY, trX, trY, brX, brY, blX, blY]
        selectionMask: ByteArray? = null,
    ) {
        require(corners.size == 8) { "corners must have 8 values" }
        val w = surface.width; val h = surface.height
        val src = surface.toPixelArray()
        surface.clear()

        // 変形先の4コーナー
        val tlX = corners[0]; val tlY = corners[1]
        val trX = corners[2]; val trY = corners[3]
        val brX = corners[4]; val brY = corners[5]
        val blX = corners[6]; val blY = corners[7]

        for (dstY in 0 until h) {
            for (dstX in 0 until w) {
                // 選択マスク有効時: 部分選択ピクセルのアルファを減衰
                var maskVal = 255  // デフォルト = 完全選択
                if (selectionMask != null) {
                    maskVal = selectionMask[dstY * w + dstX].toInt() and 0xFF
                    if (maskVal == 0) continue  // 完全非選択: スキップ
                }
                // 逆変換: dst(x,y) → src(u,v) via 双線形逆マッピング
                // 正規化座標 u,v を求め、元の矩形 [0,w)x[0,h) にマッピング
                val srcXY = inverseBilinearMap(
                    dstX.toFloat(), dstY.toFloat(),
                    tlX, tlY, trX, trY, brX, brY, blX, blY,
                    w.toFloat(), h.toFloat()
                ) ?: continue

                var pixel = sampleBilinear(src, w, h, srcXY.first, srcXY.second)
                if (pixel == 0) continue

                // 部分選択時: アルファを減衰
                if (maskVal < 255) {
                    val fadeAlpha = maskVal / 255f
                    val origAlpha = PixelOps.alpha(pixel)
                    val newAlpha = (origAlpha * fadeAlpha).toInt().coerceIn(0, 255)
                    pixel = PixelOps.pack(newAlpha, PixelOps.red(pixel), PixelOps.green(pixel), PixelOps.blue(pixel))
                }

                val tx = dstX / Tile.SIZE; val ty = dstY / Tile.SIZE
                if (tx < 0 || tx >= surface.tilesX || ty < 0 || ty >= surface.tilesY) continue
                val tile = surface.getOrCreateMutable(tx, ty)
                tile.pixels[(dstY - ty * Tile.SIZE) * Tile.SIZE + (dstX - tx * Tile.SIZE)] = pixel
            }
        }
        PaintDebug.d(PaintDebug.Layer) { "[Transform] distort" }
    }

    /**
     * 双線形逆マッピング: 変形後の座標(px,py) → 元の正規化座標(u,v)
     * 4コーナーで定義される四角形内の点を [0,srcW) x [0,srcH) にマッピング。
     * 点が四角形外なら null を返す。
     */
    private fun inverseBilinearMap(
        px: Float, py: Float,
        tlX: Float, tlY: Float, trX: Float, trY: Float,
        brX: Float, brY: Float, blX: Float, blY: Float,
        srcW: Float, srcH: Float,
    ): Pair<Float, Float>? {
        // Newton-Raphson 法で (u,v) を求める
        // P = (1-v)*((1-u)*TL + u*TR) + v*((1-u)*BL + u*BR)
        var u = 0.5f; var v = 0.5f
        for (iter in 0 until 12) {
            val x = (1-v)*((1-u)*tlX + u*trX) + v*((1-u)*blX + u*brX)
            val y = (1-v)*((1-u)*tlY + u*trY) + v*((1-u)*blY + u*brY)
            val ex = x - px; val ey = y - py
            if (abs(ex) < 0.01f && abs(ey) < 0.01f) break

            // ヤコビアン
            val dxdu = (1-v)*(trX - tlX) + v*(brX - blX)
            val dxdv = (1-u)*(blX - tlX) + u*(brX - trX)
            val dydu = (1-v)*(trY - tlY) + v*(brY - blY)
            val dydv = (1-u)*(blY - tlY) + u*(brY - trY)

            val det = dxdu * dydv - dxdv * dydu
            if (abs(det) < 1e-8f) return null

            val invDet = 1f / det
            u -= (dydv * ex - dxdv * ey) * invDet
            v -= (-dydu * ex + dxdu * ey) * invDet
        }

        if (u < -0.01f || u > 1.01f || v < -0.01f || v > 1.01f) return null
        return Pair(u.coerceIn(0f, 1f) * (srcW - 1), v.coerceIn(0f, 1f) * (srcH - 1))
    }

    // ── メッシュワープ ────────────────────────────────────────────

    /**
     * メッシュワープ変形。NxM グリッドのノード位置を変形後の座標として指定。
     * @param gridW グリッドの列数 (ノード数 = gridW+1)
     * @param gridH グリッドの行数 (ノード数 = gridH+1)
     * @param nodes 変形後のノード座標 [(gridW+1)*(gridH+1) 個の (x,y)]
     */
    fun meshWarp(
        surface: TiledSurface,
        gridW: Int, gridH: Int,
        nodes: FloatArray, // [(gridW+1)*(gridH+1)*2] = [x0,y0, x1,y1, ...]
        selectionMask: ByteArray? = null,
    ) {
        require(gridW >= 1 && gridH >= 1) { "grid must be at least 1x1" }
        require(nodes.size == (gridW + 1) * (gridH + 1) * 2) { "nodes size mismatch" }

        val w = surface.width; val h = surface.height
        val src = surface.toPixelArray()
        surface.clear()

        val nodesPerRow = gridW + 1
        val cellW = w.toFloat() / gridW
        val cellH = h.toFloat() / gridH

        for (dstY in 0 until h) {
            for (dstX in 0 until w) {
                // 選択マスク有効時: 部分選択ピクセルのアルファを減衰
                var maskVal = 255  // デフォルト = 完全選択
                if (selectionMask != null) {
                    maskVal = selectionMask[dstY * w + dstX].toInt() and 0xFF
                    if (maskVal == 0) continue  // 完全非選択: スキップ
                }

                // dstX,dstY がどのメッシュセルに属するか
                val cellX = (dstX / cellW).toInt().coerceIn(0, gridW - 1)
                val cellY = (dstY / cellH).toInt().coerceIn(0, gridH - 1)

                // セル内の正規化座標 (0..1)
                val lu = (dstX - cellX * cellW) / cellW
                val lv = (dstY - cellY * cellH) / cellH

                // セルの4コーナーノード (変形後座標)
                val i00 = (cellY * nodesPerRow + cellX) * 2
                val i10 = (cellY * nodesPerRow + cellX + 1) * 2
                val i01 = ((cellY + 1) * nodesPerRow + cellX) * 2
                val i11 = ((cellY + 1) * nodesPerRow + cellX + 1) * 2

                val nx = (1-lv)*((1-lu)*nodes[i00] + lu*nodes[i10]) + lv*((1-lu)*nodes[i01] + lu*nodes[i11])
                val ny = (1-lv)*((1-lu)*nodes[i00+1] + lu*nodes[i10+1]) + lv*((1-lu)*nodes[i01+1] + lu*nodes[i11+1])

                // 逆マッピング: dstX,dstY → 元のセル内の相対座標
                // メッシュの各セルは元画像の対応セル領域にマッピング
                // 正変換: 元の (cellX*cellW + lu*cellW, cellY*cellH + lv*cellH) → (nx, ny)
                // 逆変換: ここでは dst→src なので、dstの位置から元のセル位置を逆算
                // 簡易方式: dst=元の座標、nodeはユーザーが移動した先 → 逆マッピング不要
                // 正マッピングの場合: 元(sx,sy) → dst(nx,ny)
                val srcX = cellX * cellW + lu * cellW
                val srcY = cellY * cellH + lv * cellH

                var pixel = sampleBilinear(src, w, h, srcX, srcY)
                if (pixel == 0) continue

                // 部分選択時: アルファを減衰
                if (maskVal < 255) {
                    val fadeAlpha = maskVal / 255f
                    val origAlpha = PixelOps.alpha(pixel)
                    val newAlpha = (origAlpha * fadeAlpha).toInt().coerceIn(0, 255)
                    pixel = PixelOps.pack(newAlpha, PixelOps.red(pixel), PixelOps.green(pixel), PixelOps.blue(pixel))
                }

                // 書き込み先は変形後座標
                val dstPx = nx.toInt(); val dstPy = ny.toInt()
                if (dstPx < 0 || dstPx >= w || dstPy < 0 || dstPy >= h) continue
                val tx = dstPx / Tile.SIZE; val ty = dstPy / Tile.SIZE
                if (tx < 0 || tx >= surface.tilesX || ty < 0 || ty >= surface.tilesY) continue
                val tile = surface.getOrCreateMutable(tx, ty)
                tile.pixels[(dstPy - ty * Tile.SIZE) * Tile.SIZE + (dstPx - tx * Tile.SIZE)] = pixel
            }
        }
        PaintDebug.d(PaintDebug.Layer) { "[Transform] meshWarp ${gridW}x${gridH}" }
    }

    // ── リキファイ（ゆがみ） ──────────────────────────────────────

    /**
     * リキファイ（ゆがみ）: ブラシ位置の周辺ピクセルを方向ベクトルで押し出す。
     * @param mode 0=Push, 1=TwirlCW, 2=TwirlCCW, 3=Pinch, 4=Expand
     */
    fun liquify(
        surface: TiledSurface,
        cx: Float, cy: Float, radius: Float,
        dirX: Float, dirY: Float,
        pressure: Float, mode: Int,
        selectionMask: ByteArray? = null,
    ) {
        require(radius > 0f) { "liquify radius must be > 0" }
        val w = surface.width; val h = surface.height
        val src = surface.toPixelArray()
        val r = radius.toInt()
        val startX = max(0, (cx - r).toInt()); val endX = min(w - 1, (cx + r).toInt())
        val startY = max(0, (cy - r).toInt()); val endY = min(h - 1, (cy + r).toInt())
        val r2 = radius * radius
        val strength = pressure.coerceIn(0f, 1f)

        for (y in startY..endY) {
            for (x in startX..endX) {
                val dx = x - cx; val dy = y - cy
                val dist2 = dx * dx + dy * dy
                if (dist2 >= r2) continue

                // 選択マスク有効時: 部分選択ピクセルのアルファを減衰
                var maskVal = 255  // デフォルト = 完全選択
                if (selectionMask != null) {
                    maskVal = selectionMask[y * w + x].toInt() and 0xFF
                    if (maskVal == 0) continue  // 完全非選択: スキップ
                }

                // 距離に基づく減衰 (中心ほど強い)
                val dist = sqrt(dist2)
                val falloff = (1f - dist / radius).let { it * it } * strength

                // 変位ベクトル計算
                val (vx, vy) = when (mode) {
                    0 -> Pair(dirX * falloff, dirY * falloff)            // Push
                    1 -> Pair(-dy / radius * falloff * 10f, dx / radius * falloff * 10f)  // Twirl CW
                    2 -> Pair(dy / radius * falloff * 10f, -dx / radius * falloff * 10f)  // Twirl CCW
                    3 -> Pair(-dx * falloff * 0.3f, -dy * falloff * 0.3f)  // Pinch
                    4 -> Pair(dx * falloff * 0.3f, dy * falloff * 0.3f)    // Expand
                    else -> Pair(0f, 0f)
                }

                // ソース座標 (変位の逆方向からサンプリング)
                val srcXf = x - vx; val srcYf = y - vy
                var pixel = sampleBilinear(src, w, h, srcXf, srcYf)

                // 部分選択時: アルファを減衰
                if (maskVal < 255) {
                    val fadeAlpha = maskVal / 255f
                    val origAlpha = PixelOps.alpha(pixel)
                    val newAlpha = (origAlpha * fadeAlpha).toInt().coerceIn(0, 255)
                    pixel = PixelOps.pack(newAlpha, PixelOps.red(pixel), PixelOps.green(pixel), PixelOps.blue(pixel))
                }

                val tx = x / Tile.SIZE; val ty2 = y / Tile.SIZE
                val tile = surface.getOrCreateMutable(tx, ty2)
                tile.pixels[(y - ty2 * Tile.SIZE) * Tile.SIZE + (x - tx * Tile.SIZE)] = pixel
            }
        }
    }

    // ── バイキュービック補間 ──────────────────────────────────────

    /**
     * premultiplied ARGB のバイキュービック補間サンプリング。
     * Mitchell-Netravali フィルタ (B=1/3, C=1/3) を使用。
     */
    fun sampleBicubic(pixels: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = x - x0; val fy = y - y0

        var ra = 0f; var rr = 0f; var rg = 0f; var rb = 0f
        for (j in -1..2) {
            val wy = mitchellFilter(fy - j)
            for (i in -1..2) {
                val wx = mitchellFilter(fx - i)
                val c = safeGet(pixels, w, h, x0 + i, y0 + j)
                val wt = wx * wy
                ra += PixelOps.alpha(c) * wt
                rr += PixelOps.red(c) * wt
                rg += PixelOps.green(c) * wt
                rb += PixelOps.blue(c) * wt
            }
        }
        val ca = ra.toInt().coerceIn(0, 255)
        return PixelOps.pack(ca, rr.toInt().coerceIn(0, ca), rg.toInt().coerceIn(0, ca), rb.toInt().coerceIn(0, ca))
    }

    /** Mitchell-Netravali フィルタ (B=1/3, C=1/3) */
    private fun mitchellFilter(t: Float): Float {
        val x = abs(t)
        return when {
            x < 1f -> ((12f - 9f * B - 6f * C) * x * x * x + (-18f + 12f * B + 6f * C) * x * x + (6f - 2f * B)) / 6f
            x < 2f -> ((-B - 6f * C) * x * x * x + (6f * B + 30f * C) * x * x + (-12f * B - 48f * C) * x + (8f * B + 24f * C)) / 6f
            else -> 0f
        }
    }
    private const val B = 1f / 3f
    private const val C = 1f / 3f

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
