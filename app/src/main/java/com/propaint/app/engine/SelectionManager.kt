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
    fun paintCircle(cx: Int, cy: Int, radius: Int, isAdd: Boolean) {
        val m = _mask ?: return
        val r = max(1, radius)
        val r2 = r * r
        val startY = max(0, cy - r); val endY = min(height - 1, cy + r)
        val startX = max(0, cx - r); val endX = min(width - 1, cx + r)
        for (py in startY..endY) {
            val dy = py - cy
            for (px in startX..endX) {
                val dx = px - cx
                if (dx * dx + dy * dy <= r2) {
                    m[py * width + px] = if (isAdd) 0xFF.toByte() else 0
                }
            }
        }
    }

    // ── 選択範囲生成 ────────────────────────────────────────────

    /** 選択解除 */
    fun clearSelection() {
        _mask = null
        PaintDebug.d(PaintDebug.Layer) { "[Selection] cleared" }
    }

    /** 全選択 */
    fun selectAll() {
        val m = ByteArray(width * height)
        m.fill(0xFF.toByte())
        _mask = m
        PaintDebug.d(PaintDebug.Layer) { "[Selection] selectAll" }
    }

    /** 矩形選択 */
    fun selectRect(left: Int, top: Int, right: Int, bottom: Int, mode: SelectionMode = SelectionMode.Replace) {
        require(left <= right && top <= bottom) { "invalid rect: ($left,$top)-($right,$bottom)" }
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
                if ((yf < min(y0f, y1f)) || (yf >= max(y0f, y1f))) continue
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
        PaintDebug.d(PaintDebug.Layer) { "[Selection] inverted" }
    }

    /**
     * 選択範囲を拡張/縮小。
     * @param amount 正=拡張, 負=縮小 (ピクセル数)
     */
    fun expandContract(amount: Int) {
        val m = _mask ?: return
        if (amount == 0) return
        val absAmt = abs(amount)
        val isExpand = amount > 0

        val result = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var best = if (isExpand) 0 else 255
                // 正方形カーネルで近傍を検索
                val y0 = max(0, y - absAmt); val y1 = min(height - 1, y + absAmt)
                val x0 = max(0, x - absAmt); val x1 = min(width - 1, x + absAmt)
                for (ny in y0..y1) {
                    for (nx in x0..x1) {
                        // 円形距離チェック
                        val dx = nx - x; val dy = ny - y
                        if (dx * dx + dy * dy > absAmt * absAmt) continue
                        val v = m[ny * width + nx].toInt() and 0xFF
                        best = if (isExpand) max(best, v) else min(best, v)
                    }
                }
                result[y * width + x] = best.toByte()
            }
        }
        _mask = result
        PaintDebug.d(PaintDebug.Layer) { "[Selection] expandContract amount=$amount" }
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
        PaintDebug.d(PaintDebug.Layer) { "[Selection] feather radius=$safeRadius" }
    }

    /** 選択範囲のバウンディングボックスを取得。選択がない場合は null */
    fun getBounds(): IntArray? {
        val m = _mask ?: return null
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
        if (minX > maxX) return null
        return intArrayOf(minX, minY, maxX + 1, maxY + 1)
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
