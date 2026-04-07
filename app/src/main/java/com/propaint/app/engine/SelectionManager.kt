package com.propaint.app.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 選択範囲管理。マスクベースのビットマップ選択。
 * マスクは 0..255 のグレースケール (0=未選択, 255=完全選択)。
 * アンチエイリアス選択やぼかし選択のため連続値をサポート。
 */
class SelectionManager(val width: Int, val height: Int) {

    init {
        require(width > 0 && height > 0) { "selection size must be > 0: ${width}x${height}" }
    }

    /** 選択マスク。null = 選択なし（全体を操作対象とする） */
    private var _mask: ByteArray? = null
    val mask: ByteArray? get() = _mask

    /** 選択が存在するか */
    val hasSelection: Boolean get() = _mask != null

    /** キャッシュされた選択範囲の境界 (minX, minY, maxX+1, maxY+1) */
    private var _cachedBounds: IntArray? = null
    private var _boundsCacheDirty = true

    /** 外部からマスクを直接設定（移動操作等） */
    fun setMask(newMask: ByteArray?) {
        if (newMask != null) {
            require(newMask.size == width * height) { "mask size mismatch: ${newMask.size} != ${width * height}" }
        }
        _mask = newMask
        _boundsCacheDirty = true  // キャッシュを無効化
    }

    /** マスクの指定座標の値を取得 (0..255) */
    fun getMaskValue(x: Int, y: Int): Int {
        if (x < 0 || x >= width || y < 0 || y >= height) return 0
        val m = _mask ?: return 255 // 選択なし = 全選択扱い
        return m[y * width + x].toInt() and 0xFF
    }

    // ── 選択マスク直接操作 (選択ペン/消しペン) ────────────────────

    /** マスクが null の場合に空マスクを確保する */
    fun ensureMask() {
        if (_mask == null) _mask = ByteArray(width * height)
    }

    /** 円形ブラシで選択マスクをペイントする */
    fun paintCircle(cx: Int, cy: Int, radius: Int, isAdd: Boolean, pressure: Float = 1f) {
        ensureMask()
        val m = _mask ?: return
        require(pressure in 0f..1f) { "pressure must be in 0.0..1.0, got $pressure" }
        // 筆圧が低い場合は最小径を適用（デフォルト 20%）
        val minRadiusRatio = 0.2f
        val pressureRadius = radius * (minRadiusRatio + (1f - minRadiusRatio) * pressure)
        val r = max(1, pressureRadius.toInt())
        val r2 = r * r
        val startY = max(0, cy - r); val endY = min(height - 1, cy + r)
        val startX = max(0, cx - r); val endX = min(width - 1, cx + r)

        for (py in startY..endY) {
            val dy = py - cy
            for (px in startX..endX) {
                val dx = px - cx
                if (dx * dx + dy * dy <= r2) {
                    val idx = py * width + px
                    // 二値ペン: 選択追加は 255、選択削除は 0
                    m[idx] = if (isAdd) 255.toByte() else 0.toByte()
                }
            }
        }
        _boundsCacheDirty = true  // バウンディングボックスキャッシュを無効化
    }

    // ── 選択範囲生成 ────────────────────────────────────────────

    /** 選択解除 */
    fun clearSelection() {
        _mask = null
        _boundsCacheDirty = true
        PaintDebug.d(PaintDebug.Layer) { "[Selection] cleared" }
    }

    /** 全選択 */
    fun selectAll() {
        val m = ByteArray(width * height)
        m.fill(0xFF.toByte())
        _mask = m
        _boundsCacheDirty = true
        PaintDebug.d(PaintDebug.Layer) { "[Selection] selectAll" }
    }

    /** 矩形選択 */
    fun selectRect(left: Int, top: Int, right: Int, bottom: Int, mode: SelectionMode = SelectionMode.Replace) {
        require(left < right && top < bottom) { "invalid rect: ($left,$top)-($right,$bottom)" }
        val l = max(0, left); val t = max(0, top)
        val r = min(width, right); val b = min(height, bottom)
        if (l >= r || t >= b) {
            if (mode == SelectionMode.Replace) clearSelection()
            return
        }
        val m = prepareMask(mode)
        for (y in t until b) {
            val off = y * width
            for (x in l until r) {
                applyMaskPixel(m, off + x, 255, mode)
            }
        }
        _mask = m
        _boundsCacheDirty = true
        PaintDebug.d(PaintDebug.Layer) { "[Selection] selectRect ($l,$t)-($r,$b) mode=$mode" }
    }

    /** 楕円選択 */
    fun selectEllipse(left: Int, top: Int, right: Int, bottom: Int, mode: SelectionMode = SelectionMode.Replace) {
        require(left <= right && top <= bottom) { "invalid ellipse rect: ($left,$top)-($right,$bottom)" }
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val rx = (right - left) / 2f
        val ry = (bottom - top) / 2f
        if (rx <= 0f || ry <= 0f) {
            if (mode == SelectionMode.Replace) clearSelection()
            return
        }
        val m = prepareMask(mode)
        val l = max(0, left); val t = max(0, top)
        val r = min(width, right); val b = min(height, bottom)
        for (y in t until b) {
            val off = y * width
            val dy = (y + 0.5f - cy) / ry
            for (x in l until r) {
                val dx = (x + 0.5f - cx) / rx
                val dist = dx * dx + dy * dy
                // アンチエイリアス: 境界付近をスムーズに
                val value = when {
                    dist <= 0.98f -> 255
                    dist >= 1.02f -> 0
                    else -> ((1.02f - dist) / 0.04f * 255f).toInt().coerceIn(0, 255)
                }
                if (value > 0) applyMaskPixel(m, off + x, value, mode)
            }
        }
        _mask = m
        _boundsCacheDirty = true
        PaintDebug.d(PaintDebug.Layer) { "[Selection] selectEllipse cx=$cx cy=$cy rx=$rx ry=$ry" }
    }

    /**
     * なげなわ選択（ポリゴン）。
     * @param points 頂点リスト [(x0,y0), (x1,y1), ...] 最低3点必要
     */
    fun selectLasso(points: List<Pair<Int, Int>>, mode: SelectionMode = SelectionMode.Replace) {
        if (points.size < 3) {
            if (mode == SelectionMode.Replace) clearSelection()
            return
        }
        val m = prepareMask(mode)

        // バウンディングボックス計算
        var minX = width; var maxX = 0; var minY = height; var maxY = 0
        for ((px, py) in points) {
            minX = min(minX, px); maxX = max(maxX, px)
            minY = min(minY, py); maxY = max(maxY, py)
        }
        minX = max(0, minX); minY = max(0, minY)
        maxX = min(width - 1, maxX); maxY = min(height - 1, maxY)

        // スキャンライン + 交差判定 (偶奇規則)
        for (y in minY..maxY) {
            val yf = y + 0.5f
            val intersections = mutableListOf<Float>()
            for (i in points.indices) {
                val (x0, y0) = points[i]
                val (x1, y1) = points[(i + 1) % points.size]
                // 辺が水平ならスキップ
                if (y0 == y1) continue
                val y0f = y0.toFloat(); val y1f = y1.toFloat()
                // yf がこの辺の y 範囲内にあるか
                if ((yf < min(y0f, y1f)) || (yf > max(y0f, y1f))) continue
                val t = (yf - y0f) / (y1f - y0f)
                intersections.add(x0 + t * (x1 - x0))
            }
            intersections.sort()
            // ペアごとに塗りつぶし
            var i = 0
            while (i + 1 < intersections.size) {
                val xl = max(minX, intersections[i].toInt())
                val xr = min(maxX, intersections[i + 1].toInt())
                for (x in xl..xr) {
                    applyMaskPixel(m, y * width + x, 255, mode)
                }
                i += 2
            }
        }
        _mask = m
        _boundsCacheDirty = true
        PaintDebug.d(PaintDebug.Layer) { "[Selection] selectLasso points=${points.size}" }
    }

    /**
     * 自動選択（マジックワンド）。
     * フラッドフィルで類似色の連結領域を選択。
     * @param surface サンプリング対象のサーフェス
     * @param startX 開始点 X
     * @param startY 開始点 Y
     * @param tolerance 色の許容範囲 (0..255)
     * @param contiguous true=連続領域のみ, false=画像全体から類似色
     */
    fun selectByColor(
        surface: TiledSurface,
        startX: Int, startY: Int,
        tolerance: Int = 32,
        contiguous: Boolean = true,
        mode: SelectionMode = SelectionMode.Replace,
    ) {
        if (startX < 0 || startX >= width || startY < 0 || startY >= height) return
        val tol = tolerance.coerceIn(0, 255)
        val targetColor = surface.getPixelAt(startX, startY)
        val m = prepareMask(mode)

        if (contiguous) {
            // フラッドフィル (BFS)
            val visited = BooleanArray(width * height)
            val queue = ArrayDeque<Int>(1024)
            val startIdx = startY * width + startX
            queue.add(startIdx)
            visited[startIdx] = true

            while (queue.isNotEmpty()) {
                val idx = queue.removeFirst()
                val x = idx % width; val y = idx / width
                val pixel = surface.getPixelAt(x, y)
                if (!isColorSimilar(targetColor, pixel, tol)) continue
                applyMaskPixel(m, idx, 255, mode)

                // 4方向隣接
                if (x > 0 && !visited[idx - 1]) { visited[idx - 1] = true; queue.add(idx - 1) }
                if (x < width - 1 && !visited[idx + 1]) { visited[idx + 1] = true; queue.add(idx + 1) }
                if (y > 0 && !visited[idx - width]) { visited[idx - width] = true; queue.add(idx - width) }
                if (y < height - 1 && !visited[idx + width]) { visited[idx + width] = true; queue.add(idx + width) }
            }
        } else {
            // 非連続: 全ピクセルスキャン
            for (y in 0 until height) {
                val off = y * width
                for (x in 0 until width) {
                    val pixel = surface.getPixelAt(x, y)
                    if (isColorSimilar(targetColor, pixel, tol)) {
                        applyMaskPixel(m, off + x, 255, mode)
                    }
                }
            }
        }
        _mask = m
        _boundsCacheDirty = true
        PaintDebug.d(PaintDebug.Layer) { "[Selection] selectByColor ($startX,$startY) tol=$tol contiguous=$contiguous" }
    }

    // ── 選択範囲操作 ────────────────────────────────────────────

    /** 選択範囲を反転 */
    fun invertSelection() {
        val m = _mask
        if (m == null) {
            // 選択なし → 何もない = 全反転 = 何もない。selectAll してから反転
            _mask = ByteArray(width * height) // 全 0 = 何も選択されていない
            return
        }
        for (i in m.indices) {
            m[i] = (255 - (m[i].toInt() and 0xFF)).toByte()
        }
        _boundsCacheDirty = true
        PaintDebug.d(PaintDebug.Layer) { "[Selection] inverted" }
    }

    /**
     * 選択範囲を拡張/縮小。
     * @param amount 正=拡張, 負=縮小 (ピクセル数)
     * 最適化: 2パス実装。水平→垂直で O(width*height*radius) に改善
     */
    fun expandContract(amount: Int) {
        val m = _mask ?: return
        if (amount == 0) return
        val absAmt = abs(amount)
        val isExpand = amount > 0

        // 1パス目: 水平処理
        val temp = ByteArray(width * height)
        for (y in 0 until height) {
            val off = y * width
            for (x in 0 until width) {
                var best = if (isExpand) 0 else 255
                val x0 = max(0, x - absAmt)
                val x1 = min(width - 1, x + absAmt)
                for (nx in x0..x1) {
                    val v = m[off + nx].toInt() and 0xFF
                    best = if (isExpand) max(best, v) else min(best, v)
                }
                temp[off + x] = best.toByte()
            }
        }

        // 2パス目: 垂直処理
        val result = ByteArray(width * height)
        for (x in 0 until width) {
            for (y in 0 until height) {
                var best = if (isExpand) 0 else 255
                val y0 = max(0, y - absAmt)
                val y1 = min(height - 1, y + absAmt)
                for (ny in y0..y1) {
                    val v = temp[ny * width + x].toInt() and 0xFF
                    best = if (isExpand) max(best, v) else min(best, v)
                }
                result[y * width + x] = best.toByte()
            }
        }
        _mask = result
        _boundsCacheDirty = true
        PaintDebug.d(PaintDebug.Layer) { "[Selection] expandContract amount=$amount" }
    }

    /**
     * マグネット選択: エッジ吸着。
     * 開始点から周辺のエッジを自動トレース。
     * @param surface スタートエッジ検出用サーフェス
     * @param startX 開始点 X
     * @param startY 開始点 Y
     * @param tolerance エッジ検出の許容値（コントラスト）
     * @param maxDistance 追跡距離限界（ピクセル）
     * @param mode 追加/削除モード
     */
    fun selectMagnet(
        surface: TiledSurface,
        startX: Int, startY: Int,
        tolerance: Int = 32,
        maxDistance: Int = 256,
        mode: SelectionMode = SelectionMode.Replace
    ) {
        if (startX < 0 || startX >= width || startY < 0 || startY >= height) return
        val tol = tolerance.coerceIn(1, 255)
        val maxDist = maxDistance.coerceIn(10, 1024)

        // エッジ検出: Sobel フィルタで勾配計算
        val edges = computeEdges(surface, tol)

        // アクティブな磁石ポイントを中心から開始
        val m = prepareMask(mode)
        val visited = BooleanArray(width * height)
        val queue = ArrayDeque<Int>(1024)

        val startIdx = startY * width + startX
        if (edges[startIdx] > 0) {
            queue.add(startIdx)
            visited[startIdx] = true
            applyMaskPixel(m, startIdx, 255, mode)
        }

        // BFS でエッジに沿ってトレース
        var distTracked = 0
        while (queue.isNotEmpty() && distTracked < maxDist) {
            val idx = queue.removeFirst()
            val x = idx % width; val y = idx / width

            // 4方向隣接を探索
            val neighbors = listOf(
                if (x > 0) idx - 1 else -1,
                if (x < width - 1) idx + 1 else -1,
                if (y > 0) idx - width else -1,
                if (y < height - 1) idx + width else -1
            ).filter { it >= 0 }

            for (nIdx in neighbors) {
                if (!visited[nIdx] && edges[nIdx] > 0) {
                    visited[nIdx] = true
                    queue.add(nIdx)
                    applyMaskPixel(m, nIdx, 255, mode)
                    distTracked++
                }
            }
        }

        _mask = m
        _boundsCacheDirty = true
        PaintDebug.d(PaintDebug.Layer) { "[Selection] selectMagnet ($startX,$startY) tol=$tol" }
    }

    /** Sobel エッジ検出 */
    private fun computeEdges(surface: TiledSurface, tolerance: Int): IntArray {
        val edges = IntArray(width * height)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                // Sobel オペレータ
                val off = y * width + x
                val p00 = surface.getPixelAt(x - 1, y - 1)
                val p10 = surface.getPixelAt(x, y - 1)
                val p20 = surface.getPixelAt(x + 1, y - 1)
                val p01 = surface.getPixelAt(x - 1, y)
                val p21 = surface.getPixelAt(x + 1, y)
                val p02 = surface.getPixelAt(x - 1, y + 1)
                val p12 = surface.getPixelAt(x, y + 1)
                val p22 = surface.getPixelAt(x + 1, y + 1)

                // グレースケール変換（luminance）
                val g00 = pixelToGray(p00)
                val g10 = pixelToGray(p10)
                val g20 = pixelToGray(p20)
                val g01 = pixelToGray(p01)
                val g21 = pixelToGray(p21)
                val g02 = pixelToGray(p02)
                val g12 = pixelToGray(p12)
                val g22 = pixelToGray(p22)

                // Sobel X, Y
                val gx = (-g00 - 2*g01 - g02) + (g20 + 2*g21 + g22)
                val gy = (-g00 - 2*g10 - g20) + (g02 + 2*g12 + g22)

                // 勾配の大きさ
                val magnitude = sqrt((gx * gx + gy * gy).toFloat()).toInt()
                edges[off] = if (magnitude > tolerance) 255 else 0
            }
        }
        return edges
    }

    private fun pixelToGray(pixel: Int): Int {
        val r = PixelOps.red(pixel)
        val g = PixelOps.green(pixel)
        val b = PixelOps.blue(pixel)
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    /** 選択範囲をぼかす (ガウシアンブラー近似: 3パスボックスブラー) */
    fun feather(radius: Int) {
        val m = _mask ?: return
        if (radius <= 0) return
        val safeRadius = radius.coerceIn(1, 50)

        // int 配列に変換して3パスボックスブラー
        val buf1 = IntArray(width * height) { m[it].toInt() and 0xFF }
        val buf2 = IntArray(width * height)

        boxBlurMask(buf1, buf2, width, height, safeRadius)
        boxBlurMask(buf2, buf1, width, height, safeRadius)
        boxBlurMask(buf1, buf2, width, height, safeRadius)

        for (i in m.indices) {
            m[i] = buf2[i].coerceIn(0, 255).toByte()
        }
        _boundsCacheDirty = true
        PaintDebug.d(PaintDebug.Layer) { "[Selection] feather radius=$safeRadius" }
    }

    /** 選択範囲のバウンディングボックスを取得。選択がない場合は null (キャッシュ対応) */
    fun getBounds(): IntArray? {
        val m = _mask ?: return null
        // キャッシュが有効なら返す
        if (!_boundsCacheDirty) return _cachedBounds

        var minX = width; var minY = height; var maxX = 0; var maxY = 0
        for (y in 0 until height) {
            val off = y * width
            for (x in 0 until width) {
                if ((m[off + x].toInt() and 0xFF) > 0) {
                    minX = min(minX, x); maxX = max(maxX, x)
                    minY = min(minY, y); maxY = max(maxY, y)
                }
            }
        }
        if (minX > maxX) {
            _cachedBounds = null
            _boundsCacheDirty = false
            return null
        }
        _cachedBounds = intArrayOf(minX, minY, maxX + 1, maxY + 1)
        _boundsCacheDirty = false
        return _cachedBounds
    }

    // ── 内部ヘルパー ────────────────────────────────────────────

    private fun prepareMask(mode: SelectionMode): ByteArray {
        return when (mode) {
            SelectionMode.Replace -> ByteArray(width * height)
            SelectionMode.Add, SelectionMode.Subtract, SelectionMode.Intersect -> {
                _mask?.copyOf() ?: ByteArray(width * height)
            }
        }
    }

    private fun applyMaskPixel(m: ByteArray, idx: Int, value: Int, mode: SelectionMode) {
        if (idx < 0 || idx >= m.size) return
        val current = m[idx].toInt() and 0xFF
        val result = when (mode) {
            SelectionMode.Replace, SelectionMode.Add -> max(current, value)
            SelectionMode.Subtract -> max(0, current - value)
            SelectionMode.Intersect -> min(current, value)
        }
        m[idx] = result.toByte()
    }

    /** premultiplied ARGB の色差を計算 (unpremultiply してから比較) */
    private fun isColorSimilar(c1: Int, c2: Int, tolerance: Int): Boolean {
        val up1 = PixelOps.unpremultiply(c1)
        val up2 = PixelOps.unpremultiply(c2)
        val da = abs(PixelOps.alpha(up1) - PixelOps.alpha(up2))
        val dr = abs(PixelOps.red(up1) - PixelOps.red(up2))
        val dg = abs(PixelOps.green(up1) - PixelOps.green(up2))
        val db = abs(PixelOps.blue(up1) - PixelOps.blue(up2))
        // ユークリッド距離ではなく最大チャンネル差で判定 (Photoshop 方式)
        return max(da, max(dr, max(dg, db))) <= tolerance
    }

    /** 1パスの水平+垂直ボックスブラー (グレースケール) */
    private fun boxBlurMask(input: IntArray, output: IntArray, w: Int, h: Int, radius: Int) {
        val temp = IntArray(w * h)
        val d = radius * 2 + 1

        // 水平
        for (y in 0 until h) {
            var acc = 0L; val off = y * w
            for (x in -radius..radius) acc += input[off + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                temp[off + x] = (acc / d).toInt()
                val addX = (x + radius + 1).coerceAtMost(w - 1)
                val remX = (x - radius).coerceAtLeast(0)
                acc += input[off + addX] - input[off + remX]
            }
        }
        // 垂直
        for (x in 0 until w) {
            var acc = 0L
            for (y in -radius..radius) acc += temp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                output[y * w + x] = (acc / d).toInt()
                val addY = (y + radius + 1).coerceAtMost(h - 1)
                val remY = (y - radius).coerceAtLeast(0)
                acc += temp[addY * w + x] - temp[remY * w + x]
            }
        }
    }

    companion object {
        /**
         * 矩形選択マスクを生成（自動選択フォールバック用）。
         * @param left 左端 X (inclusive)
         * @param top 上端 Y (inclusive)
         * @param right 右端 X (exclusive)
         * @param bottom 下端 Y (exclusive)
         * @param canvasWidth キャンバス幅
         * @param canvasHeight キャンバス高さ
         * @return 矩形範囲内が 255、外が 0 の ByteArray
         */
        fun createRectangleMask(
            left: Int, top: Int, right: Int, bottom: Int,
            canvasWidth: Int, canvasHeight: Int,
        ): ByteArray {
            require(canvasWidth > 0 && canvasHeight > 0) { "canvas size must be > 0" }
            val mask = ByteArray(canvasWidth * canvasHeight)
            val l = left.coerceIn(0, canvasWidth)
            val t = top.coerceIn(0, canvasHeight)
            val r = right.coerceIn(0, canvasWidth)
            val b = bottom.coerceIn(0, canvasHeight)
            for (y in t until b) {
                for (x in l until r) {
                    mask[y * canvasWidth + x] = 0xFF.toByte()
                }
            }
            return mask
        }
    }
}

/** 選択モード */
enum class SelectionMode {
    /** 既存選択を置き換え */
    Replace,
    /** 既存選択に追加 */
    Add,
    /** 既存選択から除去 */
    Subtract,
    /** 既存選択との共通部分 */
    Intersect,
}
